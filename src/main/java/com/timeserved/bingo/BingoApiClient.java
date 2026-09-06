package com.timeserved.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the Time Served clan site.
 *
 * <p>All requests are asynchronous: they're triggered from game event handlers,
 * which run on the client thread, and blocking that would stutter the game.
 *
 * <p>Every URL this plugin ever connects to is either this hardcoded
 * {@code BASE_URL} or, for tile icons, RuneLite's own item sprite cache via
 * {@code ItemManager} (see {@code BingoPanel#loadIconInto}) - never a URL
 * taken from an API response. An earlier version of the tile-icon path had
 * the site send a per-tile {@code iconUrl} that the panel fetched directly
 * with {@code ImageIO.read(new URL(...))}; Plugin Hub review flagged that as
 * exactly the pattern not allowed (a plugin dereferencing a URL supplied by
 * its own API response, rather than one from the user or baked into the
 * jar), so it was replaced with item-id-based icons from {@code ItemManager}
 * instead. Don't reintroduce a server-supplied URL for anything the plugin
 * fetches.
 */
@Slf4j
@Singleton
public class BingoApiClient
{
	private static final String BASE_URL = "https://timeserved.vercel.app";

	/**
	 * The site's API only exposes req.body as raw bytes when the request is
	 * declared as octet-stream, so the real image type travels in a query
	 * param instead (see submitPluginProof in api/board.ts).
	 */
	private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
	private static final String SCREENSHOT_CONTENT_TYPE = "image/jpeg";
	private static final String LEGACY_SCREENSHOT_CONTENT_TYPE = "image/png";

	/**
	 * Proofs are JPEG now (see BingoPlugin#encodeProof), but the on-disk retry
	 * queue can still hold PNG bytes saved by an earlier version of the plugin
	 * and never successfully sent. Declaring those as JPEG would store a blob
	 * whose content type doesn't match its bytes, which browsers may then
	 * refuse to render - a silently unviewable proof. Sniffing the PNG magic
	 * number costs nothing and keeps those retries correct.
	 */
	private static String contentTypeOf(byte[] image)
	{
		boolean isPng = image.length >= 8
			&& (image[0] & 0xFF) == 0x89
			&& image[1] == 'P' && image[2] == 'N' && image[3] == 'G';
		return isPng ? LEGACY_SCREENSHOT_CONTENT_TYPE : SCREENSHOT_CONTENT_TYPE;
	}

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public BingoApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Fetches the board: every team's tiles, item ids, progress and
	 * submissions.
	 *
	 * <p>Sent with no key, deliberately. This response is identical for every
	 * member and the site serves one cached copy of it to all of them, which
	 * is what stops a single person's drop costing one full board render per
	 * online member during an event. Adding an Authorization header here would
	 * be asking for a private copy of a public answer.
	 *
	 * <p>It therefore does not say which team is yours - {@link #fetchMyTeam}
	 * answers that separately, and rarely. See BingoPlugin#myTeamId.
	 */
	public void fetchBoard(boolean fresh, Consumer<BoardResponse> onSuccess, Consumer<String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/board");
		if (base == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		// view=plugin asks for the board with everything BoardResponse does
		// not read already stripped out - per-proof blob and avatar URLs above
		// all, which are most of the payload once an event has real
		// submissions on it and which this plugin was downloading and throwing
		// away on every refresh.
		HttpUrl.Builder builder = base.newBuilder().addQueryParameter("view", "plugin");
		if (fresh)
		{
			// The site caches this response briefly so that one change does not
			// cost one render per online member. That is right for a routine
			// refresh and wrong right after *this* player submitted something:
			// their own drop missing from their own board reads as a bug. A
			// unique query string gets an uncached answer for the handful of
			// refreshes that follow a real action.
			builder.addQueryParameter("fresh", Long.toString(System.currentTimeMillis()));
		}
		Request.Builder request = new Request.Builder().url(builder.build()).get();

		httpClient.newCall(request.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch bingo board", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						onError.accept(describeFailure(closeable, parseErrorBody(body)));
						return;
					}
					onSuccess.accept(gson.fromJson(body.charStream(), BoardResponse.class));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed bingo board response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/** Which team the plugin key's owner is on - mirrors GET /api/board?resource=my-team. */
	public static class MyTeam
	{
		/** Null when the key's owner hasn't been put on a team yet. */
		public String teamId;
	}

	/**
	 * Asks which team this plugin key belongs to.
	 *
	 * <p>The one genuinely per-member thing the board response used to carry,
	 * split out so the board itself can be one cached copy shared by everyone.
	 * Tiny, and asked for rarely - on startup, on a key change, and at most
	 * every half hour after that.
	 */
	public void fetchMyTeam(String apiKey, Consumer<MyTeam> onSuccess, Consumer<String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/board");
		if (base == null || apiKey.isEmpty())
		{
			onError.accept("Invalid API base URL");
			return;
		}

		HttpUrl url = base.newBuilder().addQueryParameter("resource", "my-team").build();
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + apiKey)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch team membership", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						onError.accept(describeFailure(closeable, parseErrorBody(body)));
						return;
					}
					MyTeam parsed = gson.fromJson(body.charStream(), MyTeam.class);
					onSuccess.accept(parsed == null ? new MyTeam() : parsed);
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed team membership response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/**
	 * Everything a bingo participant's once-a-minute tick needs, in one
	 * response.
	 *
	 * <p>This used to also carry clan broadcast and live-stream answers,
	 * merged in from what were three separate requests fired on the same
	 * tick, for every online member regardless of bingo participation -
	 * roughly 4,300 requests per member per day before anyone did anything at
	 * all, and across a clan this size that was the single biggest source of
	 * load on the site by a wide margin, exhausting the hosting plan's quotas.
	 * Broadcast and live-stream notifications were later removed from the
	 * plugin entirely rather than kept merged in, since the only remaining
	 * caller of this endpoint is someone with a plugin key set - i.e. an
	 * actual bingo participant - and neither feature had anything to do with
	 * bingo. See BingoPlugin#hasAnythingToPollFor.
	 *
	 * <p>Deliberately unauthenticated and identical for every caller, so the
	 * site can serve nearly all of these from its CDN without running any
	 * server code or touching its database at all. Don't add a key, a player
	 * name, or anything else per-member to this call - that would give every
	 * member their own cache entry, which is the same as having no cache.
	 */
	public void fetchPluginPoll(Consumer<PollResponse> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.parse(BASE_URL + "/api/plugin-poll");
		if (url == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		Request request = new Request.Builder().url(url).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to poll the clan site", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						onError.accept(describeFailure(closeable, parseErrorBody(body)));
						return;
					}
					PollResponse parsed = gson.fromJson(body.charStream(), PollResponse.class);
					if (parsed == null)
					{
						onError.accept("The clan site returned an empty response");
						return;
					}
					onSuccess.accept(parsed);
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed poll response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/** One combined tick's worth of state - mirrors GET /api/plugin-poll. */
	public static class PollResponse
	{
		/** Whether a bingo event is currently running. */
		public boolean bingoActive;

		/**
		 * How many seconds to wait before polling again. The same value for
		 * every member of the clan - see BingoPlugin#applyPollCadence for why
		 * that is deliberate: this carries clan-wide announcements, which must
		 * not arrive sooner for some members than others.
		 *
		 * <p>Set server-side rather than fixed in the plugin so it can be
		 * changed without a release - hosting quotas are monthly and hard, and
		 * plugin installs update whenever they feel like it. 0 or missing
		 * means "use the default". Callers must clamp it - see
		 * BingoPlugin#pollIntervalMillis.
		 */
		public int pollSeconds;

		/**
		 * Opaque marker that changes whenever anything on the board changes.
		 * Compare it against the value the board was last fetched with; only
		 * fetch the board again when it differs. Never parse it - its format
		 * is the server's business and may change.
		 */
		public String boardChangedAt;

		/**
		 * True when the site answered from a cached copy because its database
		 * was unreachable. The values above are then last-known rather than
		 * current, so callers should sit still rather than act on a change
		 * they can't trust.
		 */
		public boolean degraded;

		/**
		 * Team-combined xp/kc progress, as goal_kind:goal_key -> team id ->
		 * value. Example: {"xp:slayer": {"3": 1250000}}.
		 *
		 * <p>Carried on this tick so an xp/kc number can change without the
		 * board being declared stale. It used to arrive the other way: the
		 * server bumped boardChangedAt whenever progress moved, so every
		 * participant re-downloaded the entire board - every tile, team and
		 * submission - every two minutes for a whole event, to refresh one
		 * number. Progress is per *team*, not per member, so it is identical
		 * for everyone and rides along on the shared cached poll for free.
		 */
		public Map<String, Map<String, Long>> goalProgress;
	}

	/*
	 * fetchBoardState() / BoardState used to live here, wrapping
	 * GET /api/board?resource=status. Both were removed along with their only
	 * caller (BingoPlugin#checkBoardState): that endpoint answers from the same
	 * board_config row as /api/plugin-poll and carries the same bingoActive and
	 * boardChangedAt fields, so asking it on the same tick was a second CDN
	 * cache entry, a second origin invocation and a second database read for an
	 * answer the poll had already delivered.
	 */

	/**
	 * Uploads a screenshot as proof for a tile. The server re-checks that the
	 * item satisfies the tile and that the tile still needs proof, so a stale
	 * local board can't produce a bogus submission.
	 */
	public void submitProof(
		String apiKey,
		String tileId,
		int itemId,
		byte[] screenshot,
		Runnable onSuccess,
		Consumer<String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/board");
		if (base == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		HttpUrl url = base.newBuilder()
			.addQueryParameter("resource", "plugin-proof")
			.addQueryParameter("tileId", tileId)
			.addQueryParameter("itemId", Integer.toString(itemId))
			.addQueryParameter("contentType", contentTypeOf(screenshot))
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + apiKey)
			.post(RequestBody.create(OCTET_STREAM, screenshot))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to submit bingo proof", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (closeable.isSuccessful())
					{
						onSuccess.run();
						return;
					}
					onError.accept(describeFailure(closeable, parseErrorBody(body)));
				}
			}
		});
	}

	/** Result of a {@link #lookupRank} call - mirrors GET /api/runeprofile-proxy?resource=lookup-rank. */
	public static class RankLookupResult
	{
		public String rsn;

		/** The member's current WOM-group-role-derived rank, or null if unranked. */
		public String currentRank;

		/** The highest rank this account's RuneProfile data actually qualifies for, or null if none. */
		public String eligibleRank;

		public int overallSatisfied;
		public int overallTotal;

		/** The next tier up from eligibleRank, or null if already at the top (or no ranks exist). */
		public String nextRank;

		/** How many more items are needed for nextRank; null when nextRank is null. */
		public Integer neededForNextRank;

		/** Up to 8 item names still missing for nextRank - never null, just possibly empty. */
		public List<String> missingItemNames;
	}

	/**
	 * Runs the exact same rank-progress check as the "Auto-Verify" button on
	 * the clan site's Clan Ranks page, server-side, for the given RSN. Only
	 * ever reports what rank someone qualifies for - there's no way to
	 * actually apply an in-game clan rank from here or anywhere else.
	 *
	 * <p>onError's second argument is the server's machine-readable failure
	 * {@code reason} when it sent one (currently only "not-on-runeprofile"),
	 * or null for anything else (including a plain network failure) - lets
	 * callers like the RuneProfile-sync reminder act on a specific failure
	 * without string-matching the human-readable message.
	 *
	 * <p>No plugin key: this is a read of data that's already fully public
	 * on the clan site with no login needed, so unlike the bingo-specific
	 * calls above, there's nothing here for a key to gate.
	 */
	public void lookupRank(String rsn, Consumer<RankLookupResult> onSuccess, BiConsumer<String, String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/runeprofile-proxy");
		if (base == null)
		{
			onError.accept("Invalid API base URL", null);
			return;
		}

		HttpUrl url = base.newBuilder()
			.addQueryParameter("resource", "lookup-rank")
			.addQueryParameter("rsn", rsn)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to look up rank for {}", rsn, e);
				onError.accept("Could not reach the clan site", null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						ErrorBody err = parseErrorBody(body);
						onError.accept(describeFailure(closeable, err), err.reason);
						return;
					}
					onSuccess.accept(gson.fromJson(body.charStream(), RankLookupResult.class));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed rank lookup response", e);
					onError.accept("The clan site returned an unexpected response", null);
				}
			}
		});
	}

	/** Result of a {@link #checkClanRequirement} call - mirrors GET /api/runeprofile-proxy?resource=clan-req. */
	public static class ClanRequirementResult
	{
		public String rsn;

		/** Whether this account satisfies the clan's hard gear/kc requirement. */
		public boolean meets;

		/** Which of the three checks passed (e.g. "Twisted Bow"), or null when meets is false. */
		public String reason;
	}

	/**
	 * Checks the clan's hard gear/kc requirement for the given RSN - the
	 * same three-way check ("6+ Crystal Armour Seeds + an Enhanced Crystal
	 * Weapon Seed", "800+ Corrupted Gauntlet kc", or "a Twisted Bow") the
	 * site itself runs on its Clan Rankings page. Deliberately separate from
	 * {@link #lookupRank}: that reports rank-tier eligibility, this reports
	 * whether a much stricter, single gate is met - two different
	 * questions, so two different commands/endpoints rather than one
	 * overloaded reply.
	 *
	 * <p>No plugin key: same public-data reasoning as lookupRank.
	 */
	public void checkClanRequirement(String rsn, Consumer<ClanRequirementResult> onSuccess, BiConsumer<String, String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/runeprofile-proxy");
		if (base == null)
		{
			onError.accept("Invalid API base URL", null);
			return;
		}

		HttpUrl url = base.newBuilder()
			.addQueryParameter("resource", "clan-req")
			.addQueryParameter("rsn", rsn)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to check clan requirement for {}", rsn, e);
				onError.accept("Could not reach the clan site", null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						ErrorBody err = parseErrorBody(body);
						onError.accept(describeFailure(closeable, err), err.reason);
						return;
					}
					onSuccess.accept(gson.fromJson(body.charStream(), ClanRequirementResult.class));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed clan requirement response", e);
					onError.accept("The clan site returned an unexpected response", null);
				}
			}
		});
	}

	/** One currently-live stream - mirrors GET /api/twitch-live's LiveStream shape. */
	public static class LiveStream
	{
		public String username;
		public String displayName;
		public String game;
		public String title;
		public int viewers;
	}

	private static class LiveStreamsResponse
	{
		List<LiveStream> streams;
	}

	/**
	 * Which of the clan's known Twitch channels (site-configured, not
	 * plugin-configured) are live right now. Public data, same as the
	 * site's own homepage widget - no plugin key needed or sent.
	 */
	public void fetchLiveStreams(Consumer<List<LiveStream>> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.parse(BASE_URL + "/api/twitch-live");
		if (url == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		Request request = new Request.Builder().url(url).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch live streams", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						onError.accept(describeFailure(closeable, parseErrorBody(body)));
						return;
					}
					LiveStreamsResponse parsed = gson.fromJson(body.charStream(), LiveStreamsResponse.class);
					onSuccess.accept(parsed.streams == null ? Collections.emptyList() : parsed.streams);
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed live streams response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/** One participant's progress in a SOTW/BOTW competition. */
	public static class EventParticipation
	{
		public Player player;

		public static class Player
		{
			public String displayName;
		}

		public Progress progress;

		public static class Progress
		{
			public long gained;
		}
	}

	/** One SOTW/BOTW competition, with every participant's progress. */
	public static class EventCompetition
	{
		public String title;

		/** The WOM metric key, e.g. "slayer" or "vorkath" - display name, not this. */
		public String metric;

		/** "xp" or "kc" - decided server-side from the one skill list the site
		 *  and this plugin both already have to agree on for tile icons,
		 *  rather than this plugin keeping a second copy of it. */
		public String metricType;

		/** ISO instant; only meaningful for an ongoing or upcoming competition. */
		public String startsAt;

		/** ISO instant; only meaningful for an ongoing competition. */
		public String endsAt;

		public List<EventParticipation> participations;
	}

	/** Response shape for GET /api/wom-proxy?type=event-summary. */
	public static class EventSummaryResponse
	{
		/** "ongoing", "upcoming", or "none". */
		public String status;

		/** Every competition matching {@link #status} - almost always one, but
		 *  can be more than one if a BOTW and SOTW happen to overlap, and is
		 *  empty when status is "none". */
		public List<EventCompetition> competitions;
	}

	/**
	 * The RuneLite plugin's `!event` chat command: whichever SOTW/BOTW
	 * competition(s) the clan currently has ongoing (or the next upcoming one,
	 * if none are), with every participant's progress. Public data, same
	 * reasoning as fetchLiveStreams above - no plugin key needed or sent.
	 */
	public void fetchEventSummary(Consumer<EventSummaryResponse> onSuccess, Consumer<String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/wom-proxy");
		if (base == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}
		HttpUrl url = base.newBuilder().addQueryParameter("type", "event-summary").build();
		Request request = new Request.Builder().url(url).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch event summary", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						onError.accept(describeFailure(closeable, parseErrorBody(body)));
						return;
					}
					EventSummaryResponse parsed = gson.fromJson(body.charStream(), EventSummaryResponse.class);
					if (parsed == null)
					{
						onError.accept("The clan site returned an empty response");
						return;
					}
					if (parsed.competitions == null)
					{
						parsed.competitions = Collections.emptyList();
					}
					onSuccess.accept(parsed);
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed event summary response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/**
	 * Where the clan-wide broadcast lives - a small public JSON file on Vercel
	 * Blob, not an endpoint on the clan site. This is the one deliberate
	 * exception to this class's usual "every request goes to BASE_URL" rule
	 * (see the class doc): it is hardcoded, not a URL taken from any API
	 * response, so it doesn't run into the Plugin Hub review concern that doc
	 * describes - the same reasoning that already lets this plugin hardcode
	 * static.runelite.net and oldschool.runescape.wiki for item/skill icons.
	 *
	 * <p>Reading it this way, straight from Blob's CDN, rather than through a
	 * clan-site endpoint, is what makes checking it every single minute for
	 * every one of 100+ installs cost nothing: no Vercel function runs for
	 * this at all, so it's unaffected by how many people are checking or how
	 * often. See osrsclan's api/_lib/broadcast.ts for the write side and why
	 * this replaces the old Postgres-column version of the same feature.
	 *
	 * <p>If the clan's Blob store is ever recreated, this host changes and
	 * would need updating here - a wrong host just fails every check
	 * silently (see fetchBroadcast), it can't crash anything.
	 */
	private static final String BROADCAST_URL =
		"https://o3vcuwswsm0xzkof.public.blob.vercel-storage.com/broadcast.json";

	public static class Broadcast
	{
		public String message;
		public String updatedAt;
	}

	/**
	 * Checks the clan-wide broadcast. Deliberately silent on any failure
	 * (network error, bad host, malformed response) rather than surfacing an
	 * error anywhere - this runs on every scheduled tick for every install
	 * regardless of whether anyone is looking, unlike an explicit chat
	 * command where a player is actively waiting on a reply.
	 */
	public void fetchBroadcast(Consumer<Broadcast> onSuccess)
	{
		HttpUrl url = HttpUrl.parse(BROADCAST_URL);
		if (url == null)
		{
			return;
		}
		Request request = new Request.Builder().url(url).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch broadcast", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						log.debug("Broadcast fetch returned {}", closeable.code());
						return;
					}
					Broadcast parsed = gson.fromJson(body.charStream(), Broadcast.class);
					if (parsed != null)
					{
						onSuccess.accept(parsed);
					}
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed broadcast response", e);
				}
			}
		});
	}

	/** A parsed {"error": "...", "reason": "..."} body - reason is usually absent. */
	private static class ErrorBody
	{
		String error;
		String reason;
	}

	private ErrorBody parseErrorBody(ResponseBody body)
	{
		ErrorBody result = new ErrorBody();
		if (body != null)
		{
			try
			{
				JsonObject json = gson.fromJson(body.charStream(), JsonObject.class);
				if (json != null)
				{
					if (json.has("error"))
					{
						result.error = json.get("error").getAsString();
					}
					if (json.has("reason"))
					{
						result.reason = json.get("reason").getAsString();
					}
				}
			}
			catch (JsonSyntaxException | IllegalStateException e)
			{
				log.debug("Non-JSON error response from clan site", e);
			}
		}
		return result;
	}

	/**
	 * Surfaces the server's own {"error": "..."} message when there is one, so
	 * the player sees "That tile is already complete" rather than "HTTP 409".
	 */
	private String describeFailure(Response response, ErrorBody err)
	{
		if (err.error != null)
		{
			return err.error;
		}

		if (response.code() == 401)
		{
			return "Your plugin key was rejected - generate a new one on the clan site";
		}
		return "The clan site returned an error (" + response.code() + ")";
	}
}
