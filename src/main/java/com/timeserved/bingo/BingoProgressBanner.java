package com.timeserved.bingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;

/**
 * A brief on-screen banner celebrating a landed bingo submission — same idea
 * as Anvil's BingoClogBannerOverlay (a plain custom Overlay, not any kind of
 * native-game popup), simplified: no custom art asset or expand/collapse
 * animation, just RuneLite's own PanelComponent/LineComponent (the same
 * building blocks BingoVerificationOverlay already uses) shown for a few
 * seconds then dismissed. A second show() call while one is already
 * displaying just replaces it and restarts the timer rather than queuing —
 * simultaneous submissions are rare enough that queuing would be more
 * complexity than it's worth here.
 */
class BingoProgressBanner extends Overlay
{
	private static final long DISPLAY_MILLIS = 3000;

	private final PanelComponent panelComponent = new PanelComponent();
	private volatile String topLine;
	private volatile String bottomLine;
	private volatile long shownAt;

	@Inject
	private BingoProgressBanner()
	{
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Call from the client thread or the EDT — RuneLite overlays only ever render on the client thread regardless. */
	void show(String topLine, String bottomLine)
	{
		this.topLine = topLine;
		this.bottomLine = bottomLine;
		this.shownAt = System.currentTimeMillis();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String top = topLine;
		if (top == null || System.currentTimeMillis() - shownAt > DISPLAY_MILLIS)
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(LineComponent.builder()
			.left(top)
			.leftColor(Color.ORANGE)
			.build());
		if (bottomLine != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(bottomLine)
				.leftColor(Color.WHITE)
				.build());
		}

		return panelComponent.render(graphics);
	}
}
