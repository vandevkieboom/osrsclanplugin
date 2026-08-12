package com.timeserved.bingo;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.callback.ClientThread;
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
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Time Served Bingo",
	description = "Auto-submits bingo tile proofs to the Time Served clan site when you get a matching drop",
	tags = {"bingo", "clan", "loot", "screenshot", "event"}
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
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BingoPanel panel;

	@Inject
	private BingoGroundItemsOverlay groundItemsOverlay;

	@Inject
	private BingoVerificationOverlay verificationOverlay;

	private NavigationButton navButton;

	/**
	 * Item id -> the tiles it can satisfy, for this player's own team only.
	 * Replaced wholesale on refresh; read from the client thread on every loot
	 * event, so it's a concurrent map rather than a plain one.
	 */
	private final Map<Integer, List<BoardResponse.Tile>> tilesByItemId = new ConcurrentHashMap<>();

	/**
	 * The player's whole team board, kept around (unlike tilesByItemId, which
	 * only keeps the item-lookup slice) so the side panel has names/progress
	 * to render. Replaced wholesale on every refresh.
	 */
	private volatile List<BoardResponse.Tile> myTeamTiles = Collections.emptyList();

	/** Lowercased skill name -> the team-xp tile watching it. */
	private volatile Map<String, BoardResponse.Tile> xpTilesBySkill = Collections.emptyMap();

	/** Lowercased boss name -> the team-kc tile watching it. */
	private volatile Map<String, BoardResponse.Tile> kcTilesByBoss = Collections.emptyMap();

	/** Set by an admin on the clan site; empty until a board with one loads. */
	private volatile String verificationCode = "";

	/** Ground items currently visible whose id satisfies an outstanding tile. */
	private final Map<TileItem, LocalPoint> trackedGroundItems = new ConcurrentHashMap<>();

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
		navButton = NavigationButton.builder()
			.tooltip("Time Served Bingo")
			.icon(buildIcon())
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(groundItemsOverlay);
		overlayManager.add(verificationOverlay);
		refreshBoard();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(groundItemsOverlay);
		overlayManager.remove(verificationOverlay);
		tilesByItemId.clear();
		recentAttempts.clear();
		trackedGroundItems.clear();
		retryQueue.clear();
		myTeamTiles = Collections.emptyList();
		xpTilesBySkill = Collections.emptyMap();
		kcTilesByBoss = Collections.emptyMap();
		verificationCode = "";
	}

	/** Small self-drawn toolbar icon — avoids bundling a binary resource for one glyph. */
	private static BufferedImage buildIcon()
	{
		BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setColor(new Color(232, 87, 74));
		g.fillRoundRect(0, 0, 24, 24, 6, 6);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		g.drawString("B", 8, 17);
		g.dispose();
		return icon;
	}

	String getVerificationCode()
	{
		return verificationCode;
	}

	Map<TileItem, LocalPoint> getTrackedGroundItems()
	{
		return trackedGroundItems;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refreshBoard();
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
	 * Tiles get completed by teammates too, so the local copy goes stale on its
	 * own even when this client sees no drops. Also drains the retry queue —
	 * no need for a separate, faster schedule just for that.
	 */
	@Schedule(period = 5, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledRefresh()
	{
		refreshBoard();
		retryPendingSubmissions();
	}

	private void refreshBoard()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			tilesByItemId.clear();
			recentAttempts.clear();
			myTeamTiles = Collections.emptyList();
			xpTilesBySkill = Collections.emptyMap();
			kcTilesByBoss = Collections.emptyMap();
			verificationCode = "";
			panel.update(Collections.emptyList());
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
				myTeamTiles = tiles;
				xpTilesBySkill = nextXpTiles;
				kcTilesByBoss = nextKcTiles;
				verificationCode = (board.config != null && board.config.verificationCode != null)
					? board.config.verificationCode
					: "";
				panel.update(tiles);
				reportXpProgress(apiKey, nextXpTiles);
				log.debug("Bingo board refreshed: watching {} item ids, {} xp tiles, {} kc tiles",
					nextItemLookup.size(), nextXpTiles.size(), nextKcTiles.size());
			},
			error -> log.debug("Bingo board refresh failed: {}", error));
	}

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
			for (Map.Entry<String, BoardResponse.Tile> entry : xpTiles.entrySet())
			{
				Skill skill = skillFromName(entry.getKey());
				if (skill == null)
				{
					continue;
				}
				long xp = client.getSkillExperience(skill);
				BoardResponse.Tile tile = entry.getValue();
				api.reportProgress(apiKey, "xp", tile.goalKey, xp,
					() -> {},
					error -> log.debug("Failed to report {} xp: {}", skill, error));
			}
		});
	}

	private Skill skillFromName(String name)
	{
		try
		{
			return Skill.valueOf(name.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			log.debug("Unrecognised skill name in an xp tile's goalKey: {}", name);
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
		api.reportProgress(apiKey, "kc", tile.goalKey, kc,
			() -> {},
			error -> log.debug("Failed to report {} kc: {}", tile.goalKey, error));
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (!config.highlightGroundItems() || !tilesByItemId.containsKey(event.getItem().getId()))
		{
			return;
		}
		trackedGroundItems.put(event.getItem(), event.getTile().getLocalLocation());
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		trackedGroundItems.remove(event.getItem());
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
			notifyPlayer("Bingo: could not encode the screenshot for " + itemName);
			return;
		}

		PendingSubmission submission = new PendingSubmission(tile, itemId, itemName, png);
		api.submitProof(
			config.apiKey().trim(),
			tile.tileId,
			itemId,
			png,
			() -> notifyPlayer("Bingo: submitted " + itemName + " for tile \"" + tile.name + "\""),
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
				notifyPlayer("Bingo: " + itemName + " not submitted — " + error);
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
			() -> notifyPlayer("Bingo: submitted " + submission.itemName + " for tile \"" + submission.tile.name + "\""),
			error -> {
				if ("Could not reach the clan site".equals(error))
				{
					enqueueRetry(submission);
				}
				else
				{
					notifyPlayer("Bingo: " + submission.itemName + " not submitted — " + error);
				}
			});
	}

	private void notifyPlayer(String message)
	{
		if (!config.notifyOnSubmit())
		{
			return;
		}
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", message, null));
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
