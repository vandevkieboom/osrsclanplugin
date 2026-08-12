package com.timeserved.bingo;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Labels ground items that still satisfy one of the player's team's tiles
 * with the tile's name, so a beam is identifiable at a glance instead of
 * just "something is highlighted here". The beam itself is a real 3D model
 * (see {@link BingoLootbeam}, managed directly by {@link BingoPlugin} on
 * item spawn/despawn) — this overlay only draws the text above it.
 */
class BingoGroundItemsOverlay extends Overlay
{
	private final Client client;
	private final BingoPlugin plugin;
	private final BingoConfig config;

	@Inject
	private BingoGroundItemsOverlay(Client client, BingoPlugin plugin, BingoConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightGroundItems())
		{
			return null;
		}

		for (BingoPlugin.TrackedGroundItem tracked : plugin.getTrackedGroundItems().values())
		{
			Point textLocation = Perspective.getCanvasTextLocation(client, graphics, tracked.location, tracked.tileName, 40);
			if (textLocation != null)
			{
				OverlayUtil.renderTextLocation(graphics, textLocation, tracked.tileName, config.groundItemHighlightColor());
			}
		}
		return null;
	}
}
