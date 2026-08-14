package com.timeserved.bingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Collection-log-style "unlock" banner for a landed bingo submission — a
 * direct adaptation of AhmedFathy2001/anvil-plugin's
 * BingoClogBannerOverlay (BSD 2-Clause License, Copyright (c) 2026
 * AhmedFathy2001), including its background asset (clog_banner.png, copied
 * as-is — see that repo for provenance) and its open/hold/close animation
 * timeline. The first version of this used RuneLite's generic
 * PanelComponent box, which isn't the OSRS/collection-log look this is
 * supposed to have — this replaces it with the real thing instead of an
 * approximation.
 *
 * <p>178x100 base panel (the source PNG is 3x that). Three centred lines at
 * text-tops y=11/36/52, matching the reference implementation's tuning for
 * even vertical rhythm on the small panel.
 */
class BingoProgressBanner extends Overlay
{
	private static final int BASE_W = 178;
	private static final int BASE_H = 100;

	private static final float TOP_Y = 11f;
	private static final float MID_Y = 36f;
	private static final float BOTTOM_Y = 52f;
	private static final float FONT_PX = 16f;
	private static final float WRAP_LINE_H = 15f;
	private static final int BOTTOM_MAX_LINES = 2;
	private static final int TEXT_MARGIN_X = 12;

	private static final long H_EXPAND_MS = 800;
	private static final long V_EXPAND_MS = 800;
	private static final long HOLD_MS = 2500;
	private static final long V_COLLAPSE_MS = 650;
	private static final long H_COLLAPSE_MS = 650;
	private static final float LINE_H = 1f;

	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color WHITE = new Color(255, 255, 255);
	private static final Color SHADOW = new Color(0, 0, 0, 255);
	private static final Color LINE_COLOR = Color.BLACK;

	private static final class Banner
	{
		final String top;
		final String middle;
		final String bottom;

		Banner(String top, String middle, String bottom)
		{
			this.top = top;
			this.middle = middle;
			this.bottom = bottom;
		}
	}

	private final ConcurrentLinkedQueue<Banner> queue = new ConcurrentLinkedQueue<>();
	private final BufferedImage background;
	private final Font headerFont;
	private final Font bodyFont;
	private Banner active;
	private long startedAt;

