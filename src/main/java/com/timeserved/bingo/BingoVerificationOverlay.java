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
 * <p>Only actually draws while {@link #setCaptureMode} is on — same idea as
 * Anvil's version: the point is for it to be baked into the proof
 * screenshot, not for it to sit on the player's screen the whole time
 * they're playing. BingoPlugin#captureAndSubmit flips this on right before
 * calling drawManager.requestNextFrameListener and back off once that frame
 * has been captured, so it's visible for at most the single frame that
 * actually gets saved — imperceptible in normal play, but still part of the
 * rendered frame DrawManager captures, so it's in the screenshot every time.
 */
class BingoVerificationOverlay extends Overlay
{
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final BingoConfig config;
	private final PanelComponent panelComponent = new PanelComponent();
	private volatile boolean captureMode;

	@Inject
	private BingoVerificationOverlay(BingoConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Toggled by BingoPlugin around a single drawManager capture — see the class doc. */
	void setCaptureMode(boolean captureMode)
	{
		this.captureMode = captureMode;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!captureMode || !config.showVerificationOverlay())
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
