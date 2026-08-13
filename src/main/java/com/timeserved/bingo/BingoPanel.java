package com.timeserved.bingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
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
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
	private static final long TOAST_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(2);
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

	private final JPanel content = new ScrollableColumn();
	private final JScrollPane scrollPane;
	private final JLabel statusLabel = new JLabel();
	private final Timer statusTimer;

	private volatile long lastSyncedAt;
	private volatile String lastAutoSubmitItem;
	private volatile long lastAutoSubmitAt;

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

		JPanel toast = buildToast();
		if (toast != null)
		{
			content.add(Box.createVerticalStrut(10));
			content.add(toast);
		}

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
			content.repaint();
		});
	}

	/**
	 * Call right after the plugin auto-submits a drop, so the sidebar shows the same confirmation the
	 * chat message does. Doesn't repaint by itself — the caller (BingoPlugin#onSubmitted) always follows
	 * an auto-submit with a board refresh anyway, which is what actually surfaces the toast.
	 */
	public void notifyAutoSubmitted(String itemName)
	{
		lastAutoSubmitItem = itemName;
		lastAutoSubmitAt = System.currentTimeMillis();
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
			JLabel dot = new JLabel("●");
			dot.setFont(dot.getFont().deriveFont(8f));
			dot.setForeground(teamColor);
			metaRow.add(dot);
			metaRow.add(Box.createHorizontalStrut(5));

			JLabel teamName = new JLabel(myTeam.name);
			teamName.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
			teamName.setForeground(teamColor);
			metaRow.add(teamName);
		}
		else
		{
			metaRow.add(smallLabel("No team yet", ColorScheme.LIGHT_GRAY_COLOR));
		}

		if (board.config != null && board.config.size > 0)
		{
			metaRow.add(smallLabel("  ·  " + board.config.size + "×" + board.config.size, ColorScheme.LIGHT_GRAY_COLOR));
		}
		metaRow.add(Box.createHorizontalGlue());
		header.add(metaRow);
		return header;
	}

	// ---- board section -------------------------------------------------

	private JPanel buildBoardSection(BoardResponse board, BoardResponse.Team myTeam)
	{
		JPanel section = cappedColumn();
		section.add(sectionHeader("Your Board", myTeam.completeCount + "/" + myTeam.totalTiles));

		JPanel progressRow = cappedRow();
		progressRow.add(smallLabel("Team progress", ColorScheme.LIGHT_GRAY_COLOR));
		progressRow.add(Box.createHorizontalGlue());
		progressRow.add(smallLabel(myTeam.pct + "%", Color.WHITE));
		section.add(progressRow);
		section.add(Box.createVerticalStrut(4));

		ProgressBar boardBar = new ProgressBar();
		boardBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		boardBar.setProgress(myTeam.totalTiles > 0 ? (double) myTeam.completeCount / myTeam.totalTiles : 0, GOOD);
		section.add(boardBar);

		List<BoardResponse.Tile> allTiles = myTeam.getTiles();
		if (allTiles.isEmpty())
		{
			return section;
		}

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

		section.add(Box.createVerticalStrut(8));
		// A plain GridLayout can't do "stretch to fill the full width, but derive height so cells stay
		// square" — it either stretches both dimensions (rectangles, the original bug) or neither
		// (a small fixed block that wastes the rest of the sidebar, what happened after capping both).
		// SquareTileGrid computes cell size from its actual assigned width at layout time instead.
		SquareTileGrid grid = new SquareTileGrid(size, 3);
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
		section.add(grid);

		return section;
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

		JPanel section = cappedColumn();
		section.add(sectionHeader("Team Goals", null));
		for (int i = 0; i < goalTiles.size(); i++)
		{
			BoardResponse.Tile tile = goalTiles.get(i);
			long progress = tile.teamProgress == null ? 0 : tile.teamProgress;
			long target = tile.goalTarget == null || tile.goalTarget <= 0 ? 1 : tile.goalTarget;
			double fraction = (double) progress / target;
			Color color = progress >= target ? GOOD : WARN;
			String suffix = tile.isXpGoal() ? " XP" : " KC";
			section.add(goalRow(capitalize(tile.goalKey) + suffix,
				formatNumber(progress) + " / " + formatNumber(target), fraction, color));
			if (i < goalTiles.size() - 1)
			{
				section.add(Box.createVerticalStrut(8));
			}
		}
		return section;
	}

	private JPanel goalRow(String name, String value, double fraction, Color color)
	{
		JPanel row = cappedColumn();

		JPanel labelRow = cappedRow();
		JLabel nameLabel = smallLabel(name, ColorScheme.TEXT_COLOR);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
		labelRow.add(nameLabel);
		labelRow.add(Box.createHorizontalGlue());
		labelRow.add(smallLabel(value, ColorScheme.LIGHT_GRAY_COLOR));
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

		JPanel section = cappedColumn();
		section.add(sectionHeader("Clan Standings", null));

		if (teams.isEmpty())
		{
			section.add(smallLabel("No teams yet.", ColorScheme.LIGHT_GRAY_COLOR));
			return section;
		}

		List<BoardResponse.Team> shown = teams.size() <= LEADERBOARD_ROWS
			? teams
			: teams.subList(0, LEADERBOARD_ROWS);

		for (int i = 0; i < shown.size(); i++)
		{
			BoardResponse.Team team = shown.get(i);
			boolean mine = team.id.equals(board.myTeamId);
			section.add(leaderboardRow(i + 1, team, mine));
			if (i < shown.size() - 1)
			{
				section.add(Box.createVerticalStrut(6));
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
					section.add(Box.createVerticalStrut(10));
					section.add(leaderboardRow(i + 1, teams.get(i), true));
					break;
				}
			}
		}

		return section;
	}

	private JPanel leaderboardRow(int rank, BoardResponse.Team team, boolean mine)
	{
		JPanel row = capped(new BorderLayout(7, 0));
		if (mine)
		{
			row.setOpaque(true);
			row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			row.setBorder(new EmptyBorder(3, 4, 3, 4));
		}

		JLabel rankLabel = new JLabel(String.valueOf(rank));
		rankLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		rankLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		rankLabel.setPreferredSize(new Dimension(16, rankLabel.getPreferredSize().height));
		row.add(rankLabel, BorderLayout.WEST);

		JPanel middle = cappedColumn();
		middle.setOpaque(false);

		Color teamColor = parseColor(team.accentColor, NEUTRAL_SWATCH);

		JPanel nameRow = cappedRow();
		nameRow.setOpaque(false);
		JLabel swatch = new JLabel("■");
		swatch.setFont(swatch.getFont().deriveFont(8f));
		swatch.setForeground(teamColor);
		nameRow.add(swatch);
		nameRow.add(Box.createHorizontalStrut(5));
		JLabel nameLabel = new JLabel(team.name);
		nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(mine ? Font.BOLD : Font.PLAIN));
		nameLabel.setForeground(mine ? Color.WHITE : ColorScheme.TEXT_COLOR);
		nameRow.add(nameLabel);
		middle.add(nameRow);
		middle.add(Box.createVerticalStrut(3));

		ProgressBar bar = new ProgressBar();
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setPreferredSize(new Dimension(10, 4));
		bar.setProgress(team.pct / 100.0, mine ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR);
		middle.add(bar);

		row.add(middle, BorderLayout.CENTER);

		JLabel pctLabel = new JLabel(team.pct + "%");
		pctLabel.setFont(FontManager.getRunescapeSmallFont());
		pctLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pctLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		pctLabel.setPreferredSize(new Dimension(32, pctLabel.getPreferredSize().height));
		row.add(pctLabel, BorderLayout.EAST);

		return row;
	}

	// ---- toast + status --------------------------------------------------

	/** Null when nothing was auto-submitted recently — the caller just omits the row entirely. */
	private JPanel buildToast()
	{
		String item = lastAutoSubmitItem;
		long at = lastAutoSubmitAt;
		if (item == null || System.currentTimeMillis() - at > TOAST_WINDOW_MILLIS)
		{
			return null;
		}

		JPanel toast = capped(new BorderLayout(6, 0));
		toast.setOpaque(true);
		toast.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		toast.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, GOOD),
			new EmptyBorder(6, 6, 6, 6)));

		JLabel icon = new JLabel("⚒");
		icon.setFont(icon.getFont().deriveFont(13f));
		toast.add(icon, BorderLayout.WEST);

		JLabel text = new JLabel("<html>" + escapeHtml(item) + " auto-detected — submitted</html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setBorder(new EmptyBorder(0, 6, 0, 0));
		toast.add(text, BorderLayout.CENTER);

		return toast;
	}

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
		statusLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
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

	private JPanel sectionHeader(String text, String count)
	{
		JLabel title = new JLabel(text.toUpperCase());
		title.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, 5, 0)));
		row.add(title, BorderLayout.WEST);

		if (count != null)
		{
			JLabel countLabel = new JLabel(count);
			countLabel.setFont(FontManager.getRunescapeSmallFont());
			countLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			row.add(countLabel, BorderLayout.EAST);
		}

		JPanel wrap = capped(new BorderLayout());
		wrap.add(row, BorderLayout.CENTER);
		wrap.setBorder(new EmptyBorder(0, 0, 8, 0));
		return wrap;
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

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
			setPreferredSize(new Dimension(10, 6));
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
			g2.dispose();
		}
	}

	/**
	 * A grid of square cells that fills whatever width the parent container actually has, deriving cell
	 * (and therefore overall) height from that width — the Swing equivalent of CSS's
	 * {@code aspect-ratio: 1} on a {@code repeat(N, 1fr)} grid.
	 *
	 * <p>Two earlier versions of this got the width wrong in opposite directions: one measured its OWN
	 * getWidth() during layout and tried to self-correct via revalidate() once known, but the parent
	 * never ended up reserving the corrected height, so rows got positioned beyond this component's own
	 * bounds and Swing clipped them. The other computed a width from RuneLite's documented sidebar
	 * constants up front, which was close but assumed a scrollbar width that didn't match what this
	 * JScrollPane's actual scrollbar renders at, leaving an unfilled gap.
	 *
	 * <p>The fix: read {@link #getParent()}'s width instead of this component's own. A container can't be
	 * asked to arrange its children until it already has real bounds itself — that's a hard invariant of
	 * how Swing layout works — so by the time anything queries a child's preferred size, the parent's
	 * width is always already valid. No guessing, no placeholder-then-correct cycle needed.
	 */
	private static final class SquareTileGrid extends JPanel
	{
		private final int columns;
		private final int gap;

		SquareTileGrid(int columns, int gap)
		{
			this.columns = columns;
			this.gap = gap;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setLayout(null);
		}

		private int rows()
		{
			return (int) Math.ceil(getComponentCount() / (double) columns);
		}

		/** The parent's real interior width (its own width minus its border's insets), or a fallback if
		 * the parent isn't realized yet (shouldn't normally happen — see the class doc). */
		private int availableWidth()
		{
			Container parent = getParent();
			if (parent != null && parent.getWidth() > 0)
			{
				Insets insets = parent.getInsets();
				return parent.getWidth() - insets.left - insets.right;
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

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
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
