package com.timeserved.bingo;

import com.google.inject.Provides;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
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
import net.runelite.client.ui.DrawManager;
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

	/**
	 * Item id -> the tiles it can satisfy, for this player's own team only.
	 * Replaced wholesale on refresh; read from the client thread on every loot
	 * event, so it's a concurrent map rather than a plain one.
	 */
	private final Map<Integer, List<BoardResponse.Tile>> tilesByItemId = new ConcurrentHashMap<>();

	/**
	 * Tiles already submitted (or refused) this cycle, so a second matching drop
	 * doesn't fire a duplicate upload before the board is refreshed. Cleared on
	 * every successful refresh, at which point the server's own approved/pending
	 * counts are authoritative again.
	 */
	private final Set<String> claimedTiles = ConcurrentHashMap.newKeySet();

	@Provides
	BingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BingoConfig.class);
	}

	@Override
	protected void startUp()
	{
		refreshBoard();
	}

	@Override
	protected void shutDown()
	{
		tilesByItemId.clear();
		claimedTiles.clear();
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
	 * own even when this client sees no drops.
	 */
	@Schedule(period = 5, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledRefresh()
	{
		refreshBoard();
	}

	private void refreshBoard()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			tilesByItemId.clear();
			claimedTiles.clear();
			return;
		}

		api.fetchBoard(
			apiKey,
			board -> {
				BoardResponse.Team myTeam = board.findMyTeam();
				Map<Integer, List<BoardResponse.Tile>> next = new HashMap<>();
				if (myTeam != null)
				{
					for (BoardResponse.Tile tile : myTeam.getTiles())
					{
						for (Integer itemId : tile.getItemIds())
						{
							next.computeIfAbsent(itemId, id -> new ArrayList<>()).add(tile);
						}
					}
				}

				tilesByItemId.clear();
				tilesByItemId.putAll(next);
				claimedTiles.clear();
				log.debug("Bingo board refreshed: watching {} item ids", next.size());
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
				if (!tile.needsMoreProof() || !claimedTiles.add(tile.tileId))
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
			claimedTiles.remove(tile.tileId);
			notifyPlayer("Bingo: could not encode the screenshot for " + itemName);
			return;
		}

		api.submitProof(
			config.apiKey().trim(),
			tile.tileId,
			itemId,
			png,
			() -> notifyPlayer("Bingo: submitted " + itemName + " for tile \"" + tile.name + "\""),
			error -> {
				// A transport failure is worth retrying on the next matching
				// drop; a rejection from the server is not.
				if ("Could not reach the clan site".equals(error))
				{
					claimedTiles.remove(tile.tileId);
				}
				notifyPlayer("Bingo: " + itemName + " not submitted — " + error);
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
}
