package com.timeserved.bingo;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptEvent;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;

/**
 * Step one of an in-game bingo board: injects a "Bingo" tab into the
 * Collection Log's native tab row, styled to match Jagex's own tabs, that
 * currently just shows a placeholder when clicked. This exists to prove the
 * injection mechanism itself works correctly in a real client before any
 * real board content gets built on top of it.
 *
 * <p>Technique — tab geometry/sprites, hiding the native boss list,
 * restoring native content on navigate-away — is adapted from
 * AhmedFathy2001/anvil-plugin's {@code ClogTabController} (BSD 2-Clause
 * License, Copyright (c) 2026 AhmedFathy2001). See {@link BingoClogIds} for
 * the specific borrowed geometry/sprite constants.
 *
 * <p>Deliberately NOT included yet, to keep this first pass small and
 * verifiable on its own: the Adventure Log's alternate interface id, the
 * per-game-tick safety net Anvil also has (in case the search-input/other
 * edge cases bypass the COLLECTION_DRAW_LIST signal), and obviously any real
 * board content.
 */
@Slf4j
@Singleton
class BingoClogTabController
{
	private static final String PLACEHOLDER_WIDGET_NAME = "TimeServedBingoPlaceholder";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private volatile boolean clogOpen;
	private volatile boolean tabActive;

	void onWidgetLoaded(int groupId)
	{
		if (groupId != InterfaceID.COLLECTION_LOG)
		{
			return;
		}
		clogOpen = true;
		tabActive = false;
		clientThread.invokeLater(this::injectTab);
	}

	void onWidgetClosed(int groupId)
	{
		if (groupId == InterfaceID.COLLECTION_LOG)
		{
			clogOpen = false;
			tabActive = false;
		}
	}

	/**
	 * COLLECTION_DRAW_LIST fires whenever the native list redraws, which we
	 * never trigger ourselves — so seeing it while our tab is active means
	 * the user clicked a native tab/entry, i.e. navigated away from ours.
	 */
	void onCollectionDrawList()
	{
		if (!clogOpen || !tabActive)
		{
			return;
		}
		tabActive = false;
		setBossListHidden(false);
		clearPlaceholder();
		injectTab();
	}

	private void injectTab()
	{
		Widget tabs = client.getWidget(ComponentID.COLLECTION_LOG_TABS);
		if (tabs == null)
		{
			return;
		}

		// Re-space the 5 native tabs to fit a 6th. While ours is active, force theirs to the
		// unselected sprite; otherwise leave native selection alone and only reposition.
		Widget[] natives = tabs.getStaticChildren();
		if (natives != null)
		{
			for (int i = 0; i < natives.length; i++)
			{
				layoutTab(natives[i], i * BingoClogIds.TAB_STEP, BingoClogIds.TAB_WIDTH,
					tabActive ? Boolean.FALSE : null, null, false);
			}
		}

		Widget tab = findOwnTab(tabs);
		if (tab == null)
		{
			tab = tabs.createChild(-1, WidgetType.LAYER);
			tab.setName(BingoClogIds.BINGO_TAB_NAME);
			tab.setHasListener(true);
			tab.setAction(0, "View");
			tab.setOnOpListener((JavaScriptCallback) this::onTabClicked);
		}
		layoutTab(tab, BingoClogIds.BINGO_TAB_INDEX * BingoClogIds.TAB_STEP, BingoClogIds.TAB_WIDTH,
			tabActive ? Boolean.TRUE : Boolean.FALSE, "Bingo", true);
		// Make our tab's slices render exactly like Jagex's by copying their render flags.
		if (natives != null && natives.length > 0)
		{
			copyTabRenderFlags(natives[0], tab);
		}
		tabs.revalidate();
	}

	private Widget findOwnTab(Widget tabs)
	{
		Widget[] dyn = tabs.getDynamicChildren();
		if (dyn != null)
		{
			for (Widget c : dyn)
			{
				if (c != null && BingoClogIds.BINGO_TAB_NAME.equals(c.getName()))
				{
					return c;
				}
			}
		}
		return null;
	}

	private void onTabClicked(ScriptEvent event)
	{
		tabActive = true;
		setBossListHidden(true);
		injectTab();
		showPlaceholder();
	}

