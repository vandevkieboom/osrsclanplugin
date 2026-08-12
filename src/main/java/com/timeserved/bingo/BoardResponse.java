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
		public String name;
		public int requiredCount;
		public int approvedCount;
		public int pendingCount;
		public List<Integer> itemIds;

		public List<Integer> getItemIds()
		{
			return itemIds == null ? Collections.emptyList() : itemIds;
		}

		/**
		 * Whether this tile still has room for another proof. Mirrors the
		 * server's own rule (api/_lib/board.ts): approved and pending proofs
		 * both count towards the requirement, so a tile awaiting review isn't
		 * submitted again.
		 */
		public boolean needsMoreProof()
		{
			return approvedCount + pendingCount < requiredCount;
		}
	}
}
