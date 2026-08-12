package com.timeserved.bingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Outlines ground items whose id still satisfies one of the player's team's
 * tiles — reuses {@link BingoPlugin}'s existing item-id-to-tile lookup, no
 * new data from the site needed.
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

		Color color = config.groundItemHighlightColor();
		for (Map.Entry<TileItem, LocalPoint> entry : plugin.getTrackedGroundItems().entrySet())
		{
			Shape poly = Perspective.getCanvasTilePoly(client, entry.getValue());
			if (poly != null)
			{
				OverlayUtil.renderPolygon(graphics, poly, color);
			}
		}
		return null;
	}
}
