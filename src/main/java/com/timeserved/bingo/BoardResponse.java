package com.timeserved.bingo;

import java.util.Collections;
import java.util.List;

/**
 * Mirrors the JSON returned by GET /api/board on the clan site.
 *
 * <p>Only the fields this plugin needs are declared; Gson ignores the rest.
 *
 * <p>Note that ids arrive as strings, not numbers: they're Postgres bigints,
 * which the site's driver serialises as strings. Parsing them as longs here
 * would silently fail, so they're kept as strings and passed straight back to
 * the API.
 */
public class BoardResponse
{
	public String myTeamId;
	public List<Team> teams;
	public Config config;

	/** Only the fields this plugin reads out of the board's event config. */
	public static class Config
	{
		/** The board is always size x size — needed to lay out the panel's grid to match. */
		public int size;

		/**
		 * Anti-cheat watermark for manually-taken screenshots — only present
		 * when this request was authenticated (see getRequestUser in
		 * api/board.ts), which a plugin request always is once a key is set.
		 */
		public String verificationCode;
	}

	public List<Team> getTeams()
	{
		return teams == null ? Collections.emptyList() : teams;
	}

	/** The caller's own team, or null if they aren't on one. */
	public Team findMyTeam()
	{
		if (myTeamId == null)
		{
			return null;
		}
		for (Team team : getTeams())
		{
			if (myTeamId.equals(team.id))
			{
				return team;
			}
		}
		return null;
	}

	public static class Team
	{
		public String id;
		public String name;
		public List<Tile> tiles;

		public List<Tile> getTiles()
		{
			return tiles == null ? Collections.emptyList() : tiles;
		}
	}

	public static class Tile
	{
		public String tileId;

		/** This tile's slot on the size x size board — 0-indexed, row-major. */
		public int position;

		public String name;
		public int requiredCount;
		public int approvedCount;
		public int pendingCount;
		public List<Integer> itemIds;

		/**
		 * "item" (the default) is the proof/review tile this plugin has always
		 * handled; "xp"/"kc" is a team-combined total this plugin reports
		 * readings toward directly (see {@link BingoApiClient#reportProgress})
		 * — those tiles are never in {@code itemIds} and never take a
		 * screenshot, they're just watched and reported.
		 */
		public String goalKind;

		/** The skill or boss name to watch, exactly as the server has it configured. */
		public String goalKey;

		/** XP/kill-count threshold for the team total; null for item tiles. */
		public Long goalTarget;

		/** Server-computed team total so far; null for item tiles. */
		public Long teamProgress;

		public List<Integer> getItemIds()
		{
			return itemIds == null ? Collections.emptyList() : itemIds;
		}

		public boolean isXpGoal()
		{
			return "xp".equals(goalKind);
		}

		public boolean isKcGoal()
		{
			return "kc".equals(goalKind);
		}

		/**
		 * Whether this tile still has room for another proof. Mirrors the
		 * server's own rule (api/_lib/board.ts): approved and pending proofs
		 * both count towards the requirement, so a tile awaiting review isn't
		 * submitted again. Only meaningful for item tiles — xp/kc tiles have
		 * no proofs at all.
		 */
		public boolean needsMoreProof()
		{
			return approvedCount + pendingCount < requiredCount;
		}
	}
}
