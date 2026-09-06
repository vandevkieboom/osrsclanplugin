package com.timeserved.bingo;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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

	/** Same visible-reply mechanism as !rank - see setChatReply. */
	private static final String EVENT_COMMAND = "!event";

	/** Guards registerCommands()/unregisterCommands() so either is safe to call more than once in a row. */
	private boolean commandsRegistered;

	/** Sync-reminder fires at most once per plugin session, on whichever outcome comes back first. */
	private boolean checkedRuneProfileSync;

	/**
	 * Whether the site last reported an active bingo event, from the combined
	 * poll (see BingoApiClient#fetchPluginPoll). The expensive board fetch
	 * only ever runs while this is true, so between events the plugin's bingo
	 * half costs nothing at all while its clan half (chat commands,
	 * live-stream and broadcast notifications) keeps working normally.
	 * Defaults true so the plugin behaves normally until it has actually
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
		// Not added here unconditionally - see updateNavVisibility(). Team
		// membership isn't known yet at this point (refreshMyTeam() below is
		// asynchronous), so the icon only appears once that resolves.

		List<PendingSubmissionStore.PendingItem> restored = pendingStore.loadAll();
		if (!restored.isEmpty())
		{
			log.debug("Restored {} pending submission(s) from a previous session", restored.size());
		}
		for (PendingSubmissionStore.PendingItem item : restored)
		{
			enqueueRetry(item);
		}

		// Fills the sidebar panel straight away rather than leaving it empty
		// until the first tick. Everything after this goes through poll().
		refreshMyTeam();
		forceRefreshBoard();
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
		checkedRuneProfileSync = false;
		bingoActive = true;
		lastBoardStamp = null;
		nextPollAllowedAt = 0L;
		lastPollAt = 0L;
		consecutivePollFailures = 0;
		pollIntervalMillis = POLL_INTERVAL_DEFAULT_MILLIS;
		lastBoardFetchAt = 0L;
		lastMyTeamFetchAt = 0L;
		myTeamId = null;
		panelWasVisible = false;
		lastBoard = null;
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
		chatCommandManager.registerCommandAsync(EVENT_COMMAND, this::onEventCommand);
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
		chatCommandManager.unregisterCommand(EVENT_COMMAND);
		commandsRegistered = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Fires on every area/instance load, not just a literal login -
			// during something like raids this can happen many times in quick
			// succession. So this asks for a poll rather than a board fetch:
			// poll() rate-limits itself and only fetches the board if it has
			// actually changed, which makes a burst of instance loads cost
			// nothing while still getting a real logging-in player caught up
			// immediately instead of up to a minute later.
			poll();
			checkRuneProfileSync();
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
			// A new key can mean a different member, and so a different
			// team - re-resolve both rather than wait for a tick.
			refreshMyTeam();
			forceRefreshBoard();
		}
		else if ("showSidebar".equals(event.getKey()))
		{
			updateNavVisibility();
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
	 * The plugin's whole periodic workload: one request, once a minute, only
	 * while actually logged in, and only for members who have a plugin key
	 * set at all (see hasAnythingToPollFor).
	 *
	 * <p>This used to be three requests every minute - bingo status, clan
	 * broadcast, live streams - fired unconditionally for as long as the
	 * client was open, logged in or not, for every install regardless of
	 * whether they had anything to do with bingo. That is roughly 4,300
	 * requests per member per day doing nothing, and across the clan it was
	 * enough to exhaust the site's hosting quotas outright, at which point
	 * the site started failing for everyone. Broadcast and live-stream
	 * notifications were removed entirely rather than just merged, so the
	 * only thing left to poll for is bingo state, and only bingo participants
	 * have any reason to ask for it - between events, or for the several
	 * hundred members who just use the chat commands, this tick makes zero
	 * requests at all.
	 *
	 * <p>Broadcast was briefly reintroduced over Vercel Blob (2026-09-07) on
	 * the reasoning that reading a static file costs no function invocations
	 * and never touches the database - both true - and removed again the same
	 * day once it turned out every blob URL read still bills an Edge Request
	 * whether it hits cache or not. Nothing about moving the *answer* off the
	 * database changes how many times the *question* is asked, and at 100-500
	 * members that question is the entire cost. Don't reintroduce any
	 * unconditional periodic check here without doing that arithmetic first.
	 *
	 * <p>Nothing runs while logged out either: every result this could
	 * deliver is a game chat message or a board the player is looking at
	 * in-game, so polling at the login screen would spend requests on
	 * nothing.
	 *
	 * <p>The tick itself is deliberately still one minute: the point was
	 * never to make a participant wait longer for a board update, it was to
	 * stop the whole clan paying for answers nobody but a participant ever
	 * needed.
	 */
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledRefresh()
	{
		poll();
	}

	/*
	 * checkBoardState() used to run here as a second half of this tick: a
	 * participant-only call to GET /api/board?resource=status asking "has the
	 * board changed?". It was removed because it was a duplicate request for
	 * an answer poll() had already fetched on the same tick - onPolled reads
	 * boardChangedAt from the same board_config row and does the same three
	 * things with it (refresh the board, retry pending submissions, clear the
	 * failure backoff).
	 *
	 * Its own comment claimed it "isn't adding any load beyond what a
	 * participant's poll() already costs", and that was simply wrong: a
	 * separate URL is a separate CDN cache entry, so it was a separate origin
	 * invocation and a separate database read every single time. During an
	 * event it doubled the rate at which participants woke Neon's compute, for
	 * information they already had.
	 */

	private volatile boolean panelWasVisible;

	/** The last board fetched, kept so xp/kc progress arriving on a poll can be
	 *  applied to it without paying for another board fetch. */
	private volatile BoardResponse lastBoard;

	/**
	 * Updates the held board's xp/kc tiles from progress carried on the poll,
	 * and repaints - no board fetch involved.
	 *
	 * <p>This is the replacement for the server bumping boardChangedAt every
	 * time a number moved, which made every participant re-download the whole
	 * board every two minutes for the duration of an event. Item tiles are
	 * untouched: those only change when somebody submits something, which does
	 * still move the board marker.
	 */
	private void applyGoalProgress(Map<String, Map<String, Long>> goalProgress)
	{
		BoardResponse board = lastBoard;
		if (board == null || goalProgress == null || goalProgress.isEmpty())
		{
			return;
		}

		boolean changed = false;
		for (BoardResponse.Team team : board.getTeams())
		{
			for (BoardResponse.Tile tile : team.getTiles())
			{
				if (!tile.isXpGoal() && !tile.isKcGoal())
				{
					continue;
				}
				Map<String, Long> byTeam = goalProgress.get(tile.goalKind + ":" + tile.goalKey);
				if (byTeam == null)
				{
					continue;
				}
				Long value = byTeam.get(team.id);
				if (value != null && !value.equals(tile.teamProgress))
				{
					tile.teamProgress = value;
					changed = true;
				}
			}
		}

		if (changed)
		{
			SwingUtilities.invokeLater(() -> bingoPanel.refresh(board));
		}
	}

	/**
	 * Notices the sidebar panel being opened and fetches a board for it.
	 *
	 * <p>Costs nothing on its own - it is a local Swing check, no network -
	 * but it is what makes it safe for shouldRefreshBoard to skip fetching a
	 * board while nobody is looking at one. Without it, opening the panel
	 * would show a stale board until the next scheduled tick came round.
	 */
	@Schedule(period = 5, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void checkPanelOpened()
	{
		boolean visible = bingoPanel.isShowing();
		boolean opened = visible && !panelWasVisible;
		panelWasVisible = visible;
		if (opened && bingoActive && !config.apiKey().trim().isEmpty())
		{
			// Deliberately not a cache-busting fetch: nothing has *changed*,
			// this reader simply doesn't have a copy yet, so the shared cached
			// one is exactly right. Clearing the marker lets the next poll
			// re-sync it.
			lastBoardStamp = null;
			refreshBoard(null);
		}
	}

	/**
	 * Poll cadence. The scheduler below ticks every minute, but the actual gap
	 * is whatever the site last asked for (PollResponse#pollSeconds), clamped
	 * to this range - the server knows whether an event is on, and can be
	 * retuned without a plugin release, which a hard-coded constant here could
	 * not be. Defaults to one minute until the site has said otherwise.
	 */
	private static final long POLL_INTERVAL_DEFAULT_MILLIS = 60_000L;
	private static final long POLL_INTERVAL_MIN_MILLIS = 60_000L;
	private static final long POLL_INTERVAL_MAX_MILLIS = 15 * 60_000L;

	private volatile long pollIntervalMillis = POLL_INTERVAL_DEFAULT_MILLIS;

	/**
	 * How long to wait after consecutive failures before trying again:
	 * doubles each time, capped well short of "give up".
	 *
	 * <p>Without this, an outage at the site turns every online plugin into a
	 * client retrying once a minute forever, which is exactly the wrong
	 * response - it is maximum load at the moment the site can least afford
	 * it, and it is how a small problem became a total one. Backing off means
	 * a struggling site gets quieter, not louder, and recovers on its own.
	 */
	private static final long BACKOFF_BASE_MILLIS = 60_000L;
	private static final long BACKOFF_MAX_MILLIS = 15 * 60_000L;

	// All three are read and written from OkHttp's callback threads as well as
	// from the scheduler thread, so none of them can be a plain field.
	private volatile long nextPollAllowedAt;
	private volatile long lastPollAt;
	private volatile int consecutivePollFailures;

	/**
	 * Marker for the board this plugin's tile lookup was last built from.
	 * Null means "unknown" - the next poll fetches the board regardless. See
	 * BingoApiClient.PollResponse#boardChangedAt.
	 */
	private volatile String lastBoardStamp;

	/** When the board was last successfully fetched - see shouldRefreshBoard. */
	private volatile long lastBoardFetchAt;

	/**
	 * Which team this plugin key belongs to. No longer part of the board
	 * response (that is one cached copy shared by everybody), so it is fetched
	 * separately and kept here. Null until the first fetch succeeds, or when
	 * the member genuinely is not on a team.
	 */
	private volatile String myTeamId;

	private volatile long lastMyTeamFetchAt;

	/**
	 * Team assignment happens before an event rather than during one, so this
	 * does not need to be prompt - it needs to eventually be right, at
	 * negligible cost.
	 */
	private static final long MY_TEAM_REFRESH_MILLIS = 30 * 60_000L;

	/**
	 * How long the plugin will go without re-fetching the board while the
	 * sidebar panel is closed.
	 *
	 * <p>The board changes constantly during a busy event - every submission,
	 * every hiscores reconcile - and each change would otherwise have every
	 * online plugin pull the whole thing again. But nearly all of what changes
	 * that often is display data: standings, teammates submissions, goal
	 * totals. The only part the plugin needs while nobody is looking is the
	 * item-id watch list that auto-submission checks drops against, and that
	 * only changes when an admin edits tiles, which does not happen mid-event.
	 *
	 * <p>So: panel open, refresh on every change and stay live. Panel closed,
	 * refresh at this interval, which keeps the watch list current without
	 * paying for a board nobody is reading.
	 */
	private static final long BACKGROUND_BOARD_REFRESH_MILLIS = 10 * 60_000L;

	/**
	 * Runs a poll unless one ran too recently or a backoff is in effect.
	 *
	 * <p>Callable from anything, including things that fire often: the
	 * interval check below is the only gate, and it is deliberately the same
	 * one for a scheduled tick and for a login. An earlier version gave
	 * login-triggered polls a shorter floor so someone logging in would be
	 * caught up immediately, which was both unnecessary and expensive -
	 * GameState.LOGGED_IN fires on every area and instance load, not just a
	 * real login, so during a raid that shorter floor would have roughly
	 * doubled the request rate for exactly the players generating the most of
	 * it. It was also pointless: a player who has actually been logged out is
	 * one who has not been polling, so their last poll is already older than
	 * the interval and they get their immediate catch-up from this check
	 * anyway. An instance load mid-session gets nothing, which is correct -
	 * they were polled a moment ago.
	 */
	private void poll()
	{
		// Nothing this fetches can reach the player while they're logged out -
		// it all arrives as game chat or an in-game board - so polling at the
		// login screen spends requests on notifications with nowhere to go.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (!hasAnythingToPollFor())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now < nextPollAllowedAt)
		{
			return;
		}
		// A second of slack: the scheduler doesn't fire on exact 60s
		// boundaries, and without this a 60s cadence checked against a 60s
		// interval would skip every other tick and silently run at two
		// minutes.
		if (now - lastPollAt < pollIntervalMillis - 1_000L)
		{
			return;
		}
		lastPollAt = now;

		api.fetchPluginPoll(this::onPolled, this::onPollFailed);
	}

	/**
	 * Whether this install has any reason to be talking to the site on a timer.
	 *
	 * <p>Live-stream notifications and admin broadcasts are gone - the periodic
	 * poll now exists purely to deliver bingo state to actual participants. A
	 * plugin key is what a bingo participant has, so it is the only reason left
	 * to poll at all: nobody without one has anything for this tick to tell
	 * them, so between events (or for the several hundred members who just use
	 * the chat commands) this install makes zero background requests. The chat
	 * commands are unaffected either way: they are sent when typed, and never
	 * poll.
	 */
	private boolean hasAnythingToPollFor()
	{
		return !config.apiKey().trim().isEmpty();
	}

	private void onPolled(BingoApiClient.PollResponse result)
	{
		consecutivePollFailures = 0;
		nextPollAllowedAt = 0L;

		// Applied before the degraded check below, deliberately: a site that is
		// having trouble asks for a slower cadence, and that request is the one
		// thing worth honouring from a degraded response - it's the whole point
		// of the field. Everything else in one is last-known rather than
		// current.
		applyPollCadence(result);

		// The site couldn't reach its own database and answered from a cached
		// copy. Acting on that value could mean deciding an event has ended
		// when it hasn't. Sitting this one out costs at most one poll interval.
		if (result.degraded)
		{
			log.debug("Clan site answered in degraded mode; skipping this tick");
			return;
		}

		boolean wasActive = bingoActive;
		bingoActive = result.bingoActive;

		// An event just started. Team membership is only ever checked while
		// bingo is active (see maybeRefreshMyTeam), so at this exact moment
		// every online client is holding whatever it last knew - which for the
		// normal run-up to an event ("build the teams, then flip the switch")
		// is "no team", and the bingo nav icon is hidden on that basis. Without
		// this the icon would not appear until the throttle happened to lapse,
		// leaving members who *are* on a team unable to see the board and no
		// obvious fix but restarting the client. Clearing the marker makes the
		// very next poll re-ask, so flipping the switch reaches everyone within
		// about a minute, which is what the 30-minutes-before-start routine
		// assumes. Costs one request per client per event.
		if (bingoActive && !wasActive)
		{
			lastMyTeamFetchAt = 0L;
		}

		// The nav icon now tracks whether an event is running, not just team
		// membership. Between events a leftover team assignment (rosters are
		// not cleared by a board reset) otherwise left the board on screen
		// indefinitely for anyone who took part in the previous bingo - and
		// left it there even after an admin removed them from a team, since
		// membership is only re-checked while an event is active.
		if (bingoActive != wasActive)
		{
			updateNavVisibility();
		}

		if (bingoActive)
		{
			// Re-check team membership whenever the board marker moves rather
			// than only on a timer: roster edits bump it (there is a trigger on
			// the users table for exactly this), so a member added to or removed
			// from a team is picked up on the next poll instead of up to half an
			// hour later - and, between marker changes, not asked for at all.
			// This endpoint is per-member and so cannot be cached; every call is
			// a real database read, which is why "only when something actually
			// changed" matters more here than anywhere else.
			if (!Objects.equals(result.boardChangedAt, lastBoardStamp))
			{
				lastMyTeamFetchAt = 0L;
			}
			maybeRefreshMyTeam();
			if (shouldRefreshBoard(result.boardChangedAt))
			{
				refreshBoard(result.boardChangedAt);
			}
			// After any board refresh above, so a fetched board isn't
			// immediately overwritten with older numbers.
			applyGoalProgress(result.goalProgress);
			retryPendingSubmissions();
		}
	}

	/**
	 * Whether this tick should pull the board again.
	 *
	 * <p>Two gates, and both matter. Nothing changed since the copy we hold
	 * means there is nothing to fetch at all. And if something did change but
	 * the panel is closed, what changed is display data nobody is looking at,
	 * so it can wait for the slow background refresh that keeps the item-id
	 * watch list current.
	 *
	 * <p>Auto-submission is unaffected either way: it fires off a loot event,
	 * not off a poll, and the watch list it uses only changes when tiles are
	 * edited. Opening the panel gets a fresh board on the next tick.
	 */
	private boolean shouldRefreshBoard(String stamp)
	{
		if (lastBoardStamp != null && stamp != null && stamp.equals(lastBoardStamp))
		{
			return false;
		}
		// Somebody is looking at it, so it needs to be right.
		if (isPanelVisible())
		{
			return true;
		}
		// Not on a team and not looking: there is nothing this board could be
		// used for. The only reason to hold one with the panel closed is the
		// item-id watch list that auto-submission checks drops against, and a
		// member who isn't on a team cannot submit anything anyway - the server
		// refuses it. This is the common case by a wide margin: most of the
		// clan runs this plugin for the chat commands and the live-stream
		// notices and is never in a bingo team at all.
		if (myTeamId == null)
		{
			return false;
		}
		return lastBoardStamp == null
			|| System.currentTimeMillis() - lastBoardFetchAt >= BACKGROUND_BOARD_REFRESH_MILLIS;
	}

	/**
	 * Whether the bingo sidebar panel is actually on screen.
	 *
	 * <p>RuneLite removes a plugin panel from the sidebar container when a
	 * different one is selected, so Swing's own isShowing() answers this
	 * without depending on a RuneLite API that could move between versions.
	 */
	private boolean isPanelVisible()
	{
		return bingoPanel.isShowing();
	}

	/**
	 * Throttled purely on elapsed time, and that is the whole point of it.
	 *
	 * <p>An earlier version skipped only when a team id was already known,
	 * which meant everybody who is *not* in the bingo - most of the clan, most
	 * of the year - re-asked on every single poll and got the same "no team"
	 * answer forever. Exactly the wrong people paying exactly the most.
	 */
	private void maybeRefreshMyTeam()
	{
		if (lastMyTeamFetchAt != 0L
			&& System.currentTimeMillis() - lastMyTeamFetchAt < MY_TEAM_REFRESH_MILLIS)
		{
			return;
		}
		refreshMyTeam();
	}

	private void refreshMyTeam()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			myTeamId = null;
			updateNavVisibility();
			return;
		}
		lastMyTeamFetchAt = System.currentTimeMillis();
		api.fetchMyTeam(
			apiKey,
			result -> {
				String previous = myTeamId;
				myTeamId = result.teamId;
				updateNavVisibility();
				// Someone just put on a team, or moved to another one, is
				// looking at the wrong half of the board until it is
				// re-rendered - and the change marker cannot help, because the
				// board itself did not change, only which part of it is theirs.
				boolean changed = previous == null
					? result.teamId != null
					: !previous.equals(result.teamId);
				if (changed)
				{
					forceRefreshBoard();
					// Joining a team mid-event also changes which cadence
					// applies, and that was decided on the poll this reply came
					// from - before the answer existed. Clearing the last-poll
					// time lets the next scheduled tick re-derive it, rather
					// than leaving a new participant on the non-participant
					// cadence until a whole slow interval has elapsed.
					lastPollAt = 0L;
				}
			},
			error -> log.debug("Failed to fetch team membership: {}", error));
	}

	/**
	 * Shows the bingo nav icon only for members who are actually on a team.
	 * Someone with a plugin key but no team could still open the panel and
	 * see the clan-wide standings, which cost a real board fetch for
	 * information that's already fully public on the website - not worth it
	 * for someone who isn't playing. Called whenever team membership or the
	 * "Show bingo board" toggle change.
	 */
	private void updateNavVisibility()
	{
		if (config.showSidebar() && myTeamId != null && bingoActive)
		{
			clientToolbar.addNavigation(bingoNavButton);
		}
		else
		{
			clientToolbar.removeNavigation(bingoNavButton);
		}
	}

	/**
	 * Adopts the cadence the site asks for - fast while an event is running,
	 * slow otherwise. The server decides rather than the plugin, so it can be
	 * retuned mid-month without waiting for installs to update.
	 */
	private void applyPollCadence(BingoApiClient.PollResponse result)
	{
		if (result.pollSeconds > 0)
		{
			pollIntervalMillis = Math.min(
				POLL_INTERVAL_MAX_MILLIS,
				Math.max(POLL_INTERVAL_MIN_MILLIS, result.pollSeconds * 1000L));
		}
	}

	private void onPollFailed(String error)
	{
		consecutivePollFailures++;
		long delay = Math.min(
			BACKOFF_MAX_MILLIS,
			BACKOFF_BASE_MILLIS * (1L << Math.min(consecutivePollFailures - 1, 8)));
		nextPollAllowedAt = System.currentTimeMillis() + delay;
		log.debug("Clan site poll failed ({}); backing off {}ms", error, delay);
	}

	/**
	 * Fetches the board and rebuilds the item-id lookup that handleLoot()
	 * checks drops against. Team-combined xp/kc tiles need no plugin-side
	 * reporting at all - their progress comes entirely from the website's
	 * own hiscores polling (see osrsclan/api/_lib/board.ts), so this only
	 * ever has to watch for item drops.
	 *
	 * <p>This is the expensive call - it makes the site query tiles, teams,
	 * rosters and every submission, and send back the lot. Almost all of the
	 * time it should be reached through {@link #poll()}, which only calls it
	 * when the board has actually changed. {@link #forceRefreshBoard()} is
	 * for the handful of moments where waiting for that is wrong.
	 *
	 * @param stamp the board marker this fetch corresponds to, recorded on
	 *              success so later polls can tell whether anything has moved
	 *              since. Null means "unknown", which makes the next poll
	 *              fetch again rather than risk holding a stale board.
	 */
	private void refreshBoard(String stamp)
	{
		refreshBoard(stamp, false);
	}

	private void refreshBoard(String stamp, boolean fresh)
	{
		if (config.apiKey().trim().isEmpty())
		{
			tilesByItemId.clear();
			recentAttempts.clear();
			lastBoardStamp = null;
			SwingUtilities.invokeLater(bingoPanel::showNoApiKey);
			return;
		}

		api.fetchBoard(
			fresh,
			board -> {
				// The stamp the board actually came back with, not the one we
				// asked on the strength of: a cached copy can predate the
				// change that prompted this fetch, and recording the newer
				// stamp for it would leave the panel stuck a change behind
				// with nothing left to trigger a correction. Recording what
				// arrived means it simply doesn't match the next poll either,
				// and gets re-fetched.
				lastBoardStamp = board.boardChangedAt != null ? board.boardChangedAt : stamp;
				lastBoardFetchAt = System.currentTimeMillis();
				// Held so a poll can refresh xp/kc numbers on it without
				// re-fetching the whole thing - see applyGoalProgress.
				lastBoard = board;
				// The board is a single cached copy shared by everyone, so it
				// says nothing about who is asking. The team id comes from
				// fetchMyTeam and is stitched in here, so findMyTeam() and the
				// panel keep working exactly as before.
				board.myTeamId = myTeamId;
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
			error -> {
				// Leave the marker unknown so the next poll tries again
				// rather than concluding the board is already up to date.
				lastBoardStamp = null;
				log.debug("Bingo board refresh failed: {}", error);
			});
	}

	/**
	 * Fetches the board right now, whatever the change marker says.
	 *
	 * <p>For the few moments where the marker can't answer the question:
	 * plugin startup and an API key change (no board held at all yet), and
	 * straight after this player's own submission (they should see their own
	 * drop land immediately, not up to a minute later). Clearing the marker
	 * means the following poll re-syncs it.
	 */
	private void forceRefreshBoard()
	{
		lastBoardStamp = null;
		// fresh: these are all cases where the caller knows something changed
		// that a cached copy may predate - most importantly this player's own
		// submission, which they should see land immediately.
		refreshBoard(null, true);
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

	/**
	 * Longest edge a proof screenshot is stored at. Only ever shrinks an
	 * oversized frame - most clients are already at or under this, and a
	 * proof nobody can read is worthless, so this is set well above "big
	 * enough to read a chat line" rather than as tight as it could be.
	 */
	private static final int MAX_PROOF_EDGE_PX = 1920;

	/**
	 * JPEG quality for proof screenshots. High enough that the artefacts are
	 * invisible at a glance on a game frame; the point is not to be small, it
	 * is to not be PNG.
	 */
	private static final float PROOF_JPEG_QUALITY = 0.85f;

	/**
	 * Encodes the captured frame and sends it as proof.
	 *
	 * <p>These were lossless PNGs of a full game frame, which is close to the
	 * worst case for PNG: it compresses flat colour well and detailed,
	 * dithered, noisy 3D output badly, so real proofs were landing around
	 * 800KB-1MB each. That is charged twice over - once against the site's
	 * blob storage quota, which every proof occupies until the board is
	 * reset, and again against its transfer quota every single time somebody
	 * opens a tile to look at the screenshots. JPEG at high quality is
	 * roughly three to four times smaller on this kind of image with no
	 * meaningful loss of legibility, which is the only thing a proof has to
	 * be.
	 */
	private void encodeAndUpload(BoardResponse.Tile tile, int itemId, String itemName, BufferedImage frame)
	{
		byte[] image;
		try
		{
			image = encodeProof(frame);
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
			image,
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
					PendingSubmissionStore.PendingItem item = pendingStore.saveProof(tile.tileId, tile.name, itemId, itemName, image);
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

	/**
	 * Scales the frame down if it is larger than MAX_PROOF_EDGE_PX and encodes
	 * it as JPEG.
	 *
	 * <p>The intermediate is TYPE_INT_RGB rather than whatever the client
	 * handed over: JPEG has no alpha channel, and writing an image that has
	 * one produces either a failure or colour-inverted output depending on the
	 * JDK, rather than anything useful.
	 */
	private static byte[] encodeProof(BufferedImage frame) throws IOException
	{
		int w = frame.getWidth();
		int h = frame.getHeight();
		double scale = Math.min(1.0, (double) MAX_PROOF_EDGE_PX / Math.max(w, h));
		int outW = Math.max(1, (int) Math.round(w * scale));
		int outH = Math.max(1, (int) Math.round(h * scale));

		BufferedImage rgb = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(frame, 0, 0, outW, outH, null);
		g.dispose();

		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext())
		{
			// No JPEG writer on this JVM (shouldn't happen on a standard JDK).
			// A larger PNG is much better than no proof at all.
			ByteArrayOutputStream fallback = new ByteArrayOutputStream();
			ImageIO.write(rgb, "png", fallback);
			return fallback.toByteArray();
		}

		ImageWriter writer = writers.next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out))
		{
			writer.setOutput(stream);
			ImageWriteParam params = writer.getDefaultWriteParam();
			if (params.canWriteCompressed())
			{
				params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				params.setCompressionQuality(PROOF_JPEG_QUALITY);
			}
			writer.write(null, new IIOImage(rgb, null, null), params);
		}
		finally
		{
			writer.dispose();
		}
		return out.toByteArray();
	}

	/** Common success path for a real proof landing: chat message plus a board refresh. */
	private void onSubmitted(String itemName, String tileName)
	{
		notifyPlayer("Submitted " + itemName + " for tile \"" + tileName + "\"");
		// Straight away, not on the next tick: a player who just got a drop
		// should see it on their own board immediately.
		forceRefreshBoard();
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

	/**
	 * Handles "!event [name]". Unlike !rank/!needed, no name defaults to
	 * showing overall standings rather than the sender's own progress -
	 * "!event" alone asking about yourself would be a strange default for a
	 * clan-wide leaderboard command. Needs no plugin key: this is public WOM
	 * competition data, same reasoning as !live.
	 */
	private void onEventCommand(ChatMessage chatMessage, String message)
	{
		String typed = message.length() > EVENT_COMMAND.length()
			? message.substring(EVENT_COMMAND.length()).trim()
			: "";
		String targetName = typed.isEmpty() ? null : Text.sanitize(typed);

		api.fetchEventSummary(
			result -> setChatReply(chatMessage, formatEventResult(result, targetName)),
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

	/** Top standings shown per competition before truncating - keeps a reply
	 *  with several ongoing competitions from flooding chat. */
	private static final int EVENT_TOP_N = 5;

	private String formatEventResult(BingoApiClient.EventSummaryResponse result, String targetName)
	{
		if ("none".equals(result.status) || result.competitions.isEmpty())
		{
			return "No BOTW/SOTW running right now.";
		}

		boolean upcoming = "upcoming".equals(result.status);
		List<String> parts = new ArrayList<>();
		boolean foundTarget = false;

		for (BingoApiClient.EventCompetition comp : result.competitions)
		{
			String label = "xp".equals(comp.metricType) ? "SOTW" : "BOTW";
			String metric = humanizeMetric(comp.metric);

			if (upcoming)
			{
				parts.add(label + ": " + metric + " starts in " + formatCountdown(comp.startsAt));
				continue;
			}

			if (targetName != null)
			{
				String found = formatPersonalProgress(comp, label, metric, targetName);
				if (found != null)
				{
					parts.add(found);
					foundTarget = true;
				}
				continue;
			}

			parts.add(label + ": " + metric + " (ends in " + formatCountdown(comp.endsAt) + ") - "
				+ formatStandings(comp));
		}

		if (targetName != null && !foundTarget)
		{
			return targetName + " isn't competing in the current event" + (result.competitions.size() > 1 ? "s" : "") + ".";
		}

		return String.join("  |  ", parts);
	}

	/** Null when this player isn't in this specific competition - the caller
	 *  tries the next one before giving up. */
	private String formatPersonalProgress(BingoApiClient.EventCompetition comp, String label, String metric, String targetName)
	{
		List<BingoApiClient.EventParticipation> ranked = rankedParticipants(comp);
		for (int i = 0; i < ranked.size(); i++)
		{
			BingoApiClient.EventParticipation p = ranked.get(i);
			if (p.player != null && targetName.equalsIgnoreCase(p.player.displayName))
			{
				return targetName + ": " + formatNumber(p.progress.gained) + " " + comp.metricType
					+ " gained in " + metric + " " + label + " (rank " + (i + 1) + " of " + ranked.size() + ")";
			}
		}
		return null;
	}

	private String formatStandings(BingoApiClient.EventCompetition comp)
	{
		List<BingoApiClient.EventParticipation> ranked = rankedParticipants(comp);
		int shown = Math.min(EVENT_TOP_N, ranked.size());
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < shown; i++)
		{
			if (i > 0)
			{
				text.append("  ");
			}
			BingoApiClient.EventParticipation p = ranked.get(i);
			String name = p.player != null ? p.player.displayName : "?";
			text.append(i + 1).append(". ").append(name).append(" ")
				.append(formatNumber(p.progress.gained)).append(" ").append(comp.metricType);
		}
		return text.toString();
	}

	private List<BingoApiClient.EventParticipation> rankedParticipants(BingoApiClient.EventCompetition comp)
	{
		List<BingoApiClient.EventParticipation> ranked = new ArrayList<>(
			comp.participations == null ? Collections.emptyList() : comp.participations);
		ranked.sort((a, b) -> Long.compare(b.progress.gained, a.progress.gained));
		return ranked;
	}

	private String formatNumber(long value)
	{
		return String.format(Locale.ROOT, "%,d", value);
	}

	/** "kril_tsutsaroth" -> "Kril Tsutsaroth". Good enough for a chat message;
	 *  not attempting proper capitalisation of small words like "of". */
	private String humanizeMetric(String metric)
	{
		if (metric == null || metric.isEmpty())
		{
			return "?";
		}
		String[] words = metric.replace('_', ' ').split(" ");
		StringBuilder text = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (text.length() > 0)
			{
				text.append(' ');
			}
			text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return text.toString();
	}

	/** "3d 4h" / "6h 12m" / "less than a minute" - deliberately coarse, this
	 *  is a rough sense of time left, not a countdown timer. */
	private String formatCountdown(String isoInstant)
	{
		if (isoInstant == null)
		{
			return "an unknown time";
		}
		try
		{
			Instant target = Instant.parse(isoInstant);
			Duration remaining = Duration.between(Instant.now(), target);
			boolean past = remaining.isNegative();
			Duration abs = remaining.abs();

			long days = abs.toDays();
			long hours = abs.toHours() % 24;
			long minutes = abs.toMinutes() % 60;

			String amount;
			if (days > 0)
			{
				amount = days + "d " + hours + "h";
			}
			else if (hours > 0)
			{
				amount = hours + "h " + minutes + "m";
			}
			else if (minutes > 0)
			{
				amount = minutes + "m";
			}
			else
			{
				amount = "under a minute";
			}
			return past ? amount + " ago" : amount;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not parse event timestamp {}", isoInstant, e);
			return "an unknown time";
		}
	}

	/**
	 * Backs the "Remind me to sync RuneProfile" toggle. Only ever detects
	 * "never set up on RuneProfile at all" (a 404 from the site, see
	 * BingoApiClient#lookupRank's reason field) - there's no confirmed way
	 * to tell a stale-but-present sync from a fresh one, so that case isn't
	 * covered. Runs once per session, right after login.
	 */
	private static final String RP_SYNC_CONFIRMED_KEY = "runeProfileSyncConfirmed";
	private static final String RP_SYNC_CHECKED_AT_KEY = "runeProfileSyncCheckedAt";
	private static final long RP_SYNC_RECHECK_MILLIS = 24 * 60 * 60_000L;

	/**
	 * Reminds you once, if you have never set RuneProfile up.
	 *
	 * <p>This fired on every login, for every install, and the endpoint it
	 * calls is the most expensive one on the site - a clan roster lookup plus
	 * three upstream profile fetches - all to answer a question whose answer
	 * almost never changes and, once it is yes, never changes again. With a few
	 * hundred installs that is hundreds of the site's heaviest requests a day
	 * to tell nobody anything.
	 *
	 * <p>So the answer is remembered. Confirmed synced, and it never asks
	 * again. Not synced, and it asks at most once a day rather than once a
	 * session, which is if anything a better reminder - once per session
	 * punishes people who hop worlds or crash.
	 */
	private void checkRuneProfileSync()
	{
		if (!config.remindRuneProfileSync() || checkedRuneProfileSync)
		{
			return;
		}
		if ("true".equals(configManager.getConfiguration(BingoConfig.GROUP, RP_SYNC_CONFIRMED_KEY)))
		{
			checkedRuneProfileSync = true;
			return;
		}
		Long lastCheck = configManager.getConfiguration(
			BingoConfig.GROUP, RP_SYNC_CHECKED_AT_KEY, Long.class);
		if (lastCheck != null && System.currentTimeMillis() - lastCheck < RP_SYNC_RECHECK_MILLIS)
		{
			checkedRuneProfileSync = true;
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}

		checkedRuneProfileSync = true;
		configManager.setConfiguration(
			BingoConfig.GROUP, RP_SYNC_CHECKED_AT_KEY, System.currentTimeMillis());
		api.lookupRank(local.getName(),
			result -> configManager.setConfiguration(
				BingoConfig.GROUP, RP_SYNC_CONFIRMED_KEY, "true"),
			(error, reason) -> {
				if ("not-on-runeprofile".equals(reason))
				{
					sendChatMessage("You haven't synced RuneProfile yet - install it and open your"
						+ " collection log so rank checks can see your progress.", config.clanMessageColor());
				}
			});
	}

}
