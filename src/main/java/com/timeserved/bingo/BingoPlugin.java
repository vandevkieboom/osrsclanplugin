package com.timeserved.bingo;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Time Served",
	description = "Tools for the Time Served clan: auto-submits bingo tile proofs when you get a matching drop,"
		+ " and adds a \"!verify <name>\" chat command to check a clan member's rank eligibility.",
	tags = {"bingo", "clan", "loot", "screenshot", "event", "verify", "rank"}
)
public class BingoPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BingoConfig config;

	@Inject
	private BingoApiClient api;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BingoVerificationOverlay verificationOverlay;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private BingoPanel bingoPanel;

	@Inject
	private PendingSubmissionStore pendingStore;

	private NavigationButton bingoNavButton;

	/**
	 * Runs the site's "Auto-Verify" rank check for the given name. "!verify
	 * <name>" really is sent as a normal chat message, same as "!lvl" or
	 * "!kc" — visible to everyone nearby, plugin or not. The looked-up
	 * result then overwrites that message's displayed text (see
	 * setChatReply below), the exact technique RuneLite's own bundled chat
	 * commands use, which only ever affects local rendering: it shows up
	 * for other viewers whose own client also has this command registered
	 * (i.e. other Time Served Bingo plugin users), while anyone else just
	 * sees the plain, unmodified "!verify <name>" they actually typed.
	 */
	private static final String VERIFY_COMMAND = "!verify";

	/** Same visible-reply mechanism as !verify — see setChatReply. */
	private static final String NEEDED_COMMAND = "!needed";

	/** Same visible-reply mechanism as !verify — see setChatReply. */
	private static final String LIVE_COMMAND = "!live";

	/**
	 * Twitch usernames seen live on the last background check, so "Notify me
	 * when clan members go live" only announces new arrivals rather than
	 * re-announcing everyone who was already live on every check. Null until
	 * the first check completes, which seeds this silently instead of
	 * announcing everyone already live at plugin startup as "new".
	 */
	private volatile Set<String> previouslyLiveUsernames;

	/** Sync-reminder fires at most once per plugin session, on whichever outcome comes back first. */
	private boolean checkedRuneProfileSync;

	/**
	 * Item id -> the tiles it can satisfy, for this player's own team only.
	 * Replaced wholesale on refresh; read from the client thread on every loot
	 * event, so it's a concurrent map rather than a plain one.
	 */
	private final Map<Integer, List<BoardResponse.Tile>> tilesByItemId = new ConcurrentHashMap<>();

	/**
	 * Drop proofs that failed to send over the network, waiting to retry.
	 * Backed by PendingSubmissionStore, which mirrors every entry to disk —
	 * startUp() reloads whatever survived a previous session, so a client
	 * restart mid-outage no longer silently loses a real drop with no way
	 * to reconstruct it afterwards.
	 */
	private final Deque<PendingSubmissionStore.PendingItem> retryQueue = new ConcurrentLinkedDeque<>();

	/**
	 * Caps how many failed items this plugin holds in memory (and thus on
	 * disk) at once. Generous rather than tight, now that a restart can't
	 * wipe the queue — this just bounds a genuinely prolonged, unresolved
	 * outage rather than protecting against a restart the way it used to.
	 */
	private static final int MAX_RETRY_QUEUE = 100;

	/**
	 * Tile id -> when it was last attempted (submitted or refused). This exists
	 * purely to collapse the near-simultaneous duplicate: RuneLite's loot
	 * tracker republishes most NPC kills as both NpcLootReceived and
	 * LootReceived, so a single kill would otherwise be submitted twice.
	 *
	 * <p>Deliberately NOT a "don't retry this tile" flag with any longer
	 * lifetime than that: if a submission gets rejected by an admin, a later
	 * genuine re-drop of the same item must be able to try again. The server
	 * is the actual authority on whether a tile still needs proof (it checks
	 * approved+pending counts fresh on every request), so this cache only
	 * needs to survive long enough to de-duplicate one kill's events, not
	 * until the next board refresh.
	 */
	private final Map<String, Long> recentAttempts = new ConcurrentHashMap<>();
	// 2 game ticks (a tick is 600ms) — comfortably longer than the gap between
	// NpcLootReceived and LootReceived firing for the SAME kill (they land in
	// the same tick, or very close), but short enough that genuinely separate
	// kills seconds apart are never mistaken for duplicates. This used to be
	// 30 seconds, which was a real bug: rapid-killing a fast-dying, fast-
	// respawning monster (e.g. farming bones from something weak) meant only
	// the FIRST kill in any 30-second span ever got submitted — every other
	// real, separate kill in that window was silently dropped, since this
	// cache is keyed on tile id alone with no idea whether a later hit is a
	// genuine new kill or the same kill's duplicate event.
	private static final long DEDUPE_WINDOW_MILLIS = 1200L;

	private boolean recentlyAttempted(String tileId)
	{
		long now = System.currentTimeMillis();
		Long last = recentAttempts.putIfAbsent(tileId, now);
		if (last == null)
		{
			return false;
		}
		if (now - last > DEDUPE_WINDOW_MILLIS)
		{
			// Stale — treat as a fresh attempt and reset the window.
			recentAttempts.put(tileId, now);
			return false;
		}
		return true;
	}

	@Provides
	BingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BingoConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(verificationOverlay);
		chatCommandManager.registerCommandAsync(VERIFY_COMMAND, this::onRankCommand);
		chatCommandManager.registerCommandAsync(NEEDED_COMMAND, this::onNeededCommand);
		chatCommandManager.registerCommandAsync(LIVE_COMMAND, this::onLiveCommand);

		bingoNavButton = NavigationButton.builder()
			.tooltip("Bingo")
			.icon(buildNavIcon())
			.priority(5)
			.panel(bingoPanel)
			.build();
		clientToolbar.addNavigation(bingoNavButton);

		List<PendingSubmissionStore.PendingItem> restored = pendingStore.loadAll();
		if (!restored.isEmpty())
		{
			log.debug("Restored {} pending submission(s) from a previous session", restored.size());
		}
		for (PendingSubmissionStore.PendingItem item : restored)
		{
			enqueueRetry(item);
		}

		refreshBoard();
	}

	/**
	 * A plain drawn icon rather than a bundled PNG resource: three small squares on the sidebar rail,
	 * echoing a bingo tile without needing an image asset shipped alongside the plugin.
	 */
	private static BufferedImage buildNavIcon()
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(220, 138, 0));
		int cell = 4;
		int gap = 2;
		for (int row = 0; row < 3; row++)
		{
			for (int col = 0; col < 3; col++)
			{
				if (row == 1 && col == 1)
				{
					continue;
				}
				g.fillRect(1 + col * (cell + gap), 1 + row * (cell + gap), cell, cell);
			}
		}
		g.dispose();
		return icon;
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(verificationOverlay);
		chatCommandManager.unregisterCommand(VERIFY_COMMAND);
		chatCommandManager.unregisterCommand(NEEDED_COMMAND);
		chatCommandManager.unregisterCommand(LIVE_COMMAND);
		clientToolbar.removeNavigation(bingoNavButton);
		bingoPanel.dispose();
		tilesByItemId.clear();
		recentAttempts.clear();
		retryQueue.clear();
		previouslyLiveUsernames = null;
		checkedRuneProfileSync = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refreshBoard();
			checkRuneProfileSync();
			checkBroadcast();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (BingoConfig.GROUP.equals(event.getGroup()) && "apiKey".equals(event.getKey()))
		{
			refreshBoard();
		}
	}

	/**
	 * Tiles get completed by teammates too, so the local copy (item ids to
	 * watch for) goes stale on its own even when this client sees no drops.
	 * A minute is frequent enough to stay reasonably current for a small
	 * clan without hammering the API; this player's own actions (see the
	 * refreshBoard() calls after a successful submit below) update instantly
	 * regardless. Also drains the retry queue — no need for a separate,
	 * faster schedule for that.
	 */
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledRefresh()
	{
		refreshBoard();
		retryPendingSubmissions();
		checkLiveStreams();
		checkBroadcast();
	}

	/**
	 * Fetches the board and rebuilds the item-id lookup that handleLoot()
	 * checks drops against. Team-combined xp/kc tiles need no plugin-side
	 * reporting at all — their progress comes entirely from the website's
	 * own hiscores polling (see osrsclan/api/_lib/board.ts), so this only
	 * ever has to watch for item drops.
	 */
	private void refreshBoard()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			tilesByItemId.clear();
			recentAttempts.clear();
			SwingUtilities.invokeLater(bingoPanel::showNoApiKey);
			return;
		}

		api.fetchBoard(
			apiKey,
			board -> {
				SwingUtilities.invokeLater(() -> bingoPanel.refresh(board));

				BoardResponse.Team myTeam = board.findMyTeam();
				List<BoardResponse.Tile> tiles = myTeam == null ? Collections.emptyList() : myTeam.getTiles();

				Map<Integer, List<BoardResponse.Tile>> nextItemLookup = new HashMap<>();
				for (BoardResponse.Tile tile : tiles)
				{
					if (tile.isXpGoal() || tile.isKcGoal())
					{
						continue;
					}
					for (Integer itemId : tile.getItemIds())
					{
						nextItemLookup.computeIfAbsent(itemId, id -> new ArrayList<>()).add(tile);
					}
				}

				tilesByItemId.clear();
				tilesByItemId.putAll(nextItemLookup);
				log.debug("Bingo board refreshed: watching {} item ids", nextItemLookup.size());
			},
			error -> log.debug("Bingo board refresh failed: {}", error));
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		handleLoot(event.getItems());
	}

	/**
	 * Covers loot that doesn't come straight off an NPC corpse — raid chests,
	 * barrows chests, clue caskets and so on.
	 *
	 * <p>Every loot event represents something actually obtained in game. Buying
	 * an item, withdrawing it from the bank or receiving it in a trade does not
	 * produce one, which is what keeps a bought item from being claimed as a
	 * drop.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		handleLoot(event.getItems());
	}

	private void handleLoot(Collection<ItemStack> items)
	{
		if (items == null || tilesByItemId.isEmpty() || config.apiKey().trim().isEmpty())
		{
			return;
		}

		for (ItemStack item : items)
		{
			List<BoardResponse.Tile> candidates = tilesByItemId.get(item.getId());
			if (candidates == null)
			{
				continue;
			}

			for (BoardResponse.Tile tile : candidates)
			{
				if (!tile.needsMoreProof() || recentlyAttempted(tile.tileId))
				{
					continue;
				}
				// Fired right here, at detection, rather than after the
				// upload succeeds: it's purely cosmetic, so there's no
				// reason to make it wait out a full screenshot-encode +
				// network round trip (which is what made it feel delayed).
				playDropEmote();
				// Reading the item name needs the client thread, and we're on it
				// here — resolve it now rather than inside the upload callback.
				captureAndSubmit(tile, item.getId(), itemName(item.getId()));
			}
		}
	}

	private String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (RuntimeException e)
		{
			log.debug("Could not resolve name for item {}", itemId, e);
			return "item " + itemId;
		}
	}

	private void captureAndSubmit(BoardResponse.Tile tile, int itemId, String itemName)
	{
		// On for exactly the frame this listener captures, then straight back off — see
		// BingoVerificationOverlay's class doc for why this is a toggle and not "always visible".
		verificationOverlay.setCaptureMode(true);
		drawManager.requestNextFrameListener(image -> {
			verificationOverlay.setCaptureMode(false);
			// Copy the frame before leaving the render callback: the Image the
			// client hands over is not ours to keep.
			BufferedImage frame = ImageUtil.bufferedImageFromImage(image);
			executor.execute(() -> encodeAndUpload(tile, itemId, itemName, frame));
		});
	}

	private void encodeAndUpload(BoardResponse.Tile tile, int itemId, String itemName, BufferedImage frame)
	{
		byte[] png;
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(frame, "png", out);
			png = out.toByteArray();
		}
		catch (IOException e)
		{
			log.warn("Failed to encode bingo screenshot", e);
			recentAttempts.remove(tile.tileId);
			notifyPlayer("Could not encode the screenshot for " + itemName);
			return;
		}

		api.submitProof(
			config.apiKey().trim(),
			tile.tileId,
			itemId,
			png,
			() -> onSubmitted(itemName, tile.name),
			error -> {
				// A transport failure is worth retrying — both immediately on
				// the next matching drop (the dedupe window, not "forever", is
				// what lets a genuine re-drop after a later admin rejection go
				// through) and via the retry queue in case no further drop
				// ever comes. A rejection from the server is neither.
				if ("Could not reach the clan site".equals(error))
				{
					recentAttempts.remove(tile.tileId);
					PendingSubmissionStore.PendingItem item = pendingStore.saveProof(tile.tileId, tile.name, itemId, itemName, png);
					if (item != null)
					{
						enqueueRetry(item);
					}
				}
				notifyPlayer(itemName + " not submitted — " + error);
			});
	}

	/** Adds an item to the in-memory retry queue, evicting (and deleting from disk) the oldest if it's full. */
	private void enqueueRetry(PendingSubmissionStore.PendingItem item)
	{
		if (retryQueue.size() >= MAX_RETRY_QUEUE)
		{
			PendingSubmissionStore.PendingItem dropped = retryQueue.poll();
			if (dropped != null)
			{
				pendingStore.remove(dropped);
				log.warn("Bingo retry queue full — dropping oldest queued item {}", dropped.id);
			}
		}
		retryQueue.add(item);
	}

	private void retryPendingSubmissions()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			return;
		}

		PendingSubmissionStore.PendingItem item;
		while ((item = retryQueue.poll()) != null)
		{
			retryProofItem(apiKey, item);
		}
	}

	private void retryProofItem(String apiKey, PendingSubmissionStore.PendingItem item)
	{
		byte[] png = pendingStore.readScreenshot(item);
		if (png == null)
		{
			// The screenshot itself is gone (disk issue between sessions) —
			// nothing left to retry with.
			pendingStore.remove(item);
			return;
		}

		api.submitProof(
			apiKey,
			item.tileId,
			item.itemId,
			png,
			() -> {
				pendingStore.remove(item);
				onSubmitted(item.itemName, item.tileName);
			},
			error -> {
				if ("Could not reach the clan site".equals(error))
				{
					enqueueRetry(item);
				}
				else
				{
					pendingStore.remove(item);
					notifyPlayer(item.itemName + " not submitted — " + error);
				}
			});
	}

	/**
	 * Common success path for a real proof landing: chat message, a note to the sidebar panel (so its
	 * toast can show the same confirmation without needing to be watching chat), plus a refresh.
	 */
	private void onSubmitted(String itemName, String tileName)
	{
		notifyPlayer("Submitted " + itemName + " for tile \"" + tileName + "\"");
		bingoPanel.notifyAutoSubmitted(itemName);
		refreshBoard();
	}

	/**
	 * Plays the Party emote — purely a local rendering override, not a real
	 * triggered emote. {@code Actor.setAnimation} is the same mechanism the
	 * game engine itself uses to play idle/walk animations on any actor.
	 * It's never sent to the server, so nobody else sees it, and it doesn't
	 * block or delay any real action: the next real animation update
	 * (walking, attacking, anything) simply overwrites it, same as it would
	 * overwrite a real emote. Called from handleLoot() at the moment a
	 * matching drop is detected, not from the upload's success callback —
	 * it's cosmetic, so it shouldn't wait out a screenshot-encode + network
	 * round trip.
	 */
	private void playDropEmote()
	{
		if (!config.playDropEmote())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			Player local = client.getLocalPlayer();
			if (local != null)
			{
				local.setAnimation(AnimationID.EMOTE_PARTY);
			}
		});
	}

	private void notifyPlayer(String message)
	{
		if (!config.notifyOnSubmit())
		{
			return;
		}
		sendChatMessage(message, config.submitMessageColor());
	}

	/**
	 * The clan commands (!verify, !needed, !live, the sync reminder) always
	 * show their result regardless of the "Chat message on submit" toggle —
	 * that setting is specifically about drop-submission notifications, not
	 * a command the player just typed — which is also why they pass their
	 * own clanMessageColor() here rather than sharing submitMessageColor: a
	 * bingo drop notification and a clan command reply aren't the same kind
	 * of message, so one color config shouldn't govern both.
	 */
	private void sendChatMessage(String message, Color color)
	{
		String colored = ColorUtil.wrapWithColorTag(message, color);
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", colored, null));
	}

	/**
	 * Handles "!verify [name]" once it's actually been sent (this is
	 * registerCommandAsync's execute callback, run off the client thread
	 * already). No name defaults to the sender — chatMessage.getName() is
	 * this message's own author for a real sent message, so no client-thread
	 * hop is needed to read it the way client.getLocalPlayer() would require.
	 * A usage mistake only gets a private reply — nothing worth other
	 * viewers seeing — but a real lookup result or failure rewrites the sent
	 * message itself via setChatReply, same as !lvl/!kc. No plugin key
	 * needed: this is a clan-wide feature, not a bingo one.
	 */
	private void onRankCommand(ChatMessage chatMessage, String message)
	{
		String rsn = commandArgument(message, VERIFY_COMMAND, chatMessage);
		if (rsn.isEmpty())
		{
			sendChatMessage("Usage - " + VERIFY_COMMAND + " [name]", config.clanMessageColor());
			return;
		}

		api.lookupRank(rsn,
			result -> setChatReply(chatMessage, formatRankResult(result)),
			(error, reason) -> setChatReply(chatMessage, describeRankError(rsn, error, reason)));
	}

	/**
	 * Handles "!needed [name]" — same shape as !verify (real sent message,
	 * self by default, rewritten in place with the result), just reporting
	 * what's missing for the next rank tier instead of the current one.
	 * Kept as its own command rather than folded into !verify's reply so
	 * that reply can stay a single short line.
	 */
	private void onNeededCommand(ChatMessage chatMessage, String message)
	{
		String rsn = commandArgument(message, NEEDED_COMMAND, chatMessage);
		if (rsn.isEmpty())
		{
			sendChatMessage("Usage - " + NEEDED_COMMAND + " [name]", config.clanMessageColor());
			return;
		}

		api.lookupRank(rsn,
			result -> setChatReply(chatMessage, formatNeededResult(result)),
			(error, reason) -> setChatReply(chatMessage, describeRankError(rsn, error, reason)));
	}

	/**
	 * Handles "!live" the same way as !verify: a real sent message that
	 * setChatReply then overwrites with the result. Needs no plugin key —
	 * live status is public site data, same as the site's own homepage.
	 */
	private void onLiveCommand(ChatMessage chatMessage, String message)
	{
		api.fetchLiveStreams(
			streams -> setChatReply(chatMessage, formatLiveStreams(streams)),
			error -> setChatReply(chatMessage, error));
	}

	/** The bit after the command word, or the sender's own name if nothing follows it. */
	private String commandArgument(String message, String command, ChatMessage chatMessage)
	{
		String typed = message.length() > command.length() ? message.substring(command.length()).trim() : "";
		return typed.isEmpty() ? Text.sanitize(chatMessage.getName()) : typed;
	}

	/**
	 * Overwrites the already-sent message's displayed text with the lookup
	 * result — RuneLite's own bundled chat commands (!lvl, !kc, ...) use
	 * this exact mechanism. Purely a local rendering override, same family
	 * as playDropEmote()'s Actor.setAnimation: it changes nothing on the
	 * wire, so it only shows up for other viewers whose own client also has
	 * this command registered.
	 */
	private void setChatReply(ChatMessage chatMessage, String reply)
	{
		String colored = ColorUtil.wrapWithColorTag(reply, config.clanMessageColor());
		clientThread.invokeLater(() -> {
			MessageNode messageNode = chatMessage.getMessageNode();
			messageNode.setRuneLiteFormatMessage(colored);
			client.refreshChat();
		});
	}

	private String describeRankError(String rsn, String error, String reason)
	{
		if ("not-on-runeprofile".equals(reason))
		{
			return rsn + " hasn't synced RuneProfile yet — install it and open your collection log.";
		}
		return "Rank lookup for " + rsn + " failed - " + error;
	}

	private String formatRankResult(BingoApiClient.RankLookupResult result)
	{
		String eligible = result.eligibleRank != null ? result.eligibleRank : "no rank yet";
		return result.rsn + " qualifies for " + eligible + " - " + result.overallSatisfied + "/" + result.overallTotal + " items";
	}

	private String formatNeededResult(BingoApiClient.RankLookupResult result)
	{
		if (result.nextRank == null)
		{
			return result.rsn + " is already at the top rank.";
		}
		if (result.neededForNextRank == null || result.neededForNextRank <= 0)
		{
			return result.rsn + " already qualifies for " + result.nextRank + ".";
		}

		StringBuilder text = new StringBuilder()
			.append(result.rsn).append(" needs ").append(result.neededForNextRank)
			.append(" more for ").append(result.nextRank);
		List<String> missing = result.missingItemNames;
		if (missing != null && !missing.isEmpty())
		{
			text.append(": ");
			int shown = Math.min(5, missing.size());
			text.append(String.join(", ", missing.subList(0, shown)));
			if (missing.size() > shown)
			{
				text.append(", +").append(missing.size() - shown).append(" more");
			}
		}
		return text.toString();
	}

	private String formatLiveStreams(List<BingoApiClient.LiveStream> streams)
	{
		if (streams.isEmpty())
		{
			return "No clan members are live right now.";
		}

		int shown = Math.min(5, streams.size());
		StringBuilder text = new StringBuilder("Live now: ");
		for (int i = 0; i < shown; i++)
		{
			if (i > 0)
			{
				text.append(", ");
			}
			text.append(streams.get(i).displayName);
		}
		if (streams.size() > shown)
		{
			text.append(", +").append(streams.size() - shown).append(" more");
		}
		return text.toString();
	}

	/**
	 * Backs the "Notify me when clan members go live" toggle. The first
	 * check after startUp/reconnect only seeds previouslyLiveUsernames
	 * silently — otherwise everyone already live at that moment would get
	 * announced as "newly" live just because the plugin only just started
	 * watching.
	 */
	private void checkLiveStreams()
	{
		if (!config.notifyLiveStreams())
		{
			return;
		}

		api.fetchLiveStreams(
			streams -> {
				Set<String> nowLive = new HashSet<>();
				for (BingoApiClient.LiveStream stream : streams)
				{
					nowLive.add(stream.username);
				}

				Set<String> previous = previouslyLiveUsernames;
				previouslyLiveUsernames = nowLive;
				if (previous == null)
				{
					return;
				}

				for (BingoApiClient.LiveStream stream : streams)
				{
					if (!previous.contains(stream.username))
					{
						sendChatMessage(stream.displayName + " just went live", config.clanMessageColor());
					}
				}
			},
			error -> log.debug("Failed to check live streams: {}", error));
	}

	/**
	 * Backs the "Remind me to sync RuneProfile" toggle. Only ever detects
	 * "never set up on RuneProfile at all" (a 404 from the site, see
	 * BingoApiClient#lookupRank's reason field) — there's no confirmed way
	 * to tell a stale-but-present sync from a fresh one, so that case isn't
	 * covered. Runs once per session, right after login.
	 */
	private void checkRuneProfileSync()
	{
		if (!config.remindRuneProfileSync() || checkedRuneProfileSync)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}

		checkedRuneProfileSync = true;
		api.lookupRank(local.getName(),
			result -> {},
			(error, reason) -> {
				if ("not-on-runeprofile".equals(reason))
				{
					sendChatMessage("You haven't synced RuneProfile yet — install it and open your"
						+ " collection log so rank checks can see your progress.", config.clanMessageColor());
				}
			});
	}

	private static final String LAST_SEEN_BROADCAST_KEY = "lastSeenBroadcast";

	/**
	 * Backs the "Clan broadcasts" toggle: shows the latest one-off message an
	 * admin has pushed out from the site's Board Config panel, once per
	 * message. The last-shown timestamp is persisted via ConfigManager
	 * (rather than kept in memory like checkedRuneProfileSync above) since a
	 * broadcast can happen at any point during play, not just once per
	 * session — an in-memory flag would re-show the same message after every
	 * client restart.
	 */
	private void checkBroadcast()
	{
		if (!config.notifyBroadcasts())
		{
			return;
		}

		api.fetchBroadcast(
			result -> {
				if (result.message == null || result.message.isEmpty() || result.updatedAt == null)
				{
					return;
				}
				String lastSeen = configManager.getConfiguration(BingoConfig.GROUP, LAST_SEEN_BROADCAST_KEY);
				if (result.updatedAt.equals(lastSeen))
				{
					return;
				}
				configManager.setConfiguration(BingoConfig.GROUP, LAST_SEEN_BROADCAST_KEY, result.updatedAt);
				sendChatMessage(result.message, config.clanMessageColor());
			},
			error -> log.debug("Failed to check broadcast: {}", error));
	}
}
