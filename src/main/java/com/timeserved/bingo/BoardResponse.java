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
	public Config config;
	public List<Team> teams;

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

	/**
	 * The bingo event's own name/size - used for the sidebar panel's header.
	 * The "is bingo active" flag lives on a separate, dedicated endpoint
	 * (see BingoApiClient#fetchBingoStatus) rather than here, since that
	 * needs to be cheap enough to poll every minute regardless of activity,
	 * unlike this response.
	 */
	public static class Config
	{
		public String name;
		public int size;
	}

	public static class Team
	{
		public String id;
		public String name;
		/** Hex string like "#e8574a"; null falls back to a neutral colour. */
		public String accentColor;
		public int completeCount;
		public int totalTiles;
		public int pct;
		public boolean isLeading;
		public List<Tile> tiles;

		public List<Tile> getTiles()
		{
			return tiles == null ? Collections.emptyList() : tiles;
		}
	}

	public static class Tile
	{
		public String tileId;
		public int position;
		public String name;
		public String iconUrl;
		public int requiredCount;
		public int approvedCount;
		public int pendingCount;
		/** "approved" | "pending" | "rejected" | "none" - server-computed, mirrors the website's own status. */
		public String status;
		public List<Integer> itemIds;

		/**
		 * "item" (the default) is the proof/review tile this plugin submits
		 * screenshots for; "xp"/"kc" is a team-combined total tracked entirely
		 * server-side from the clan's hiscores (see osrsclan/api/_lib/board.ts)
		 * - those tiles are never in {@code itemIds} and never take a
		 * screenshot, they're purely display: teamProgress below is all the
		 * plugin needs to show for them.
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
		 * submitted again. Only meaningful for item tiles - xp/kc tiles have
		 * no proofs at all.
		 */
		public boolean needsMoreProof()
		{
			return approvedCount + pendingCount < requiredCount;
		}
	}
}
