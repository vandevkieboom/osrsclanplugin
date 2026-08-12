package com.timeserved.bingo;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Small always-on watermark (event codephrase + a live timestamp) so a
 * manually-taken screenshot can be tied to the live event — the codephrase
 * itself comes from the board's config and is set by a clan admin.
 */
class BingoVerificationOverlay extends OverlayPanel
{
	private final BingoPlugin plugin;
	private final BingoConfig config;
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	@Inject
	private BingoVerificationOverlay(BingoPlugin plugin, BingoConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String code = plugin.getVerificationCode();
		if (!config.showVerificationOverlay() || code == null || code.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Bingo verify:")
			.right(code)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(timeFormat.format(new Date()))
			.build());
		return panelComponent.render(graphics);
	}
}
