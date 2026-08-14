package com.timeserved.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
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
	private static final String SCREENSHOT_CONTENT_TYPE = "image/png";

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public BingoApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Fetches the board, including every tile's item ids and the caller's own
	 * team. The key is optional for reading, but without it the response has no
	 * myTeamId and the plugin can't tell which tiles are relevant.
	 */
	public void fetchBoard(String apiKey, Consumer<BoardResponse> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.parse(BASE_URL + "/api/board");
		if (url == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		Request.Builder request = new Request.Builder().url(url).get();
		if (!apiKey.isEmpty())
		{
			request.header("Authorization", "Bearer " + apiKey);
		}

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
			.addQueryParameter("contentType", SCREENSHOT_CONTENT_TYPE)
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

	/** Result of a {@link #lookupRank} call — mirrors GET /api/runeprofile-proxy?resource=lookup-rank. */
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

		/** Up to 8 item names still missing for nextRank — never null, just possibly empty. */
		public List<String> missingItemNames;
	}

	/**
	 * Runs the exact same rank-progress check as the "Auto-Verify" button on
	 * the clan site's Clan Ranks page, server-side, for the given RSN. Only
	 * ever reports what rank someone qualifies for — there's no way to
	 * actually apply an in-game clan rank from here or anywhere else.
	 *
	 * <p>onError's second argument is the server's machine-readable failure
	 * {@code reason} when it sent one (currently only "not-on-runeprofile"),
	 * or null for anything else (including a plain network failure) — lets
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

	/** One currently-live stream — mirrors GET /api/twitch-live's LiveStream shape. */
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
	 * site's own homepage widget — no plugin key needed or sent.
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

	/** Result of a {@link #fetchBroadcast} call — mirrors GET /api/runeprofile-proxy?resource=broadcast. */
	public static class BroadcastResult
	{
		public String message;

		/** ISO-8601 timestamp of the last admin broadcast, or null if none has ever been sent. */
		public String updatedAt;
	}

	/**
	 * The latest one-off message an admin has pushed out from the site's
	 * Board Config panel. Callers compare updatedAt against the last one
	 * they've already shown to tell a new broadcast from one already seen —
	 * this always returns the current message, not just new ones. Public,
	 * no plugin key: same reasoning as lookupRank above.
	 */
	public void fetchBroadcast(Consumer<BroadcastResult> onSuccess, Consumer<String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/runeprofile-proxy");
		if (base == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		HttpUrl url = base.newBuilder().addQueryParameter("resource", "broadcast").build();
		Request request = new Request.Builder().url(url).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch broadcast", e);
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
					onSuccess.accept(gson.fromJson(body.charStream(), BroadcastResult.class));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed broadcast response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/** A parsed {"error": "...", "reason": "..."} body — reason is usually absent. */
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
			return "Your plugin key was rejected — generate a new one on the clan site";
		}
		return "The clan site returned an error (" + response.code() + ")";
	}
}
