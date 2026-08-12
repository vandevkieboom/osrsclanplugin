package com.timeserved.bingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Small always-on watermark (event codeword + a live UTC timestamp) so a
 * manually-taken screenshot can be tied to the live event. The codeword is a
 * plain local config value — typed in by each player from whatever the event
 * organiser tells them when it starts, not fetched from the clan site (a
 * site-wide codeword was tried and dropped: any authenticated member could
 * read it, not just ones actually on a bingo team, defeating the point of it
 * being a shared secret).
 *
 * <p>Free-floating ({@link OverlayPosition#DYNAMIC} + movable, not snapped to
 * a corner), like any other movable RuneLite overlay: hold Alt and drag to
 * move it. Deliberately not resizable — {@code panelComponent} always shrinks
 * to fit its one line of text on its own, so a manual resize handle would
 * have nothing real to do and just made the edit-mode box look wrong (that's
 * the "stuck square" — it never has an actual size to grab).
 *
 * <p>Always renders as this one-line horizontal bar whenever the "Display
 * codeword" toggle is on, even before a codeword is typed in — otherwise
 * there's nothing to position until it has real content, which is the other
 * half of the same problem.
 */
class BingoVerificationOverlay extends OverlayPanel
{
	private final BingoConfig config;
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm 'UTC'");

	@Inject
	private BingoVerificationOverlay(BingoConfig config)
	{
		this.config = config;
		timeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		// DETACHED is deprecated in favour of this combination — DYNAMIC
		// alone would also mark it non-movable, so setMovable(true) has to
		// come after setPosition(), not before.
		setPosition(OverlayPosition.DYNAMIC);
		setMovable(true);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showVerificationOverlay())
		{
			return null;
		}

		String code = config.codephrase().trim();
		LineComponent.LineComponentBuilder line = LineComponent.builder()
			.left(code.isEmpty() ? "(no codeword set)" : code)
			.leftColor(code.isEmpty() ? Color.GRAY : config.verificationCodeColor());
		if (config.showVerificationTimestamp())
		{
			line.right(timeFormat.format(new Date()))
				.rightColor(config.verificationTimestampColor());
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(line.build());
		return panelComponent.render(graphics);
	}
}
