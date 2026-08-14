package com.timeserved.bingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;

/**
 * Renders the verification code (a plain manually-entered config value, see
 * BingoConfig#verificationCode) plus a live UTC timestamp in a corner of
 * the client. Deliberately never fetched from the site: an earlier version
 * of this had the code broadcast through GET /api/board, which requires no
 * authentication at all, so anyone could have read it — defeating the
 * whole point of it being something only real participants know. An admin
 * decides on a code and shares it directly (e.g. in Discord) instead.
 *
 * <p>Since this is a normal RuneLite overlay, it's part of the rendered
 * frame the same as anything else on screen — captureAndSubmit's
 * drawManager.requestNextFrameListener call picks it up automatically, so
 * every proof screenshot has it baked in with no special-casing needed at
 * capture time.
 */
class BingoVerificationOverlay extends Overlay
{
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final BingoConfig config;
	private final PanelComponent panelComponent = new PanelComponent();

	@Inject
	private BingoVerificationOverlay(BingoConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showVerificationOverlay())
		{
			return null;
		}

		String codeword = config.verificationCode().trim();
		if (codeword.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(LineComponent.builder()
			.left(codeword)
			.leftColor(Color.WHITE)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(TIME_FORMAT.format(Instant.now().atZone(ZoneOffset.UTC)) + " UTC")
			.leftColor(Color.LIGHT_GRAY)
			.build());

		return panelComponent.render(graphics);
	}
}
