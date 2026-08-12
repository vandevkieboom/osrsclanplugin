package com.timeserved.bingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Read-only view of the signed-in player's team board, laid out as the same
 * size x size grid as the real board — same tiles/progress
 * {@link BingoApiClient#fetchBoard} already pulls in, just no longer thrown
 * away after {@code refreshBoard()} extracts the item-id lookup from it.
 * Hover a cell for its name and progress.
 */
public class BingoPanel extends PluginPanel
{
	private static final int MIN_CELL_SIZE = 18;
	private static final int MAX_CELL_SIZE = 38;
	private static final int CELL_GAP = 2;

	private final ItemManager itemManager;
	private final JPanel content = new JPanel();

	@Inject
	BingoPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		setLayout(new BorderLayout());
		content.setLayout(new BorderLayout());
		add(content, BorderLayout.NORTH);
	}

	/** Safe to call from any thread — hops onto the Swing EDT itself. */
	void update(List<BoardResponse.Tile> tiles, int boardSize)
	{
		SwingUtilities.invokeLater(() -> render(tiles, boardSize));
	}

	private void render(List<BoardResponse.Tile> tiles, int boardSize)
	{
		content.removeAll();

		if (tiles.isEmpty() || boardSize <= 0)
		{
			JLabel empty = new JLabel(
				"<html>No team board yet.<br>Set your plugin key in the config to load one.</html>");
			empty.setForeground(Color.GRAY);
			empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			content.add(empty, BorderLayout.NORTH);
		}
		else
		{
			Map<Integer, BoardResponse.Tile> byPosition = new HashMap<>();
			for (BoardResponse.Tile tile : tiles)
			{
				byPosition.put(tile.position, tile);
			}

			int cellSize = Math.max(MIN_CELL_SIZE, Math.min(MAX_CELL_SIZE, 200 / boardSize));
			JPanel grid = new JPanel(new GridLayout(boardSize, boardSize, CELL_GAP, CELL_GAP));
			grid.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			for (int i = 0; i < boardSize * boardSize; i++)
			{
				grid.add(cell(byPosition.get(i), cellSize));
			}
			content.add(grid, BorderLayout.NORTH);
		}

		content.revalidate();
		content.repaint();
	}

	private JLabel cell(BoardResponse.Tile tile, int cellSize)
	{
		JLabel cell = new JLabel();
		cell.setOpaque(true);
		cell.setHorizontalAlignment(JLabel.CENTER);
		cell.setPreferredSize(new Dimension(cellSize, cellSize));

		if (tile == null)
		{
			// An empty board slot — nothing configured for it yet.
			cell.setBackground(ColorScheme.DARK_GRAY_COLOR.darker());
			cell.setBorder(BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1));
			return cell;
		}

		boolean complete = isComplete(tile);
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createLineBorder(
			complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.MEDIUM_GRAY_COLOR, 2));
		cell.setToolTipText(tooltipFor(tile));

		if (!tile.getItemIds().isEmpty())
		{
			// Several item ids can satisfy one tile (e.g. "any DT2 ring") —
			// showing the first is a good enough icon, this isn't meant to be
			// an exhaustive picker.
			AsyncBufferedImage image = itemManager.getImage(tile.getItemIds().get(0));
			image.addTo(cell);
		}
		else if (tile.isXpGoal() || tile.isKcGoal())
		{
			cell.setText(tile.isXpGoal() ? "XP" : "KC");
			cell.setForeground(complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
			cell.setFont(cell.getFont().deriveFont(Font.BOLD, Math.min(11f, cellSize / 2.2f)));
		}

		return cell;
	}

	private boolean isComplete(BoardResponse.Tile tile)
	{
		if (tile.isXpGoal() || tile.isKcGoal())
		{
			return tile.goalTarget != null && tile.teamProgress != null && tile.teamProgress >= tile.goalTarget;
		}
		return tile.approvedCount >= tile.requiredCount;
	}

	private String tooltipFor(BoardResponse.Tile tile)
	{
		String progress;
		if (tile.isXpGoal() || tile.isKcGoal())
		{
			long p = tile.teamProgress == null ? 0 : tile.teamProgress;
			long t = tile.goalTarget == null ? 0 : tile.goalTarget;
			progress = String.format("%,d / %,d", p, t);
		}
		else
		{
			progress = tile.approvedCount + " / " + tile.requiredCount;
		}
		return "<html>" + escapeHtml(tile.name) + "<br>" + progress + "</html>";
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
