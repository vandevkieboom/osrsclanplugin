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
 * Shows the codeword (and, if enabled, a live UTC timestamp) on screen, ported directly from Wise
 * Old Man's own overlay ({@code net.wiseoldman.ui.CodeWordOverlay} in
 * {@code wise-old-man/wiseoldman-runelite-plugin}) rather than reimplemented from screenshots.
 * It's a single RuneLite {@link LineComponent} (codeword on the left, timestamp on the right)
 * inside a resizable {@link OverlayPanel} - no custom paint code. The "sticky to the right",
 * "one line when wide, wraps into extra lines when narrow" behaviour isn't something either
 * plugin builds itself: it's {@link LineComponent}'s own built-in wrapping (once the codeword +
 * timestamp no longer both fit on the panel's current dragged width, it word-wraps the right side
 * into as many extra lines as it needs, right-aligning each one, while the left side keeps its own
 * line(s)), and {@link OverlayPanel} already supplies the resize handles, background panel, and
 * border for free.
 *
 * <p>This is the only codeword overlay - there used to be a second one
 * ({@code BingoVerificationOverlay}) that only rendered for the single frame a proof screenshot
 * captured, so the codeword wouldn't sit on screen the whole session. That's been removed: if this
 * overlay happens to be on when a proof screenshot is captured, {@code drawManager} picks it up
 * like any other on-screen overlay, so a second, capture-only overlay was redundant.
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

		LineComponent.LineComponentBuilder line = LineComponent.builder()
			.left(codeword)
			.leftColor(config.codewordColor());
		if (config.showCodewordTimestamp())
		{
			line.right(TIMESTAMP_FORMAT.format(Instant.now()))
				.rightColor(config.timestampColor());
		}
		panelComponent.getChildren().add(line.build());

		return super.render(graphics);
	}
}
