package com.timeserved.bingo;

import com.google.inject.Provides;
import java.awt.Color;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
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
	private BingoGroundItemsOverlay groundItemsOverlay;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private LfgPanel lfgPanel;

	private NavigationButton lfgNavButton;

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

	/** Lowercased skill name -> the team-xp tile watching it. */
	private volatile Map<String, BoardResponse.Tile> xpTilesBySkill = Collections.emptyMap();

	/** Lowercased boss name -> the team-kc tile watching it. */
	private volatile Map<String, BoardResponse.Tile> kcTilesByBoss = Collections.emptyMap();

	/** Ground items currently visible whose id satisfies an outstanding tile. */
	private final Map<TileItem, TrackedGroundItem> trackedGroundItems = new ConcurrentHashMap<>();

	/** A tracked ground item's real 3D loot beam, plus what to label it with. */
	static class TrackedGroundItem
	{
		final LocalPoint location;
		final String tileName;
		final BingoLootbeam beam;

		TrackedGroundItem(LocalPoint location, String tileName, BingoLootbeam beam)
		{
			this.location = location;
			this.tileName = tileName;
			this.beam = beam;
		}
	}

	/** Screenshots that failed to upload over the network, waiting to retry. */
	private final Deque<PendingSubmission> retryQueue = new ConcurrentLinkedDeque<>();

	/**
	 * Caps how many failed screenshots this plugin holds in memory at once —
	 * deliberately small and in-memory only (nothing persisted to disk), so a
	 * prolonged outage drops the oldest queued screenshot rather than growing
	 * without bound.
	 */
	private static final int MAX_RETRY_QUEUE = 20;

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
	private static final long DEDUPE_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(30);

	/**
	 * Matches OSRS's generic "kill count" family of chat messages ("Your
	 * Zulrah kill count is: 50.", "...completion count for...", "...lap
	 * count is:...", etc). Mirrors KILLCOUNT_PATTERN in RuneLite's own
	 * bundled ChatCommandsPlugin.
	 */
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
		"Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? "
			+ "(?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)"
			+ "is: ?<col=[0-9a-f]{6}>(?<kc>[0-9,]+)</col>");

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
		overlayManager.add(groundItemsOverlay);
		chatCommandManager.registerCommandAsync(VERIFY_COMMAND, this::onRankCommand);
		chatCommandManager.registerCommandAsync(NEEDED_COMMAND, this::onNeededCommand);
		chatCommandManager.registerCommandAsync(LIVE_COMMAND, this::onLiveCommand);

		BufferedImage lfgIcon = ImageUtil.loadImageResource(getClass(), "/com/timeserved/bingo/lfg_icon.png");
		lfgNavButton = NavigationButton.builder()
			.tooltip("LFG")
			.icon(lfgIcon)
			.priority(5)
			.panel(lfgPanel)
			.build();
		clientToolbar.addNavigation(lfgNavButton);

		refreshBoard();
		refreshLfg();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(groundItemsOverlay);
		chatCommandManager.unregisterCommand(VERIFY_COMMAND);
		chatCommandManager.unregisterCommand(NEEDED_COMMAND);
		chatCommandManager.unregisterCommand(LIVE_COMMAND);
		clientToolbar.removeNavigation(lfgNavButton);
		tilesByItemId.clear();
		recentAttempts.clear();
		for (TrackedGroundItem tracked : trackedGroundItems.values())
		{
			tracked.beam.remove();
		}
		trackedGroundItems.clear();
		retryQueue.clear();
		warnedUnrecognisedSkills.clear();
		xpTilesBySkill = Collections.emptyMap();
		kcTilesByBoss = Collections.emptyMap();
		previouslyLiveUsernames = null;
		checkedRuneProfileSync = false;
	}

	Map<TileItem, TrackedGroundItem> getTrackedGroundItems()
	{
		return trackedGroundItems;
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
	 * watch for, xp/kc tiles to report to) goes stale on its own even when
	 * this client sees no drops. A minute is frequent enough to stay
	 * reasonably current for a small clan without hammering the API; this
	 * player's own actions (see the refreshBoard() calls after a successful
	 * submit/report below) update instantly regardless. Also drains the
	 * retry queue — no need for a separate, faster schedule for that.
	 */
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledRefresh()
	{
		refreshBoard();
		retryPendingSubmissions();
		checkLiveStreams();
		checkBroadcast();
		refreshLfg();
	}

	/**
	 * Refreshes the LFG panel's list. Unlike the bingo board (which still
	 * shows something useful with no key), the LFG board is entirely
	 * plugin-key-gated, so an empty key just clears the panel instead of
	 * calling out to the site.
	 */
	private void refreshLfg()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			SwingUtilities.invokeLater(() -> lfgPanel.refresh(Collections.emptyList()));
			return;
		}
		api.fetchLfgPosts(apiKey,
			posts -> SwingUtilities.invokeLater(() -> lfgPanel.refresh(posts)),
			error -> log.debug("LFG refresh failed: {}", error));
	}

	private void refreshBoard()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			tilesByItemId.clear();
			recentAttempts.clear();
			xpTilesBySkill = Collections.emptyMap();
			kcTilesByBoss = Collections.emptyMap();
			return;
		}

		api.fetchBoard(
			apiKey,
			board -> {
				BoardResponse.Team myTeam = board.findMyTeam();
				List<BoardResponse.Tile> tiles = myTeam == null ? Collections.emptyList() : myTeam.getTiles();

				Map<Integer, List<BoardResponse.Tile>> nextItemLookup = new HashMap<>();
				Map<String, BoardResponse.Tile> nextXpTiles = new HashMap<>();
				Map<String, BoardResponse.Tile> nextKcTiles = new HashMap<>();
				for (BoardResponse.Tile tile : tiles)
				{
					if (tile.isXpGoal())
					{
						nextXpTiles.put(tile.goalKey.trim().toLowerCase(), tile);
					}
					else if (tile.isKcGoal())
					{
						nextKcTiles.put(tile.goalKey.trim().toLowerCase(), tile);
					}
					else
					{
						for (Integer itemId : tile.getItemIds())
						{
							nextItemLookup.computeIfAbsent(itemId, id -> new ArrayList<>()).add(tile);
						}
					}
				}

				tilesByItemId.clear();
				tilesByItemId.putAll(nextItemLookup);
				xpTilesBySkill = nextXpTiles;
				kcTilesByBoss = nextKcTiles;
				reportXpProgress(apiKey, nextXpTiles);
				log.debug("Bingo board refreshed: watching {} item ids, {} xp tiles, {} kc tiles",
					nextItemLookup.size(), nextXpTiles.size(), nextKcTiles.size());
			},
			error -> log.debug("Bingo board refresh failed: {}", error));
	}

	/** goalKeys we've already warned about once, so a bad tile doesn't spam chat every refresh. */
	private final Set<String> warnedUnrecognisedSkills = ConcurrentHashMap.newKeySet();

	/**
	 * Reads this player's current xp for every skill an active team-xp tile
	 * watches and reports it. Safe to call on every refresh — the server
	 * tracks each member's own baseline and only a rising reading ever moves
	 * the team total, so re-reporting the same or a lower value is a no-op.
	 */
	private void reportXpProgress(String apiKey, Map<String, BoardResponse.Tile> xpTiles)
	{
		if (xpTiles.isEmpty())
		{
			return;
		}

		clientThread.invokeLater(() -> {
			// Guards against a real correctness bug: startUp() calls
			// refreshBoard() unconditionally, which can happen before a
			// character is even loaded (e.g. still on the login screen).
			// getSkillExperience() would read 0 in that case, and since a
			// player's *first-ever* report becomes their permanent
			// baseline server-side, that 0 would wrongly credit their
			// entire lifetime xp in that skill to the tile. Skipping the
			// whole cycle here is harmless — the next refresh after actual
			// login (onGameStateChanged already triggers one) reports the
			// real reading instead.
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}

			for (Map.Entry<String, BoardResponse.Tile> entry : xpTiles.entrySet())
			{
				Skill skill = skillFromName(entry.getKey());
				if (skill == null)
				{
					continue;
				}
				long xp = client.getSkillExperience(skill);
				BoardResponse.Tile tile = entry.getValue();
				// No refreshBoard() on success here, unlike the kc report
				// below — this call is itself made from inside
				// refreshBoard()'s own callback, so that would recurse
				// forever. The next scheduled refresh picks up the new total.
				api.reportProgress(apiKey, "xp", tile.goalKey, xp,
					() -> {},
					error -> log.debug("Failed to report {} xp: {}", skill, error));
			}
		});
	}

	/**
	 * Matches a tile's goalKey against a real skill, tolerating the two
	 * mismatches an admin is actually likely to type: the old "Runecrafting"
	 * name (OSRS renamed it to "Runecraft") and the American "Defense"
	 * spelling (OSRS uses "Defence"). Anything else that still doesn't match
	 * gets a one-time chat warning instead of failing invisibly — a typo'd
	 * skill name would otherwise silently never report, with the only trace
	 * being a debug-level log line nobody sees.
	 */
	private Skill skillFromName(String name)
	{
		String normalized = name.trim().toLowerCase();
		if ("runecrafting".equals(normalized))
		{
			normalized = "runecraft";
		}
		else if ("defense".equals(normalized))
		{
			normalized = "defence";
		}

		try
		{
			return Skill.valueOf(normalized.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			if (warnedUnrecognisedSkills.add(normalized))
			{
				notifyPlayer("\"" + name + "\" on your team's board isn't a real skill name — ask an admin to fix that tile.");
			}
			return null;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		ChatMessageType type = chatMessage.getType();
		if (type != ChatMessageType.TRADE && type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.SPAM && type != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}

		Map<String, BoardResponse.Tile> kcTiles = kcTilesByBoss;
		if (kcTiles.isEmpty())
		{
			return;
		}

		Matcher matcher = KILLCOUNT_PATTERN.matcher(chatMessage.getMessage());
		if (!matcher.find())
		{
			return;
		}

		BoardResponse.Tile tile = kcTiles.get(matcher.group("boss").trim().toLowerCase());
		if (tile == null)
		{
			return;
		}

		long kc;
		try
		{
			kc = Long.parseLong(matcher.group("kc").replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}

		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			return;
		}
		// Unlike the xp report below, this one is safe to follow with a
		// refresh: it's not itself running inside refreshBoard()'s callback,
		// so there's no risk of looping — just an instant panel update the
		// moment a tracked kill count ticks over.
		api.reportProgress(apiKey, "kc", tile.goalKey, kc,
			this::refreshBoard,
			error -> log.debug("Failed to report {} kc: {}", tile.goalKey, error));
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (!config.highlightGroundItems())
		{
			return;
		}
		List<BoardResponse.Tile> candidates = tilesByItemId.get(event.getItem().getId());
		if (candidates == null || candidates.isEmpty())
		{
			return;
		}

		BingoLootbeam beam = new BingoLootbeam(
			client, clientThread, event.getTile().getWorldLocation(),
			config.groundItemHighlightColor(), BingoLootbeam.Style.MODERN);
		trackedGroundItems.put(event.getItem(),
			new TrackedGroundItem(event.getTile().getLocalLocation(), candidates.get(0).name, beam));
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		TrackedGroundItem tracked = trackedGroundItems.remove(event.getItem());
		if (tracked != null)
		{
			tracked.beam.remove();
		}
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

		PendingSubmission submission = new PendingSubmission(tile, itemId, itemName, png);
		api.submitProof(
			config.apiKey().trim(),
			tile.tileId,
			itemId,
			png,
			() -> onSubmitted("Submitted " +itemName + " for tile \"" + tile.name + "\""),
			error -> {
				// A transport failure is worth retrying — both immediately on
				// the next matching drop (the 30s dedupe window, not "forever",
				// is what lets a genuine re-drop after a later admin rejection
				// go through) and via the retry queue in case no further drop
				// ever comes. A rejection from the server is neither.
				if ("Could not reach the clan site".equals(error))
				{
					recentAttempts.remove(tile.tileId);
					enqueueRetry(submission);
				}
				notifyPlayer(itemName + " not submitted — " + error);
			});
	}

	private void enqueueRetry(PendingSubmission submission)
	{
		if (retryQueue.size() >= MAX_RETRY_QUEUE)
		{
			PendingSubmission dropped = retryQueue.poll();
			if (dropped != null)
			{
				log.warn("Bingo retry queue full — dropping oldest queued screenshot for tile {}", dropped.tile.tileId);
			}
		}
		retryQueue.add(submission);
	}

	private void retryPendingSubmissions()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			return;
		}

		PendingSubmission submission;
		while ((submission = retryQueue.poll()) != null)
		{
			retrySubmission(apiKey, submission);
		}
	}

	private void retrySubmission(String apiKey, PendingSubmission submission)
	{
		api.submitProof(
			apiKey,
			submission.tile.tileId,
			submission.itemId,
			submission.png,
			() -> onSubmitted("Submitted " +submission.itemName + " for tile \"" + submission.tile.name + "\""),
			error -> {
				if ("Could not reach the clan site".equals(error))
				{
					enqueueRetry(submission);
				}
				else
				{
					notifyPlayer(submission.itemName + " not submitted — " + error);
				}
			});
	}

	/** Common success path for a real proof landing: chat message plus a refresh. */
	private void onSubmitted(String message)
	{
		notifyPlayer(message);
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

	private static class PendingSubmission
	{
		private final BoardResponse.Tile tile;
		private final int itemId;
		private final String itemName;
		private final byte[] png;

		private PendingSubmission(BoardResponse.Tile tile, int itemId, String itemName, byte[] png)
		{
			this.tile = tile;
			this.itemId = itemId;
			this.itemName = itemName;
			this.png = png;
		}
	}
}
