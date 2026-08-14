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
import java.util.concurrent.ScheduledFuture;
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
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
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
	private BingoVerificationOverlay verificationOverlay;

	@Inject
	private BingoProgressBanner progressBanner;

	@Inject
	private BingoClogTabController clogTabController;

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

	/**
	 * Submissions (drop proofs and kc/xp readings) that failed to send over
	 * the network, waiting to retry. Backed by PendingSubmissionStore, which
	 * mirrors every entry to disk — startUp() reloads whatever survived a
	 * previous session, so a client restart mid-outage no longer silently
	 * loses a real drop with no way to reconstruct it afterwards.
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
	private static final long DEDUPE_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(30);

	/**
	 * Debounce buffer for kc chat-line reports: goalKey -> latest absolute kc
	 * seen. Without this, a kill streak on a tracked boss fires one
	 * report-and-refresh per kill. Since reportProgress only ever cares about
	 * the newest absolute value (the server takes the max, same as the xp
	 * path), buffering and sending the latest value once the streak goes
	 * quiet is a pure win — same end result, far fewer requests.
	 *
	 * <p>Guarded by kcPushLock rather than made a ConcurrentHashMap: a plain
	 * "copy the map, then clear it" drain (which is what flushing needs) is
	 * two separate operations even on a concurrent map, and a put() landing
	 * from the client thread in the gap between those two operations would
	 * be silently wiped by the clear() — a genuinely lost kc update, not
	 * just a delayed one. For a bingo where accuracy decides the winner,
	 * that's not an acceptable trade for a debounce. The lock makes "put a
	 * value" and "drain everything" fully mutually exclusive instead, at
	 * the cost of a lock held only across a map put or a copy-and-clear —
	 * both microseconds, and kc chat lines are human-paced, not a hot loop.
	 */
	private final Object kcPushLock = new Object();
	private final Map<String, Long> pendingKcPush = new HashMap<>();
	private volatile ScheduledFuture<?> kcPushTask;
	private static final long KC_PUSH_COALESCE_MILLIS = TimeUnit.SECONDS.toMillis(15);

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
		overlayManager.add(verificationOverlay);
		overlayManager.add(progressBanner);
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
		overlayManager.remove(groundItemsOverlay);
		overlayManager.remove(verificationOverlay);
		overlayManager.remove(progressBanner);
		chatCommandManager.unregisterCommand(VERIFY_COMMAND);
		chatCommandManager.unregisterCommand(NEEDED_COMMAND);
		chatCommandManager.unregisterCommand(LIVE_COMMAND);
		clientToolbar.removeNavigation(bingoNavButton);
		bingoPanel.dispose();
		ScheduledFuture<?> pendingFlush = kcPushTask;
		if (pendingFlush != null)
		{
			pendingFlush.cancel(false);
		}
		synchronized (kcPushLock)
		{
			pendingKcPush.clear();
		}
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
			// GameState alone isn't quite enough: it can flip to LOGGED_IN a
			// moment before the player/skill data behind it is actually
			// populated (most likely right after a client restart), and
			// getSkillExperience() reads 0 in that gap. Checking the local
			// player exists too closes most of that window.
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
				|| client.getLocalPlayer().getName() == null)
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
				if (xp <= 0)
				{
					// Belt-and-braces on top of the guard above: a 0 (or
					// corrupt negative) reading becomes a PERMANENT baseline
					// server-side if this is the first-ever report for this
					// (player, skill) — recordGoalProgress never touches
					// baseline_value again after it's set. Skipping this one
					// skill this cycle costs nothing (the next refresh tries
					// again); reporting a bad 0 here would wrongly count the
					// player's entire lifetime xp as "progress" forever,
					// until someone notices and resets the whole board.
					continue;
				}
				BoardResponse.Tile tile = entry.getValue();
				// No refreshBoard() on success here, unlike the kc report
				// below — this call is itself made from inside
				// refreshBoard()'s own callback, so that would recurse
				// forever. The next scheduled refresh picks up the new total.
				reportGoalWithRetry(apiKey, "xp", tile.goalKey, xp);
			}
		});
	}

	/**
	 * Reports one absolute kc/xp reading, persisting it to disk for retry if
	 * the request can't reach the site at all. A rejection from the server
	 * itself (as opposed to a transport failure) is never retried — same
	 * "the server is the authority" principle the drop-proof retry already
	 * follows.
	 */
	private void reportGoalWithRetry(String apiKey, String goalKind, String goalKey, long value)
	{
		api.reportProgress(apiKey, goalKind, goalKey, value,
			() -> {},
			error -> {
				log.debug("Failed to report {} {}: {}", goalKind, goalKey, error);
				if ("Could not reach the clan site".equals(error))
				{
					PendingSubmissionStore.PendingItem item = pendingStore.saveGoal(goalKind, goalKey, value);
					if (item != null)
					{
						enqueueRetry(item);
					}
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

		if (config.apiKey().trim().isEmpty())
		{
			return;
		}
		// Buffered rather than sent immediately — see pendingKcPush. A kill
		// streak on the same boss just keeps overwriting this entry with the
		// newest count until the streak goes quiet and scheduleKcPushFlush's
		// task actually fires.
		synchronized (kcPushLock)
		{
			pendingKcPush.put(tile.goalKey, kc);
		}
		scheduleKcPushFlush();
	}

	/**
	 * (Re)schedules the kc-push flush, cancelling any pending one first —
	 * classic debounce: the flush only actually runs once KC_PUSH_COALESCE_MILLIS
	 * passes with no further kc ticks. A rapid kill streak keeps pushing the
	 * schedule back, so it collapses to one flush (and one refresh) for the
	 * whole streak instead of one per kill.
	 */
	private void scheduleKcPushFlush()
	{
		ScheduledFuture<?> existing = kcPushTask;
		if (existing != null)
		{
			existing.cancel(false);
		}
		kcPushTask = executor.schedule(this::flushKcPush, KC_PUSH_COALESCE_MILLIS, TimeUnit.MILLISECONDS);
	}

	/** Sends every buffered kc reading, then refreshes once for the whole batch. */
	private void flushKcPush()
	{
		String apiKey = config.apiKey().trim();

		Map<String, Long> toSend;
		synchronized (kcPushLock)
		{
			if (apiKey.isEmpty() || pendingKcPush.isEmpty())
			{
				pendingKcPush.clear();
				return;
			}
			toSend = new HashMap<>(pendingKcPush);
			pendingKcPush.clear();
		}

		for (Map.Entry<String, Long> entry : toSend.entrySet())
		{
			reportGoalWithRetry(apiKey, "kc", entry.getKey(), entry.getValue());
		}
		// One refresh for the whole flushed batch, not one per boss — mirrors
		// the same "your own action updates instantly" pattern the drop path
		// uses, just coalesced the way the pushes themselves now are.
		refreshBoard();
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
	public void onWidgetLoaded(WidgetLoaded event)
	{
		clogTabController.onWidgetLoaded(event.getGroupId());
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		clogTabController.onWidgetClosed(event.getGroupId());
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST)
		{
			clogTabController.onCollectionDrawList();
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
				// the next matching drop (the 30s dedupe window, not "forever",
				// is what lets a genuine re-drop after a later admin rejection
				// go through) and via the retry queue in case no further drop
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
			if (item.isGoal())
			{
				retryGoalItem(apiKey, item);
			}
			else
			{
				retryProofItem(apiKey, item);
			}
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

	private void retryGoalItem(String apiKey, PendingSubmissionStore.PendingItem item)
	{
		api.reportProgress(apiKey, item.goalKind, item.goalKey, item.goalValue,
			() -> {
				pendingStore.remove(item);
				refreshBoard();
			},
			error -> {
				if ("Could not reach the clan site".equals(error))
				{
					enqueueRetry(item);
				}
				else
				{
					pendingStore.remove(item);
					log.debug("Dropped queued {} report for {}: {}", item.goalKind, item.goalKey, error);
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
		progressBanner.show("Bingo proof submitted", itemName + " — " + tileName);
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
