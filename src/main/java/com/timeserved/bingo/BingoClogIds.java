package com.timeserved.bingo;

/**
 * Collection-log interface geometry/sprite ids needed to inject a tab into
 * it. These are exactly the values documented in AhmedFathy2001/anvil-plugin
 * (BSD 2-Clause License, Copyright (c) 2026 AhmedFathy2001), specifically
 * its {@code ClogIds.java}, which measured them from a live client via
 * RuneLite's widget debug dump — see that file's own comment for the
 * provenance and RuneLite version they were verified against. Reused here
 * rather than re-discovered from scratch since they're exact pixel/sprite
 * values, not something that can be approximated.
 *
 * <p>Like any collection-log interface id, these are built by Jagex's own
 * CS2 scripts and can shift on a game update — if the injected tab ever
 * looks wrong (misaligned, wrong sprite), this file plus
 * {@link BingoClogTabController} are the only places that need touching.
 */
final class BingoClogIds
{
	private BingoClogIds()
	{
	}

	// Native tab row geometry: COLLECTION_LOG_TABS holds 5 static tabs at
	// width 97 / step 100. Re-spacing all of them to width 80 / step 83
	// makes room for a 6th tab.
	static final int TAB_HEIGHT = 20;
	static final int TAB_STEP = 83;
	static final int TAB_WIDTH = 80;
	static final int BINGO_TAB_INDEX = 5;
	static final String BINGO_TAB_NAME = "TimeServedBingoTab";

	// Tab background is a 3-slice sprite (left cap / stretched middle /
	// right cap). A SELECTED tab uses 2283/2284, an UNSELECTED tab uses
	// 2285/2286.
	static final int TAB_CAP_SELECTED = 2283;
	static final int TAB_MID_SELECTED = 2284;
	static final int TAB_CAP_UNSELECTED = 2285;
	static final int TAB_MID_UNSELECTED = 2286;
	static final int TAB_CAP_W = 20;

	// Index of the native boss/category list among the shared content
	// container's static children — hidden while our tab is active.
	static final int LEFT_LIST_CHILD = 1;
}
