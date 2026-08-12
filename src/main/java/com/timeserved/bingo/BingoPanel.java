package com.timeserved.bingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Read-only view of the signed-in player's team board — the same tiles/
 * progress {@link BingoApiClient#fetchBoard} already pulls in, just no
 * longer thrown away after {@code refreshBoard()} extracts the item-id
 * lookup from it.
 */
public class BingoPanel extends PluginPanel
{
	private final ItemManager itemManager;
	private final JPanel tileList = new JPanel();

	@Inject
	BingoPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		setLayout(new BorderLayout());
		tileList.setLayout(new BoxLayout(tileList, BoxLayout.Y_AXIS));
		add(tileList, BorderLayout.NORTH);
	}

	/** Safe to call from any thread — hops onto the Swing EDT itself. */
	void update(List<BoardResponse.Tile> tiles)
	{
		SwingUtilities.invokeLater(() -> render(tiles));
	}

	private void render(List<BoardResponse.Tile> tiles)
	{
		tileList.removeAll();

		if (tiles.isEmpty())
		{
			JLabel empty = new JLabel("<html>No team board yet.<br>Set your plugin key in the config to load one.</html>");
			empty.setForeground(Color.GRAY);
			empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			tileList.add(empty);
		}
		else
		{
			for (BoardResponse.Tile tile : tiles)
			{
				tileList.add(row(tile));
			}
		}

		tileList.revalidate();
		tileList.repaint();
	}

	private JPanel row(BoardResponse.Tile tile)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(22, 22));
		if (!tile.getItemIds().isEmpty())
		{
			// Several item ids can satisfy one tile (e.g. "any DT2 ring") —
			// showing the first is a good enough icon, this isn't meant to be
			// an exhaustive picker.
			AsyncBufferedImage image = itemManager.getImage(tile.getItemIds().get(0));
			image.addTo(icon);
		}

		boolean isTeamGoal = tile.isXpGoal() || tile.isKcGoal();
		String progressText;
		boolean complete;
		if (isTeamGoal)
		{
			long progress = tile.teamProgress == null ? 0 : tile.teamProgress;
			long target = tile.goalTarget == null ? 0 : tile.goalTarget;
			progressText = String.format("%,d / %,d", progress, target);
			complete = tile.goalTarget != null && progress >= target;
		}
		else
		{
			progressText = tile.approvedCount + " / " + tile.requiredCount;
			complete = tile.approvedCount >= tile.requiredCount;
		}

		JLabel name = new JLabel(tile.name);
		name.setForeground(complete ? Color.GREEN.darker() : Color.WHITE);

		JLabel progressLabel = new JLabel(progressText);
		progressLabel.setForeground(Color.GRAY);
		progressLabel.setHorizontalAlignment(JLabel.RIGHT);

		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		text.add(name, BorderLayout.WEST);
		text.add(progressLabel, BorderLayout.EAST);

		row.add(icon, BorderLayout.WEST);
		row.add(text, BorderLayout.CENTER);
		return row;
	}
}
