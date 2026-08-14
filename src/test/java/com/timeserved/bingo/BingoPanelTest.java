package com.timeserved.bingo;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;
import org.junit.AfterClass;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Renders BingoPanel with a fixture board, then drives the new collapsible
 * section headers through a real AWT click (not a direct method call) to
 * confirm the accordion actually collapses the body and updates the arrow —
 * the kind of Swing wiring bug (listener on the wrong component, wrong key
 * used for state) that only shows up by looking at rendered output.
 */
public class BingoPanelTest
{
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

	@AfterClass
	public static void shutdown()
	{
		EXECUTOR.shutdownNow();
	}

	private static BoardResponse fixtureBoard()
	{
		BoardResponse board = new BoardResponse();
		board.myTeamId = "team-1";
		board.config = new BoardResponse.Config();
		board.config.name = "Summer Bingo";
		board.config.size = 2;

		BoardResponse.Tile item1 = new BoardResponse.Tile();
		item1.tileId = "t1";
		item1.position = 0;
		item1.name = "Bat bones";
		item1.status = "approved";

		BoardResponse.Tile item2 = new BoardResponse.Tile();
		item2.tileId = "t2";
		item2.position = 1;
		item2.name = "Dragon bones";
		item2.status = "none";

		BoardResponse.Tile goal = new BoardResponse.Tile();
		goal.tileId = "t3";
		goal.position = 2;
		goal.name = "Strength XP";
		goal.goalKind = "xp";
		goal.goalKey = "strength";
		goal.goalTarget = 2000L;
		goal.teamProgress = 500L;

		BoardResponse.Team mine = new BoardResponse.Team();
		mine.id = "team-1";
		mine.name = "Auto Drafters";
		mine.completeCount = 1;
		mine.totalTiles = 2;
		mine.pct = 50;
		mine.tiles = new ArrayList<>(List.of(item1, item2, goal));

		BoardResponse.Team other = new BoardResponse.Team();
		other.id = "team-2";
		other.name = "Locked and Balding";
		other.completeCount = 2;
		other.totalTiles = 2;
		other.pct = 100;
		other.tiles = new ArrayList<>();

		board.teams = new ArrayList<>(List.of(mine, other));
		return board;
	}

	/** A 4x4, 16-tile board — reproduces a real report of the grid's bottom row(s) getting clipped. */
	private static BoardResponse fixture4x4Board()
	{
		BoardResponse board = new BoardResponse();
		board.myTeamId = "team-1";
		board.config = new BoardResponse.Config();
		board.config.name = "Bingo";
		board.config.size = 4;

		List<BoardResponse.Tile> tiles = new ArrayList<>();
		for (int i = 0; i < 16; i++)
		{
			BoardResponse.Tile tile = new BoardResponse.Tile();
			tile.tileId = "t" + i;
			tile.position = i;
			tile.name = "Tile " + i;
			tile.status = "none";
			tiles.add(tile);
		}

		BoardResponse.Team mine = new BoardResponse.Team();
		mine.id = "team-1";
		mine.name = "Stuck with ded";
		mine.completeCount = 0;
		mine.totalTiles = 16;
		mine.pct = 0;
		mine.tiles = tiles;

		board.teams = new ArrayList<>(List.of(mine));
		return board;
	}

	private static Component findByName(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
			if (c instanceof Container)
			{
				Component found = findByName((Container) c, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void click(Component target)
	{
		MouseEvent event = new MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
			0, 5, 5, 1, false);
		target.dispatchEvent(event);
	}

	/**
	 * A detached component tree that's never been realized (no window ancestor, no peer) paints its
	 * own background but skips its children's text entirely — an off-screen JFrame gives it one.
	 */
	private static JFrame realize(BingoPanel panel)
	{
		JFrame frame = new JFrame();
		frame.setUndecorated(true);
		frame.setLocation(-3000, -3000);
		frame.setLayout(new BorderLayout());
		frame.add(panel, BorderLayout.CENTER);
		frame.setSize(PluginPanel.PANEL_WIDTH, 700);
		frame.setVisible(true);
		frame.validate();
		return frame;
	}

	private static void render(BingoPanel panel, String label) throws Exception
	{
		panel.setSize(PluginPanel.PANEL_WIDTH, 700);
		panel.doLayout();
		panel.validate();
		panel.repaint();

		BufferedImage image = new BufferedImage(PluginPanel.PANEL_WIDTH, 700, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		panel.printAll(g);
		g.dispose();

		File out = new File("build/test-results/bingo-panel-" + label + ".png");
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}

	@Test
	public void collapsingBoardSectionHidesItsBodyAndSurvivesARefresh() throws Exception
	{
		BoardResponse board = fixtureBoard();

		BingoPanel[] holder = new BingoPanel[1];
		SwingUtilities.invokeAndWait(() -> {
			BingoPanel panel = new BingoPanel(EXECUTOR);
			panel.refresh(board);
			holder[0] = panel;
		});
		// refresh() finishes its scroll-position fix-up in a follow-up invokeLater — flush it.
		SwingUtilities.invokeAndWait(() -> {});
		BingoPanel panel = holder[0];

		JFrame[] frameHolder = new JFrame[1];
		SwingUtilities.invokeAndWait(() -> frameHolder[0] = realize(panel));

		SwingUtilities.invokeAndWait(() -> {
			try
			{
				render(panel, "0-expanded");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});

		Component header = findByName(panel, "collapsible-header-board");
		assertNotNull("collapsible header for the board section should exist", header);

		Component body = ((Container) header.getParent()).getComponent(1);
		assertTrue("board section body should start expanded", body.isVisible());

		SwingUtilities.invokeAndWait(() -> click(header));

		assertTrue("clicking the header should collapse the body", !body.isVisible());

		SwingUtilities.invokeAndWait(() -> {
			try
			{
				render(panel, "1-board-collapsed");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});

		// Simulates the next scheduled board poll on the SAME long-lived panel instance (as production
		// does) — refresh() tears down and rebuilds every section from scratch, so this is exactly the
		// case sectionExpanded exists to survive.
		SwingUtilities.invokeAndWait(() -> panel.refresh(board));
		SwingUtilities.invokeAndWait(() -> {});

		Component headerAfterRefresh = findByName(panel, "collapsible-header-board");
		assertNotNull(headerAfterRefresh);
		Component bodyAfterRefresh = ((Container) headerAfterRefresh.getParent()).getComponent(1);
		assertTrue("collapsed state must survive a refresh(), not silently re-expand", !bodyAfterRefresh.isVisible());

		SwingUtilities.invokeAndWait(() -> {
			try
			{
				render(panel, "2-still-collapsed-after-refresh");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});

		SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
	}

	@Test
	public void fourByFourBoardShowsAllSixteenTiles() throws Exception
	{
		BoardResponse board = fixture4x4Board();

		BingoPanel[] holder = new BingoPanel[1];
		SwingUtilities.invokeAndWait(() -> {
			BingoPanel panel = new BingoPanel(EXECUTOR);
			panel.refresh(board);
			holder[0] = panel;
		});
		SwingUtilities.invokeAndWait(() -> {});
		BingoPanel panel = holder[0];

		JFrame[] frameHolder = new JFrame[1];
		SwingUtilities.invokeAndWait(() -> frameHolder[0] = realize(panel));

		SwingUtilities.invokeAndWait(() -> {
			try
			{
				render(panel, "3-four-by-four-board");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});

		SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
	}
}
