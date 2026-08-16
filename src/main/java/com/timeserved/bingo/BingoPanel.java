package com.timeserved.bingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The clan bingo sidebar tab: the caller's team board, any xp/kc goal tiles, a clan-wide leaderboard,
 * and a status line. Every section (except the header) is only added when there's real data for it —
 * e.g. no goal tiles on the board means no "Team Goals" header floating over nothing.
 */
@Slf4j
public class BingoPanel extends PluginPanel
{
	private static final Color GOOD = new Color(55, 240, 70);
	private static final Color WARN = new Color(230, 150, 30);
	private static final Color NEUTRAL_SWATCH = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final int LEADERBOARD_ROWS = 5;
	private static final int TILE_SIZE = 30;
	private static final int TILE_ICON_PX = 20;
	private static final int CONTENT_PADDING = 10;
	/**
	 * The real usable width for content in this sidebar, computed from RuneLite's own fixed constants
	 * rather than discovered at runtime — PluginPanel.PANEL_WIDTH never changes while the client is
	 * running, so there's no need to measure getWidth() during a layout pass (and no risk of the
	 * self-correction timing bug that came from trying to).
	 */
	private static final int CONTENT_WIDTH =
		PluginPanel.PANEL_WIDTH - PluginPanel.SCROLLBAR_WIDTH - CONTENT_PADDING * 2;

	private final ScheduledExecutorService executor;
	private final Map<String, ImageIcon> iconCache = new ConcurrentHashMap<>();
	/** Which collapsible sections are open, keyed by a short section id. Survives refresh()'s full
	 * teardown-and-rebuild — otherwise every board poll would silently re-expand anything you'd closed. */
	private final Map<String, Boolean> sectionExpanded = new HashMap<>();

	private final JPanel content = new ScrollableColumn();
	private final JScrollPane scrollPane;
	private final JLabel statusLabel = new JLabel();
	private final Timer statusTimer;

	private volatile long lastSyncedAt;

	@Inject
	public BingoPanel(ScheduledExecutorService executor)
	{
		super(false);
		this.executor = executor;

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(new EmptyBorder(10, CONTENT_PADDING, 14, CONTENT_PADDING));

		scrollPane = new JScrollPane(content);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scrollPane, BorderLayout.CENTER);

		showNoApiKey();