	@Inject
	private BingoProgressBanner()
	{
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGHEST);
		this.background = loadBackground();
		this.headerFont = FontManager.getRunescapeBoldFont().deriveFont(FONT_PX);
		this.bodyFont = FontManager.getRunescapeSmallFont();
	}

	private static BufferedImage loadBackground()
	{
		try (InputStream in = BingoProgressBanner.class.getResourceAsStream("/com/timeserved/bingo/clog_banner.png"))
		{
			return in == null ? null : ImageIO.read(in);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/** Queues a submission banner: "Bingo", "Submitted!", "<item> — <tile>". */
	void show(String itemName, String tileName)
	{
		queue.add(new Banner("Bingo", "Submitted!", itemName + " — " + tileName));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (background == null)
		{
			return null;
		}

		long now = System.currentTimeMillis();
		long hEnd = H_EXPAND_MS;
		long vEnd = hEnd + V_EXPAND_MS;
		long holdEnd = vEnd + HOLD_MS;
		long vCollapseEnd = holdEnd + V_COLLAPSE_MS;
		long total = vCollapseEnd + H_COLLAPSE_MS;

		if (active == null || now - startedAt >= total)
		{
			active = queue.poll();
			if (active == null)
			{
				return null;
			}
			startedAt = now;
			now = System.currentTimeMillis();
		}

		long e = now - startedAt;

		int fullW = BASE_W;
		int fullH = BASE_H;
		int lineH = Math.max(1, Math.round(LINE_H));

		float w;
		float h;
		boolean line;
		if (e < hEnd)
		{
			w = fullW * (e / (float) H_EXPAND_MS);
			h = lineH;
			line = true;
		}
		else if (e < vEnd)
		{
			float t = (e - hEnd) / (float) V_EXPAND_MS;
			w = fullW;
			h = lineH + (fullH - lineH) * t;
			line = false;
		}
		else if (e < holdEnd)
		{
			w = fullW;
			h = fullH;
			line = false;
		}
		else if (e < vCollapseEnd)
		{
			float t = (e - holdEnd) / (float) V_COLLAPSE_MS;
			w = fullW;
			h = fullH - (fullH - lineH) * t;
			line = false;
		}
		else
		{
			float t = (e - vCollapseEnd) / (float) H_COLLAPSE_MS;
			w = fullW * (1f - t);
			h = lineH;
			line = true;
		}

		int iw = Math.max(1, Math.round(w));
		int ih = Math.max(1, Math.round(h));
		int x = (fullW - iw) / 2;
		int y = (fullH - ih) / 2;

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

		if (line)
		{
			g2.setColor(LINE_COLOR);
			g2.fillRect(x, y, iw, ih);
		}
		else
		{
			g2.drawImage(background, x, y, iw, ih, null);

			if (e < holdEnd)
			{
				g2.clipRect(x, y, iw, ih);
				int maxTextW = fullW - 2 * TEXT_MARGIN_X;
				drawCentered(g2, active.top, headerFont, ORANGE, fullW, maxTextW, TOP_Y);
				drawCentered(g2, active.middle, bodyFont, ORANGE, fullW, maxTextW, MID_Y);
				drawWrapped(g2, active.bottom, bodyFont, WHITE, fullW, maxTextW, BOTTOM_Y);
			}
		}

		g2.dispose();

		return new Dimension(fullW, fullH);
	}

	private static void drawCentered(Graphics2D g, String text, Font font, Color color, int width, int maxWidth, float topY)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		String shown = truncate(text, fm, maxWidth);
		int x = (width - fm.stringWidth(shown)) / 2;
		int baseline = Math.round(topY) + fm.getAscent();
		g.setColor(SHADOW);
		g.drawString(shown, x + 1, baseline + 1);
		g.setColor(color);
		g.drawString(shown, x, baseline);
	}

	private static void drawWrapped(Graphics2D g, String text, Font font, Color color, int width, int maxWidth, float topY)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = wrap(text, fm, maxWidth, BOTTOM_MAX_LINES);
		int step = Math.round(WRAP_LINE_H);
		int top = Math.round(topY);
		for (int i = 0; i < lines.size(); i++)
		{
			String shown = lines.get(i);
			int x = (width - fm.stringWidth(shown)) / 2;
			int baseline = top + i * step + fm.getAscent();
			g.setColor(SHADOW);
			g.drawString(shown, x + 1, baseline + 1);
			g.setColor(color);
			g.drawString(shown, x, baseline);
		}
	}

	private static List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines)
	{
		List<String> lines = new ArrayList<>();
		if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth)
		{
			lines.add(text);
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+"))
		{
			String trial = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(trial) <= maxWidth)
			{
				line.setLength(0);
				line.append(trial);
				continue;
			}
			if (line.length() > 0)
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			line.append(word);
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		if (lines.size() > maxLines)
		{
			StringBuilder tail = new StringBuilder(lines.get(maxLines - 1));
			for (int i = maxLines; i < lines.size(); i++)
			{
				tail.append(' ').append(lines.get(i));
			}
			lines = new ArrayList<>(lines.subList(0, maxLines - 1));
			lines.add(tail.toString());
		}
		for (int i = 0; i < lines.size(); i++)
		{
			if (fm.stringWidth(lines.get(i)) > maxWidth)
			{
				lines.set(i, truncate(lines.get(i), fm, maxWidth));
			}
		}
		return lines;
	}

	private static String truncate(String text, FontMetrics fm, int maxWidth)
	{
		if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		final String ellipsis = "...";
		int ellipsisW = fm.stringWidth(ellipsis);
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth)
		{
			end--;
		}
		return end <= 0 ? ellipsis : text.substring(0, end).trim() + ellipsis;
	}
}
