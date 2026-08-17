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
		+ " plus \"!rank\"/\"!verify\" chat commands to check a clan member's rank eligibility and gear requirements.",
	tags = {"bingo", "clan", "loot", "screenshot", "verify", "rank", "twitch"}
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
	private BingoCodewordOverlay codewordOverlay;

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
	 * Runs the site's "Auto-Verify" rank check for the given name - what
	 * this plugin's "!verify" command used to be before it was split in
	 * two (see VERIFY_COMMAND below): "!rank" now reports which rank tier
	 * someone is eligible for, while "!verify" checks the separate,
	 * harder clan-gear requirement. "!rank <name>" really is sent as a
	 * normal chat message, same as "!lvl" or "!kc" - visible to everyone
	 * nearby, plugin or not. The looked-up result then overwrites that
	 * message's displayed text (see setChatReply below), the exact
	 * technique RuneLite's own bundled chat commands use, which only ever
	 * affects local rendering: it shows up for other viewers whose own
	 * client also has this command registered (i.e. other Time Served
	 * Bingo plugin users), while anyone else just sees the plain,
	 * unmodified "!rank <name>" they actually typed.
	 */
	private static final String RANK_COMMAND = "!rank";

	/**
	 * Checks whether a member meets the clan's hard gear/kc requirement -
	 * the same three-way check ("Auto-Verify" used to just mean rank
	 * eligibility; this is a separate, stricter gate) the site itself runs
	 * on its Clan Rankings page: 6+ Crystal Armour Seeds plus an Enhanced
	 * Crystal Weapon Seed, OR 800+ Corrupted Gauntlet kc, OR a Twisted
	 * Bow. Same visible-reply mechanism as !rank - see setChatReply.
	 */
	private static final String VERIFY_COMMAND = "!verify";

	/** Same visible-reply mechanism as !rank - see setChatReply. */
	private static final String NEEDED_COMMAND = "!needed";

	/** Same visible-reply mechanism as !rank - see setChatReply. */
	private static final String LIVE_COMMAND = "!live";

	/** Guards registerCommands()/unregisterCommands() so either is safe to call more than once in a row. */
	private boolean commandsRegistered;

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
	 * Whether the site last reported an active bingo event. Kept current by
	 * checkBingoStatus, a separate, deliberately tiny/cheap/cached request
	 * (see BingoApiClient#fetchBingoStatus) that runs every scheduled tick
	 * regardless of this value - unlike the actual board fetch, which is
	 * expensive (tiles/teams/submissions) and only ever runs while this is
	 * true. Defaults true so the plugin behaves normally until it's actually
	 * heard otherwise, rather than starting paused on a fresh session.
	 */
	private volatile boolean bingoActive = true;

	/**
	 * Item id -> the tiles it can satisfy, for this player's own team only.
	 * Replaced wholesale on refresh; read from the client thread on every loot
	 * event, so it's a concurrent map rather than a plain one.
	 */
	private final Map<Integer, List<BoardResponse.Tile>> tilesByItemId = new ConcurrentHashMap<>();

	/**
	 * Drop proofs that failed to send over the network, waiting to retry.
	 * Backed by PendingSubmissionStore, which mirrors every entry to disk -
	 * startUp() reloads whatever survived a previous session, so a client
	 * restart mid-outage no longer silently loses a real drop with no way
	 * to reconstruct it afterwards.
	 */
	private final Deque<PendingSubmissionStore.PendingItem> retryQueue = new ConcurrentLinkedDeque<>();

	/**
	 * Caps how many failed items this plugin holds in memory (and thus on
	 * disk) at once. Generous rather than tight, now that a restart can't
	 * wipe the queue - this just bounds a genuinely prolonged, unresolved
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
	// 2 game ticks (a tick is 600ms) - comfortably longer than the gap between
	// NpcLootReceived and LootReceived firing for the SAME kill (they land in
	// the same tick, or very close), but short enough that genuinely separate
	// kills seconds apart are never mistaken for duplicates. This used to be
	// 30 seconds, which was a real bug: rapid-killing a fast-dying, fast-
	// respawning monster (e.g. farming bones from something weak) meant only
	// the FIRST kill in any 30-second span ever got submitted - every other
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
			// Stale - treat as a fresh attempt and reset the window.
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
		overlayManager.add(codewordOverlay);
		if (config.enableClanCommands())
		{
			registerCommands();
		}

		bingoNavButton = NavigationButton.builder()
			.tooltip("Bingo")
			.icon(buildNavIcon())
			.priority(5)
			.panel(bingoPanel)
			.build();
		if (config.showSidebar())
		{
			clientToolbar.addNavigation(bingoNavButton);
		}

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
		overlayManager.remove(codewordOverlay);
		unregisterCommands();
		clientToolbar.removeNavigation(bingoNavButton);
		bingoPanel.dispose();
		tilesByItemId.clear();
		recentAttempts.clear();
		retryQueue.clear();
		previouslyLiveUsernames = null;
		checkedRuneProfileSync = false;
		bingoActive = true;
	}

	/** Idempotent - safe to call when the commands are already registered (guarded by commandsRegistered). */
	private void registerCommands()
	{
		if (commandsRegistered)
		{
			return;
		}
		chatCommandManager.registerCommandAsync(RANK_COMMAND, this::onRankCommand);
		chatCommandManager.registerCommandAsync(VERIFY_COMMAND, this::onVerifyCommand);
		chatCommandManager.registerCommandAsync(NEEDED_COMMAND, this::onNeededCommand);
		chatCommandManager.registerCommandAsync(LIVE_COMMAND, this::onLiveCommand);
		commandsRegistered = true;
	}

	/** Idempotent - safe to call when the commands aren't currently registered. */
	private void unregisterCommands()
	{
		if (!commandsRegistered)
		{
			return;
		}
		chatCommandManager.unregisterCommand(RANK_COMMAND);
		chatCommandManager.unregisterCommand(VERIFY_COMMAND);
		chatCommandManager.unregisterCommand(NEEDED_COMMAND);
		chatCommandManager.unregisterCommand(LIVE_COMMAND);
		commandsRegistered = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Fires on every area/instance load, not just a literal login -
			// during something like raids or minigames this can happen many
			// times in quick succession, so the expensive board fetch only
			// runs here if bingo is actually active; checkBingoStatus's next
			// tick picks up a reactivation regardless of how long it's been
			// since this last fired.
			if (bingoActive)
			{
				refreshBoard();
			}
			checkRuneProfileSync();
			checkBroadcast();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BingoConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("apiKey".equals(event.getKey()))
		{
			refreshBoard();
		}
		else if ("showSidebar".equals(event.getKey()))
		{
			if (config.showSidebar())
			{
				clientToolbar.addNavigation(bingoNavButton);
			}
			else
			{
				clientToolbar.removeNavigation(bingoNavButton);
			}
		}
		else if ("enableClanCommands".equals(event.getKey()))
		{
			if (config.enableClanCommands())
			{
				registerCommands();
			}
			else
			{
				unregisterCommands();
			}
		}
	}

	/**
	 * Runs every minute for every plugin user, forever - this is a general
	 * clan tool (chat commands, live-stream/broadcast notifications), not a
	 * bingo-only one, so most installs run this whether or not a bingo event
	 * even exists. checkBingoStatus is a deliberately tiny, cached, near-free
	 * request (see BingoApiClient#fetchBingoStatus) that's cheap enough to
	 * run every tick regardless - the actual expensive work (refreshBoard,
	 * which queries tiles/teams/submissions) only happens on ticks where
	 * that check says a bingo event is genuinely active, so there's nothing
	 * bingo-related running at all beyond one tiny cached ping while no
	 * event is on. checkLiveStreams/checkBroadcast stay on this same
	 * 1-minute cadence unconditionally - going live or an admin broadcast
	 * are both things worth surfacing promptly.
	 */
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledRefresh()
	{
		checkBingoStatus();
		checkLiveStreams();
		checkBroadcast();
	}

	/**
	 * The only thing that runs every tick regardless of whether bingo is
	 * active - see fetchBingoStatus's doc for why this is safe to do at
	 * this frequency. Triggers the real (expensive) refreshBoard only once
	 * this comes back true; otherwise nothing bingo-related happens this
	 * tick at all.
	 */
	private void checkBingoStatus()
	{
		api.fetchBingoStatus(
			status -> {
				bingoActive = status.bingoActive;
				if (bingoActive)
				{
					refreshBoard();
					retryPendingSubmissions();
				}
			},
			error -> log.debug("Failed to check bingo status: {}", error));
	}

	/**
	 * Fetches the board and rebuilds the item-id lookup that handleLoot()
	 * checks drops against. Team-combined xp/kc tiles need no plugin-side
	 * reporting at all - their progress comes entirely from the website's
	 * own hiscores polling (see osrsclan/api/_lib/board.ts), so this only
	 * ever has to watch for item drops.
	 *
	 * <p>Called directly (bypassing checkBingoStatus's gate) from
	 * startUp/onGameStateChanged/onConfigChanged/onSubmitted - a login, a
	 * key change, or a real submission always gets an immediate, real check
	 * rather than waiting on the next scheduled tick.
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
	 * Covers loot that doesn't come straight off an NPC corpse - raid chests,
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
				// here - resolve it now rather than inside the upload callback.
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
		// Whatever's already on screen (including the codeword overlay, if the player has it on)
		// just gets picked up as part of this frame like any other overlay - see
		// BingoCodewordOverlay's class doc for why there's no separate capture-only overlay anymore.
		drawManager.requestNextFrameListener(image -> {
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
				// A transport failure is worth retrying - both immediately on
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
				notifyPlayer(itemName + " not submitted - " + error);
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
				log.warn("Bingo retry queue full - dropping oldest queued item {}", dropped.id);
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
			// The screenshot itself is gone (disk issue between sessions) -
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
					notifyPlayer(item.itemName + " not submitted - " + error);
				}
			});
	}

	/** Common success path for a real proof landing: chat message plus a board refresh. */
	private void onSubmitted(String itemName, String tileName)
	{
		notifyPlayer("Submitted " + itemName + " for tile \"" + tileName + "\"");
		refreshBoard();
	}

	/**
	 * Plays the Crab dance emote - purely a local rendering override, not a real
	 * triggered emote. {@code Actor.setAnimation} is the same mechanism the
	 * game engine itself uses to play idle/walk animations on any actor.
	 * It's never sent to the server, so nobody else sees it, and it doesn't
	 * block or delay any real action: the next real animation update
	 * (walking, attacking, anything) simply overwrites it, same as it would
	 * overwrite a real emote. Called from handleLoot() at the moment a
	 * matching drop is detected, not from the upload's success callback -
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
				local.setAnimation(AnimationID.HUMAN_EMOTE_CRABDANCE);
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
	 * The clan commands (!rank, !verify, !needed, !live, the sync reminder)
	 * always show their result regardless of the "Chat message on submit"
	 * toggle - that setting is specifically about drop-submission
	 * notifications, not a command the player just typed - which is also
	 * why they pass their own clanMessageColor() here rather than sharing
	 * submitMessageColor: a bingo drop notification and a clan command
	 * reply aren't the same kind of message, so one color config shouldn't
	 * govern both.
	 */
	private void sendChatMessage(String message, Color color)
	{
		String colored = ColorUtil.wrapWithColorTag(message, color);
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", colored, null));
	}

	/**
	 * Handles "!rank [name]" once it's actually been sent (this is
	 * registerCommandAsync's execute callback, run off the client thread
	 * already). No name defaults to the sender - chatMessage.getName() is
	 * this message's own author for a real sent message, so no client-thread
	 * hop is needed to read it the way client.getLocalPlayer() would require.
	 * A usage mistake only gets a private reply - nothing worth other
	 * viewers seeing - but a real lookup result or failure rewrites the sent
	 * message itself via setChatReply, same as !lvl/!kc. No plugin key
	 * needed: this is a clan-wide feature, not a bingo one.
	 */
	private void onRankCommand(ChatMessage chatMessage, String message)
	{
		String rsn = commandArgument(message, RANK_COMMAND, chatMessage);
		if (rsn.isEmpty())
		{
			sendChatMessage("Usage - " + RANK_COMMAND + " [name]", config.clanMessageColor());
			return;
		}

		api.lookupRank(rsn,
			result -> setChatReply(chatMessage, formatRankResult(result)),
			(error, reason) -> setChatReply(chatMessage, describeRankError(rsn, error, reason)));
	}

	/**
	 * Handles "!verify [name]" - same shape as !rank, but a different,
	 * stricter check: the clan's hard gear/kc gate (see VERIFY_COMMAND's
	 * doc) rather than the rank-tier ladder. Kept as its own command
	 * rather than folded into !rank's reply since they answer genuinely
	 * different questions ("what rank" vs. "do they meet the hard gate").
	 */
	private void onVerifyCommand(ChatMessage chatMessage, String message)
	{
		String rsn = commandArgument(message, VERIFY_COMMAND, chatMessage);
		if (rsn.isEmpty())
		{
			sendChatMessage("Usage - " + VERIFY_COMMAND + " [name]", config.clanMessageColor());
			return;
		}

		api.checkClanRequirement(rsn,
			result -> setChatReply(chatMessage, formatClanRequirementResult(result)),
			(error, reason) -> setChatReply(chatMessage, describeRankError(rsn, error, reason)));
	}

	/**
	 * Handles "!needed [name]" - same shape as !rank (real sent message,
	 * self by default, rewritten in place with the result), just reporting
	 * what's missing for the next rank tier instead of the current one.
	 * Kept as its own command rather than folded into !rank's reply so
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
	 * Handles "!live" the same way as !rank: a real sent message that
	 * setChatReply then overwrites with the result. Needs no plugin key -
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
	 * result - RuneLite's own bundled chat commands (!lvl, !kc, ...) use
	 * this exact mechanism. Purely a local rendering override, same family
	 * as playDropEmote()'s Actor.setAnimation: it changes nothing on the
	 * wire, so it only shows up for other viewers whose own client also has
	 * this command registered.
	 *
	 * <p>Deliberately no explicit color tag here, unlike sendChatMessage()'s
	 * synthesized notifications - this is rewriting a message that was
	 * already sent in whatever channel the player actually typed it into
	 * (clan chat, public chat, a friends chat, ...), so leaving it uncolored
	 * lets it inherit that channel's own color the same way !lvl/!kc's
	 * replies do, including anyone's own "Chat Colors" plugin customization
	 * for that channel. Forcing clanMessageColor() here would override that
	 * per-channel color with one fixed color regardless of where the command
	 * was actually typed.
	 */
	private void setChatReply(ChatMessage chatMessage, String reply)
	{
		clientThread.invokeLater(() -> {
			MessageNode messageNode = chatMessage.getMessageNode();
			messageNode.setRuneLiteFormatMessage(reply);
			client.refreshChat();
		});
	}

	private String describeRankError(String rsn, String error, String reason)
	{
		if ("not-on-runeprofile".equals(reason))
		{
			return rsn + " hasn't synced RuneProfile yet - install it and open your collection log.";
		}
		return "Rank lookup for " + rsn + " failed - " + error;
	}

	private String formatRankResult(BingoApiClient.RankLookupResult result)
	{
		String eligible = result.eligibleRank != null ? result.eligibleRank : "no rank yet";
		return result.rsn + " qualifies for " + eligible + " - " + result.overallSatisfied + "/" + result.overallTotal + " items";
	}

	private String formatClanRequirementResult(BingoApiClient.ClanRequirementResult result)
	{
		return result.meets
			? result.rsn + " meets the clan requirements"
			: result.rsn + " does not meet the clan requirements";
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
	 * silently - otherwise everyone already live at that moment would get
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
	 * BingoApiClient#lookupRank's reason field) - there's no confirmed way
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
					sendChatMessage("You haven't synced RuneProfile yet - install it and open your"
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
	 * session - an in-memory flag would re-show the same message after every
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
