package com.timeserved.bingo;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Shows the codeword and a live UTC timestamp on screen, ported directly from Wise Old Man's own
 * overlay ({@code net.wiseoldman.ui.CodeWordOverlay} in
 * {@code wise-old-man/wiseoldman-runelite-plugin}) rather than reimplemented from screenshots.
 * It's a single RuneLite {@link LineComponent} (codeword on the left, timestamp on the right)
 * inside a resizable {@link OverlayPanel} — no custom paint code. The "sticky to the right",
 * "one line when wide, wraps into extra lines when narrow" behaviour isn't something either
 * plugin builds itself: it's {@link LineComponent}'s own built-in wrapping (once the codeword +
 * timestamp no longer both fit on the panel's current dragged width, it word-wraps the right side
 * into as many extra lines as it needs, right-aligning each one, while the left side keeps its own
 * line(s)), and {@link OverlayPanel} already supplies the resize handles, background panel, and
 * border for free.
 *
 * <p>Unlike {@link BingoVerificationOverlay} (which only exists for a single frame to be baked
 * into a proof screenshot), this one sits on screen for the whole session when
 * {@link BingoConfig#showLiveCodewordOverlay} is on.
 */
class BingoCodewordOverlay extends OverlayPanel
{
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'")
		.withZone(ZoneOffset.UTC);

	private final BingoConfig config;

	@Inject
	private BingoCodewordOverlay(BingoConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setPriority(PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showLiveCodewordOverlay())
		{
			return null;
		}

		String codeword = config.verificationCode().trim();
		if (codeword.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(codeword)
			.leftColor(config.codewordColor())
			.right(TIMESTAMP_FORMAT.format(Instant.now()))
			.rightColor(config.timestampColor())
			.build());

		return super.render(graphics);
	}
}