		statusTimer = new Timer(1000, e -> updateStatusLabelText());
		statusTimer.start();
	}

	/** Stops the status-line ticker. Call when the plugin unloads. */
	public void dispose()
	{
		statusTimer.stop();
	}

	/** Shown whenever there's no plugin key configured — nothing to fetch, so nothing to show. */
	public void showNoApiKey()
	{
		content.removeAll();
		JLabel prompt = new JLabel(
			"<html>Set your plugin key in the Time Served config to see your board here.</html>");
		prompt.setFont(FontManager.getRunescapeSmallFont());
		prompt.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(prompt);
		content.revalidate();
		content.repaint();
	}

	/**
	 * Rebuilds the whole panel from a fresh board fetch. Must be called on the EDT.
	 *
	 * <p>This is a full teardown-and-rebuild every time — including every scheduled poll and every
	 * teleport (RuneLite fires GameState.LOGGED_IN for any area/instance load, not just literal login,
	 * and BingoPlugin refreshes the board on it) — so without this, the scroll position would snap back
	 * to the top on every single refresh, making it look like sections were swapping places if you
	 * happened to be scrolled down to one of them.
	 */
	public void refresh(BoardResponse board)
	{
		int scrollPosition = scrollPane.getVerticalScrollBar().getValue();

		// Hidden for the whole teardown-and-rebuild below, not just the
		// scroll-position correction: rebuilding every section fresh means
		// their combined height is briefly whatever partial state they're
		// in mid-rebuild (e.g. a section not added yet, or the board grid's
		// real height not yet known — see loadIconInto), which BoxLayout is
		// free to lay out and paint before this method even returns. A
		// hidden component is skipped by its parent's paint entirely, so
		// none of those intermediate layouts are ever actually shown —
		// setVisible(true) in the deferred callback below is the first
		// paint anyone sees, and by then the layout is already final.
		content.setVisible(false);

		lastSyncedAt = System.currentTimeMillis();
		content.removeAll();

		BoardResponse.Team myTeam = board.findMyTeam();

		content.add(buildHeader(board, myTeam));
		content.add(Box.createVerticalStrut(12));

		if (myTeam != null)
		{
			content.add(buildBoardSection(board, myTeam));
			content.add(Box.createVerticalStrut(12));
		}

		JPanel goals = buildGoalsSection(myTeam);
		if (goals != null)
		{
			content.add(goals);
			content.add(Box.createVerticalStrut(12));
		}

		content.add(buildLeaderboardSection(board));

		content.add(Box.createVerticalStrut(6));
		content.add(buildStatusLine());

		content.revalidate();

		// Deliberately no content.repaint() here: revalidate() only *schedules* the layout pass and
		// doesn't itself paint anything, but repainting immediately would still show one real frame at
		// the reset (top) scroll position before the correction below took effect — restoring the value
		// fixes the number, but the wrong position had already been painted, which is the actual "jump"
		// being seen. Deferring both the correction AND the repaint into the same later callback means
		// the scrollbar is already right before anything is drawn at all, so that frame never happens.
		SwingUtilities.invokeLater(() -> {
			scrollPane.getVerticalScrollBar().setValue(scrollPosition);
			content.setVisible(true);
			content.repaint();
		});
	}

	// ---- header ------------------------------------------------------

	private JPanel buildHeader(BoardResponse board, BoardResponse.Team myTeam)
	{
		JPanel header = cappedColumn();
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, 8, 0)));

		String eventName = board.config != null && board.config.name != null && !board.config.name.isEmpty()
			? board.config.name : "Bingo";
		JLabel eventLabel = new JLabel(eventName);
		eventLabel.setFont(eventLabel.getFont().deriveFont(Font.BOLD, 13f));
		eventLabel.setForeground(Color.WHITE);
		eventLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(eventLabel);
		header.add(Box.createVerticalStrut(3));

		JPanel metaRow = cappedRow();
		if (myTeam != null)
		{
			Color teamColor = parseColor(myTeam.accentColor, ColorScheme.BRAND_ORANGE);

			JLabel teamName = new JLabel(myTeam.name);
			teamName.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
			teamName.setForeground(teamColor);
			metaRow.add(teamName);
		}
		else
		{
			metaRow.add(smallLabel("No team yet", ColorScheme.LIGHT_GRAY_COLOR));
		}

		metaRow.add(Box.createHorizontalGlue());
		header.add(metaRow);
		return header;
	}

	// ---- board section -------------------------------------------------

	private JPanel buildBoardSection(BoardResponse board, BoardResponse.Team myTeam)
	{
		JPanel body = cappedColumn();

		JPanel progressRow = cappedRow();
		progressRow.add(smallLabel("Team progress", Color.WHITE));
		progressRow.add(Box.createHorizontalGlue());
		progressRow.add(smallLabel(myTeam.pct + "%", Color.WHITE));
		body.add(progressRow);
		body.add(Box.createVerticalStrut(4));

		ProgressBar boardBar = new ProgressBar();
		boardBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		boardBar.setProgress(myTeam.totalTiles > 0 ? (double) myTeam.completeCount / myTeam.totalTiles : 0, GOOD);
		body.add(boardBar);

		List<BoardResponse.Tile> allTiles = myTeam.getTiles();
		if (!allTiles.isEmpty())
		{
			// Every tile — item or xp/kc goal — occupies a real board position (see the `position` column
			// in db/schema.sql), so all of them belong in the grid, not just item tiles. A tile that isn't
			// on the current team's board response yet (goal or otherwise) just leaves that slot blank.
			int size = board.config != null && board.config.size > 0
				? board.config.size
				: (int) Math.ceil(Math.sqrt(allTiles.size()));
			int totalSlots = size * size;

			BoardResponse.Tile[] byPosition = new BoardResponse.Tile[totalSlots];
			for (BoardResponse.Tile tile : allTiles)
			{
				if (tile.position >= 0 && tile.position < totalSlots)
				{
					byPosition[tile.position] = tile;
				}
			}

			body.add(Box.createVerticalStrut(8));
			// A plain GridLayout can't do "stretch to fill the full width, but derive height so cells stay
			// square" — it either stretches both dimensions (rectangles, the original bug) or neither
			// (a small fixed block that wastes the rest of the sidebar, what happened after capping both).
			// SquareTileGrid computes cell size from its actual assigned width at layout time instead.
			SquareTileGrid grid = new SquareTileGrid(size, 3, content);
			for (BoardResponse.Tile tile : byPosition)
			{
				if (tile == null)
				{
					grid.add(blankSlot());
					continue;
				}
				// Item tiles have approved/pending/rejected/none; goal tiles are only ever approved (target
				// reached) or none (see api/board.ts) — both map onto the same two/three visual states fine.
				TileCell.State state = "approved".equals(tile.status) ? TileCell.State.DONE
					: "pending".equals(tile.status) ? TileCell.State.PENDING
					: TileCell.State.EMPTY;
				TileCell cell = new TileCell(state);
				cell.setToolTipText(tile.name);
				loadIconInto(cell, tile.iconUrl);
				grid.add(cell);
			}
			body.add(grid);
		}

		return collapsibleSection("board", "Your Board", myTeam.completeCount + "/" + myTeam.totalTiles, body);
	}

	/** An unconfigured board position — no tile at all, not just an incomplete one. */
	private static JPanel blankSlot()
	{
		JPanel slot = new JPanel();
		slot.setOpaque(false);
		slot.setPreferredSize(new Dimension(TILE_SIZE, TILE_SIZE));
		return slot;
	}

	private void loadIconInto(TileCell cell, String iconUrl)
	{
		if (iconUrl == null || iconUrl.isEmpty())
		{
			return;
		}
		ImageIcon cached = iconCache.get(iconUrl);
		if (cached != null)
		{
			cell.setIcon(cached);
			return;
		}
		executor.execute(() -> {
			try
			{
				BufferedImage image = ImageIO.read(new URL(iconUrl));
				if (image == null)
				{
					return;
				}
				Image scaled = image.getScaledInstance(TILE_ICON_PX, TILE_ICON_PX, Image.SCALE_SMOOTH);
				ImageIcon icon = new ImageIcon(scaled);
				iconCache.put(iconUrl, icon);
				SwingUtilities.invokeLater(() -> cell.setIcon(icon));
			}
			catch (IOException e)
			{
				log.debug("Failed to load tile icon {}", iconUrl, e);
			}
		});
	}

	// ---- goals section -------------------------------------------------

	private JPanel buildGoalsSection(BoardResponse.Team myTeam)
	{
		if (myTeam == null)
		{
			return null;
		}
		List<BoardResponse.Tile> goalTiles = new ArrayList<>();
		for (BoardResponse.Tile tile : myTeam.getTiles())
		{
			if (tile.isXpGoal() || tile.isKcGoal())
			{
				goalTiles.add(tile);
			}
		}
		if (goalTiles.isEmpty())
		{
			return null;
		}

		JPanel body = cappedColumn();
		for (int i = 0; i < goalTiles.size(); i++)
		{
			BoardResponse.Tile tile = goalTiles.get(i);
			long progress = tile.teamProgress == null ? 0 : tile.teamProgress;
			long target = tile.goalTarget == null || tile.goalTarget <= 0 ? 1 : tile.goalTarget;
			double fraction = (double) progress / target;
			Color color = progress >= target ? GOOD : WARN;
			String suffix = tile.isXpGoal() ? " XP" : " KC";
			body.add(goalRow(capitalize(tile.goalKey) + suffix,
				formatNumber(progress) + " / " + formatNumber(target), fraction, color));
			if (i < goalTiles.size() - 1)
			{
				body.add(Box.createVerticalStrut(8));
			}
		}
		return collapsibleSection("goals", "Team Goals", null, body);
	}

	private JPanel goalRow(String name, String value, double fraction, Color color)
	{
		JPanel row = cappedColumn();

		JPanel labelRow = cappedRow();
		JLabel nameLabel = smallLabel(name, Color.WHITE);
		labelRow.add(nameLabel);
		labelRow.add(Box.createHorizontalGlue());
		labelRow.add(smallLabel(value, Color.WHITE));
		row.add(labelRow);
		row.add(Box.createVerticalStrut(3));

		ProgressBar bar = new ProgressBar();
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setProgress(fraction, color);
		row.add(bar);

		return row;
	}

	// ---- leaderboard section --------------------------------------------

	private JPanel buildLeaderboardSection(BoardResponse board)
	{
		List<BoardResponse.Team> teams = new ArrayList<>(board.getTeams());
		teams.sort((a, b) -> b.pct != a.pct
			? Integer.compare(b.pct, a.pct)
			: a.name.compareToIgnoreCase(b.name));

		JPanel body = cappedColumn();

		if (teams.isEmpty())
		{
			body.add(smallLabel("No teams yet.", ColorScheme.LIGHT_GRAY_COLOR));
			return collapsibleSection("standings", "Clan Standings", null, body);
		}

		List<BoardResponse.Team> shown = teams.size() <= LEADERBOARD_ROWS
			? teams
			: teams.subList(0, LEADERBOARD_ROWS);

		for (int i = 0; i < shown.size(); i++)
		{
			BoardResponse.Team team = shown.get(i);
			boolean mine = team.id.equals(board.myTeamId);
			body.add(leaderboardRow(i + 1, team, mine));
			if (i < shown.size() - 1)
			{
				body.add(Box.createVerticalStrut(6));
			}
		}

		// Own team fell outside the top N — pin it below with a small gap, so you can always see where
		// you stand even if the board's full of teams ahead of yours.
		boolean myTeamShown = shown.stream().anyMatch(t -> t.id.equals(board.myTeamId));
		if (!myTeamShown && board.myTeamId != null)
		{
			for (int i = 0; i < teams.size(); i++)
			{
				if (teams.get(i).id.equals(board.myTeamId))
				{
					body.add(Box.createVerticalStrut(10));
					body.add(leaderboardRow(i + 1, teams.get(i), true));
					break;
				}
			}
		}

		return collapsibleSection("standings", "Clan Standings", null, body);
	}

	private JPanel leaderboardRow(int rank, BoardResponse.Team team, boolean mine)
	{
		JPanel row = roundedRow(new BorderLayout(7, 0));
		row.setBorder(new EmptyBorder(3, 4, 3, 4));
		Color teamColor = parseColor(team.accentColor, NEUTRAL_SWATCH);
		Color barColor = mine ? teamColor : NEUTRAL_SWATCH;
		if (mine)
		{
			// A plain gray hover color didn't read as "your team" clearly enough — a translucent tint of
			// the team's own accent color stands out regardless of what that color actually is.
			row.setOpaque(true);
			row.setBackground(new Color(
				teamColor.getRed(),
				teamColor.getGreen(),
				teamColor.getBlue(),
				50));
		}

		JLabel rankLabel = new JLabel(String.valueOf(rank));
		rankLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		rankLabel.setForeground(Color.WHITE);
		rankLabel.setPreferredSize(new Dimension(16, rankLabel.getPreferredSize().height));
		row.add(rankLabel, BorderLayout.WEST);

		JPanel middle = cappedColumn();
		middle.setOpaque(false);

		JPanel nameRow = cappedRow();
		nameRow.setOpaque(false);
		JLabel nameLabel = new JLabel(team.name);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(mine ? teamColor : Color.WHITE);
		nameRow.add(nameLabel);
		middle.add(nameRow);
		middle.add(Box.createVerticalStrut(4));

		ProgressBar bar = new ProgressBar();
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setProgress(team.pct / 100.0, barColor);
		middle.add(bar);

		row.add(middle, BorderLayout.CENTER);

		JLabel pctLabel = new JLabel(team.pct + "%");
		pctLabel.setFont(FontManager.getRunescapeSmallFont());
		pctLabel.setForeground(Color.WHITE);
		pctLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		pctLabel.setPreferredSize(new Dimension(32, pctLabel.getPreferredSize().height));
		row.add(pctLabel, BorderLayout.EAST);

		return row;
	}

	// ---- status --------------------------------------------------

	private JPanel buildStatusLine()
	{
		JPanel row = capped(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR));

		JLabel dot = new JLabel("●");
		dot.setFont(dot.getFont().deriveFont(7f));
		dot.setForeground(lastSyncedAt == 0 ? ColorScheme.MEDIUM_GRAY_COLOR : GOOD);
		dot.setBorder(new EmptyBorder(6, 0, 0, 0));
		row.add(dot, BorderLayout.WEST);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(Color.WHITE);
		statusLabel.setBorder(new EmptyBorder(6, 6, 0, 0));
		updateStatusLabelText();
		row.add(statusLabel, BorderLayout.CENTER);

		return row;
	}

	/** Ticks independently of a full refresh() — a board re-fetch only happens once a minute, but the
	 * "synced Xs ago" text should visibly count up in between. */
	private void updateStatusLabelText()
	{
		if (lastSyncedAt == 0)
		{
			statusLabel.setText("Not yet synced");
			return;
		}
		long seconds = (System.currentTimeMillis() - lastSyncedAt) / 1000;
		if (seconds < 60)
		{
			statusLabel.setText("Synced " + seconds + "s ago");
		}
		else if (seconds < 3600)
		{
			statusLabel.setText("Synced " + (seconds / 60) + "m ago");
		}
		else
		{
			statusLabel.setText("Synced " + (seconds / 3600) + "h ago");
		}
	}

	// ---- shared helpers --------------------------------------------------

	/**
	 * A section with a clickable, arrow-prefixed header that expands/collapses {@code body} — an
	 * accordion, so a long board doesn't force scrolling past sections you already know the state of.
	 * Expand/collapse state is kept in {@link #sectionExpanded} by {@code key}, not on the component
	 * itself, since refresh() tears down and rebuilds every section fresh on every poll; without that,
	 * anything you'd collapsed would silently pop back open on the next refresh.
	 *
	 * <p>The whole header row toggles on click, not just the arrow glyph — Swing delivers a mouse event
	 * to whichever child component is directly under the cursor, so the same listener has to be attached
	 * to the row itself AND every label inside it, or clicking directly on the title text would do nothing.
	 */
	private JPanel collapsibleSection(String key, String title, String count, JPanel body)
	{
		boolean expanded = sectionExpanded.getOrDefault(key, true);
		body.setVisible(expanded);

		JLabel arrow = smallLabel(expanded ? "▾" : "▸", ColorScheme.BRAND_ORANGE);

		JLabel titleLabel = new JLabel(title.toUpperCase());
		titleLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		titleLabel.setForeground(ColorScheme.BRAND_ORANGE);

		JPanel titleRow = cappedRow();
		titleRow.setOpaque(false);
		titleRow.add(arrow);
		titleRow.add(Box.createHorizontalStrut(5));
		titleRow.add(titleLabel);

		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerRow.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, 5, 0)));
		headerRow.add(titleRow, BorderLayout.WEST);

		JLabel countLabel = null;
		if (count != null)
		{
			countLabel = new JLabel(count);
			countLabel.setFont(FontManager.getRunescapeSmallFont());
			countLabel.setForeground(Color.WHITE);
			headerRow.add(countLabel, BorderLayout.EAST);
		}

		JPanel headerWrap = capped(new BorderLayout());
		headerWrap.add(headerRow, BorderLayout.CENTER);
		headerWrap.setBorder(new EmptyBorder(0, 0, 8, 0));
		headerWrap.setName("collapsible-header-" + key);

		MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean nowExpanded = !body.isVisible();
				body.setVisible(nowExpanded);
				sectionExpanded.put(key, nowExpanded);
				arrow.setText(nowExpanded ? "▾" : "▸");
				content.revalidate();
				content.repaint();
			}
		};
		for (Component c : new Component[]{headerWrap, headerRow, titleRow, arrow, titleLabel, countLabel})
		{
			if (c == null)
			{
				continue;
			}
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			c.addMouseListener(toggle);
		}

		JPanel section = cappedColumn();
		section.add(headerWrap);
		section.add(body);
		return section;
	}

	private JLabel smallLabel(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}

	private static Color parseColor(String hex, Color fallback)
	{
		if (hex == null || hex.isEmpty())
		{
			return fallback;
		}
		try
		{
			return Color.decode(hex);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	/** "slayer" -> "Slayer", "chambers of xeric" -> "Chambers Of Xeric". */
	private static String capitalize(String text)
	{
		if (text == null || text.isEmpty())
		{
			return text;
		}
		String[] words = text.trim().split("\\s+");
		StringBuilder sb = new StringBuilder();
		for (String word : words)
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
		}
		return sb.toString();
	}

	private static String formatNumber(long value)
	{
		if (value >= 1_000_000)
		{
			return String.format("%.1fM", value / 1_000_000.0);
		}
		if (value >= 1_000)
		{
			return String.format("%.1fK", value / 1_000.0);
		}
		return String.valueOf(value);
	}

	/**
	 * A height-capped, left-aligned container so a BoxLayout parent can't stretch it — for layouts that
	 * don't care which container they're attached to (BorderLayout, GridLayout). BoxLayout itself can't
	 * go through this: it's validated against the exact container it's constructed with, so a vertical or
	 * horizontal box needs {@link #cappedColumn()} / {@link #cappedRow()} instead, which set it on
	 * themselves.
	 */
	private static JPanel capped(LayoutManager layout)
	{
		JPanel panel = new JPanel(layout)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	private static final int ROUNDED_ROW_RADIUS = 8;

	/**
	 * Same height-capped behavior as {@link #capped}, but paints its own background (when
	 * {@code setOpaque(true)} + {@code setBackground(...)} are used, as the leaderboard's "your team" row
	 * does) as a rounded rect instead of Swing's default hard-cornered opaque fill.
	 */
	private static JPanel roundedRow(LayoutManager layout)
	{
		JPanel panel = new JPanel(layout)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				if (isOpaque())
				{
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setColor(getBackground());
					g2.fillRoundRect(0, 0, getWidth(), getHeight(), ROUNDED_ROW_RADIUS, ROUNDED_ROW_RADIUS);
					g2.dispose();
				}
			}
		};
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	private static JPanel cappedColumn()
	{
		JPanel panel = capped(null);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		return panel;
	}

	private static JPanel cappedRow()
	{
		JPanel panel = capped(null);
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		return panel;
	}

	// ---- self-painted components (deliberately not native JProgressBar) -------

	/** A flat, self-painted progress bar — avoids relying on any Look and Feel's native progress-bar chrome. */
	private static final class ProgressBar extends JPanel
	{
		private double fraction;
		private Color fillColor = ColorScheme.BRAND_ORANGE;

		ProgressBar()
		{
			setOpaque(false);
			setPreferredSize(new Dimension(10, 7));
		}

		void setProgress(double fraction, Color fillColor)
		{
			this.fraction = Math.max(0, Math.min(1, fraction));
			this.fillColor = fillColor;
			repaint();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(new Color(20, 20, 20));
			g2.fillRoundRect(0, 0, w, h, h, h);
			int fillW = (int) Math.round(w * fraction);
			if (fillW > 0)
			{
				g2.setColor(fillColor);
				g2.fillRoundRect(0, 0, Math.max(fillW, h), h, h, h);
			}
			g2.setColor(Color.BLACK);
			g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
			g2.dispose();
		}
	}

	/**
	 * A grid of square cells that fills whatever width the sidebar actually has, deriving cell (and
	 * therefore overall) height from that width — the Swing equivalent of CSS's
	 * {@code aspect-ratio: 1} on a {@code repeat(N, 1fr)} grid.
	 *
	 * <p>Three earlier versions of this got the width wrong. One measured its OWN getWidth() during
	 * layout and tried to self-correct via revalidate() once known, but the parent never ended up
	 * reserving the corrected height, so rows got positioned beyond this component's own bounds and
	 * Swing clipped them. Another computed a width from RuneLite's documented sidebar constants up
	 * front, which was close but assumed a scrollbar width that didn't match what this JScrollPane's
	 * actual scrollbar renders at, leaving an unfilled gap. A third read {@link #getParent()}'s width
	 * instead of this component's own — correct in principle (a container can't be asked to arrange its
	 * children until it already has real bounds itself), but it silently assumed the grid's immediate
	 * parent WAS the one reliably-already-sized ancestor. That broke the moment a collapsible-section
	 * wrapper got inserted between them: the grid's very first preferred-size query (before its new,
	 * deeper parent had a valid width yet) got cached into the ancestor chain's reserved height by
	 * BoxLayout, while doLayout() below went on to correctly compute a bigger cellSize once the parent
	 * DID have a real width — same clipped-bottom-row bug as the very first version, just introduced by
	 * a refactor nobody expected to affect this.
	 *
	 * <p>The actual fix: don't guess which ancestor is reliable — take an explicit reference to one that
	 * always is. {@code widthReference} is {@link BingoPanel#content}, the scroll view itself, which the
	 * JScrollPane machinery keeps sized to the real sidebar width no matter how many wrapper panels this
	 * class ends up nested under in the future.
	 */
	private static final class SquareTileGrid extends JPanel
	{
		private final int columns;
		private final int gap;
		private final Component widthReference;

		SquareTileGrid(int columns, int gap, Component widthReference)
		{
			this.columns = columns;
			this.gap = gap;
			this.widthReference = widthReference;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setLayout(null);
		}

		private int rows()
		{
			return (int) Math.ceil(getComponentCount() / (double) columns);
		}

		/** {@link #widthReference}'s real interior width, or a fallback if it isn't realized yet
		 * (shouldn't normally happen — see the class doc). */
		private int availableWidth()
		{
			if (widthReference.getWidth() > 0)
			{
				Insets insets = widthReference instanceof Container
					? ((Container) widthReference).getInsets()
					: new Insets(0, 0, 0, 0);
				return widthReference.getWidth() - insets.left - insets.right;
			}
			return CONTENT_WIDTH;
		}

		private int cellSize()
		{
			return Math.max(1, (availableWidth() - (columns - 1) * gap) / columns);
		}

		@Override
		public Dimension getPreferredSize()
		{
			int cell = cellSize();
			int width = columns * cell + (columns - 1) * gap;
			int height = rows() * cell + (rows() - 1) * gap;
			return new Dimension(width, height);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return getPreferredSize();
		}

		@Override
		public void doLayout()
		{
			int cellSize = cellSize();
			int count = getComponentCount();
			for (int i = 0; i < count; i++)
			{
				int row = i / columns;
				int col = i % columns;
				getComponent(i).setBounds(col * (cellSize + gap), row * (cellSize + gap), cellSize, cellSize);
			}
		}
	}

	/** One bingo tile: a loaded item icon (once fetched) plus a self-painted corner badge for done/pending state. */
	private static final class TileCell extends JPanel
	{
		enum State { DONE, PENDING, EMPTY }

		private final State state;
		private final JLabel iconLabel;

		TileCell(State state)
		{
			this.state = state;
			setLayout(new BorderLayout());
			setOpaque(true);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(BorderFactory.createLineBorder(borderColorFor(state)));
			setPreferredSize(new Dimension(TILE_SIZE, TILE_SIZE));

			iconLabel = new JLabel("", SwingConstants.CENTER);
			add(iconLabel, BorderLayout.CENTER);
		}

		void setIcon(ImageIcon icon)
		{
			iconLabel.setIcon(icon);
		}

		private static Color borderColorFor(State state)
		{
			switch (state)
			{
				case DONE:
					return new Color(55, 200, 70);
				case PENDING:
					return new Color(200, 140, 30);
				default:
					return ColorScheme.MEDIUM_GRAY_COLOR.darker();
			}
		}

		/**
		 * Overrides {@code paint}, not {@code paintComponent} — {@code paintComponent} runs BEFORE Swing
		 * paints this panel's children (the icon label), so drawing the badge there put it underneath the
		 * item icon whenever the two overlapped in the corner. {@code paint} runs the whole
		 * background+children+border sequence via {@code super.paint}, then this draws the badge on top of
		 * all of it, so it's never covered by the icon.
		 */
		@Override
		public void paint(Graphics g)
		{
			super.paint(g);
			if (state == State.EMPTY)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if (state == State.DONE)
			{
				// Swing clips a child's painting to its own bounds, unlike a CSS badge that can overhang a
				// corner, so this sits fully inside instead of bleeding over the edge.
				int d = 11;
				int x = getWidth() - d - 1;
				int y = 1;
				g2.setColor(new Color(55, 240, 70));
				g2.fillOval(x, y, d, d);
				g2.setColor(new Color(10, 40, 12));
				g2.setFont(g2.getFont().deriveFont(Font.BOLD, 7f));
				FontMetrics fm = g2.getFontMetrics();
				String check = "✓";
				g2.drawString(check, x + (d - fm.stringWidth(check)) / 2, y + (d + fm.getAscent()) / 2 - 1);
			}
			else
			{
				int d = 6;
				g2.setColor(new Color(230, 150, 30));
				g2.fillOval(getWidth() - d - 2, 2, d, d);
			}
			g2.dispose();
		}
	}

	/**
	 * A column that fills the scroll viewport's width so rows never clip horizontally — a plain JPanel
	 * view gets its preferred width inside a JScrollPane, so with HORIZONTAL_SCROLLBAR_NEVER any row
	 * wider than the sidebar silently disappears under the vertical scrollbar.
	 */
	private static final class ScrollableColumn extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