	/**
	 * Lays out a clog tab as a 3-slice sprite (left cap / stretched middle / right cap) plus a
	 * centred label. {@code selected} null leaves the background sprites untouched (native
	 * selection); true/false forces selected/unselected. {@code ensure} creates our own tab's 4
	 * children the first time.
	 */
	private void layoutTab(Widget tab, int x, int w, Boolean selected, String label, boolean ensure)
	{
		if (tab == null)
		{
			return;
		}
		place(tab, x, 0, w, BingoClogIds.TAB_HEIGHT);

		Widget[] kids = tab.getDynamicChildren();
		Widget cap0, mid, cap1, text;
		if (ensure && (kids == null || kids.length < 4))
		{
			cap0 = tab.createChild(-1, WidgetType.GRAPHIC);
			mid = tab.createChild(-1, WidgetType.GRAPHIC);
			cap1 = tab.createChild(-1, WidgetType.GRAPHIC);
			text = tab.createChild(-1, WidgetType.TEXT);
		}
		else if (kids != null && kids.length >= 4)
		{
			cap0 = kids[0];
			mid = kids[1];
			cap1 = kids[2];
			text = kids[3];
		}
		else
		{
			tab.revalidate();
			return;
		}

		int overlap = 4;
		place(mid, BingoClogIds.TAB_CAP_W - overlap, 0, w - 2 * (BingoClogIds.TAB_CAP_W - overlap), BingoClogIds.TAB_HEIGHT);
		place(cap0, 0, 0, BingoClogIds.TAB_CAP_W, BingoClogIds.TAB_HEIGHT);
		place(cap1, w - BingoClogIds.TAB_CAP_W, 0, BingoClogIds.TAB_CAP_W, BingoClogIds.TAB_HEIGHT);

		if (selected != null)
		{
			int cap = selected ? BingoClogIds.TAB_CAP_SELECTED : BingoClogIds.TAB_CAP_UNSELECTED;
			int m = selected ? BingoClogIds.TAB_MID_SELECTED : BingoClogIds.TAB_MID_UNSELECTED;
			mid.setSpriteId(m);
			cap0.setSpriteId(cap);
			cap1.setSpriteId(cap);
		}

		if (label != null)
		{
			text.setText(label);
			text.setTextColor(0xFF9040);
			text.setFontId(495);
			text.setTextShadowed(true);
		}
		place(text, 0, 0, w, BingoClogIds.TAB_HEIGHT);
		text.setXTextAlignment(1);
		text.setYTextAlignment(1);

		cap0.revalidate();
		mid.revalidate();
		cap1.revalidate();
		text.revalidate();
		tab.revalidate();
	}

	/** Copies sprite-render flags (tiling/border/flip) from a native tab's 3 slices onto ours. */
	private void copyTabRenderFlags(Widget fromTab, Widget toTab)
	{
		Widget[] from = fromTab.getDynamicChildren();
		Widget[] to = toTab.getDynamicChildren();
		if (from == null || to == null)
		{
			return;
		}
		int n = Math.min(3, Math.min(from.length, to.length));
		for (int i = 0; i < n; i++)
		{
			if (from[i] == null || to[i] == null)
			{
				continue;
			}
			to[i].setSpriteTiling(from[i].getSpriteTiling());
			to[i].setBorderType(from[i].getBorderType());
			to[i].setFlippedVertically(from[i].isFlippedVertically());
			to[i].setFlippedHorizontally(from[i].isFlippedHorizontally());
			to[i].revalidate();
		}
	}

	/** Positions + sizes a widget in absolute coordinates — native clog widgets default to relative modes, so both must be forced. */
	private static void place(Widget w, int x, int y, int width, int height)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		w.setWidthMode(WidgetSizeMode.ABSOLUTE);
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setOriginalX(x);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
	}

	private Widget contentContainer()
	{
		Widget tabs = client.getWidget(ComponentID.COLLECTION_LOG_TABS);
		return tabs == null ? null : tabs.getParent();
	}

	private void setBossListHidden(boolean hidden)
	{
		Widget container = contentContainer();
		if (container == null)
		{
			return;
		}
		Widget[] s = container.getStaticChildren();
		if (s != null && s.length > BingoClogIds.LEFT_LIST_CHILD && s[BingoClogIds.LEFT_LIST_CHILD] != null)
		{
			s[BingoClogIds.LEFT_LIST_CHILD].setHidden(hidden);
		}
	}

	private void showPlaceholder()
	{
		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		clearPlaceholderIn(items);
		Widget text = items.createChild(-1, WidgetType.TEXT);
		text.setName(PLACEHOLDER_WIDGET_NAME);
		text.setText("Bingo board coming soon here — check the sidebar panel for now.");
		text.setTextColor(0xFFFFFF);
		text.setFontId(495);
		text.setXTextAlignment(1);
		text.setYTextAlignment(1);
		place(text, 0, 0, 400, 60);
		text.revalidate();
		items.revalidate();
	}

	private void clearPlaceholder()
	{
		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items != null)
		{
			clearPlaceholderIn(items);
		}
	}

	private void clearPlaceholderIn(Widget items)
	{
		Widget[] dyn = items.getDynamicChildren();
		if (dyn == null)
		{
			return;
		}
		for (Widget c : dyn)
		{
			if (c != null && PLACEHOLDER_WIDGET_NAME.equals(c.getName()))
			{
				c.setHidden(true);
			}
		}
	}
}
