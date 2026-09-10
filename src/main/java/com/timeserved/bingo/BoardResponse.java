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

	/**
	 * The change marker this board render corresponds to - echoed back by the
	 * site so the plugin can record what it actually received rather than what
	 * it expected. See BingoPlugin#refreshBoard: filing a cached, slightly
	 * older board under the newer stamp the poll reported would leave it stuck
	 * one change behind with nothing to trigger a correction.
	 */
	public String boardChangedAt;
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
	 * (see BingoApiClient#fetchPluginPoll) rather than here, since that
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
		/**
		 * The server's own "would I accept another proof for this tile"
		 * verdict, or null talking to a site that predates it.
		 *
		 * <p>Boxed deliberately: a primitive would default to false against an
		 * older server and silently switch auto-submission off entirely, which
		 * is far worse than the problem this field exists to fix.
		 */
		public Boolean acceptsMoreProof;

		public boolean needsMoreProof()
		{
			// The server's answer wins when it gives one. It counts PENDING
			// proofs toward a tile's requirement, which the status below does
			// not - so a tile at its limit awaiting review reads as "pending"
			// here while the server refuses every further submission for it.
			// That mismatch cost a real event a wasted screenshot, upload and
			// crab dance per drop, plus a misleading "that tile is already
			// complete" in chat each time.
			if (acceptsMoreProof != null)
			{
				return acceptsMoreProof;
			}
			// Keyed off the server's own completion verdict rather than
			// re-deriving one from counts. requiredCount is the flat
			// "how many proofs" field, and it stops meaning anything the
			// moment a tile uses item_requirements (AND/OR item sets - see
			// osrsclan/db/schema.sql): a tile needing four specific items
			// might still carry requiredCount 1 from before those were set,
			// so the count comparison went false after the very first drop
			// and auto-submission silently died for the rest of the event.
			// `status` is computed server-side from the real rule, whichever
			// rule that tile uses.
			return !"approved".equals(status);
		}
	}
}
