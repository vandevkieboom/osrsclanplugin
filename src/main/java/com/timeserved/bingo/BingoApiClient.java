package com.timeserved.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
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
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
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
						onError.accept(describeFailure(closeable, body));
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
					onError.accept(describeFailure(closeable, body));
				}
			}
		});
	}

	/**
	 * Reports this member's current reading for a team-combined xp/kc goal
	 * (see {@link BoardResponse.Tile#goalKind}). The server tracks each
	 * member's own first-ever reading as their baseline and sums
	 * (latest − baseline) across the team, so this is safe to call
	 * repeatedly with the same or a higher value — there's no local state to
	 * get out of sync with.
	 */
	public void reportProgress(
		String apiKey,
		String goalKind,
		String goalKey,
		long value,
		Runnable onSuccess,
		Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.parse(BASE_URL + "/api/board");
		if (url == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		JsonObject body = new JsonObject();
		body.addProperty("goalKind", goalKind);
		body.addProperty("goalKey", goalKey);
		body.addProperty("value", value);

		Request request = new Request.Builder()
			.url(url.newBuilder().addQueryParameter("resource", "goal-progress").build())
			.header("Authorization", "Bearer " + apiKey)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to report bingo goal progress", e);
				onError.accept("Could not reach the clan site");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					if (closeable.isSuccessful())
					{
						onSuccess.run();
						return;
					}
					onError.accept(describeFailure(closeable, closeable.body()));
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
	}

	/**
	 * Runs the exact same rank-progress check as the "Auto-Verify" button on
	 * the clan site's Clan Ranks page, server-side, for the given RSN. Only
	 * ever reports what rank someone qualifies for — there's no way to
	 * actually apply an in-game clan rank from here or anywhere else.
	 */
	public void lookupRank(String apiKey, String rsn, Consumer<RankLookupResult> onSuccess, Consumer<String> onError)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + "/api/runeprofile-proxy");
		if (base == null)
		{
			onError.accept("Invalid API base URL");
			return;
		}

		HttpUrl url = base.newBuilder()
			.addQueryParameter("resource", "lookup-rank")
			.addQueryParameter("rsn", rsn)
			.build();

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
				log.debug("Failed to look up rank for {}", rsn, e);
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
						onError.accept(describeFailure(closeable, body));
						return;
					}
					onSuccess.accept(gson.fromJson(body.charStream(), RankLookupResult.class));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed rank lookup response", e);
					onError.accept("The clan site returned an unexpected response");
				}
			}
		});
	}

	/**
	 * Surfaces the server's own {"error": "..."} message when there is one, so
	 * the player sees "That tile is already complete" rather than "HTTP 409".
	 */
	private String describeFailure(Response response, ResponseBody body)
	{
		if (body != null)
		{
			try
			{
				JsonObject json = gson.fromJson(body.charStream(), JsonObject.class);
				if (json != null && json.has("error"))
				{
					return json.get("error").getAsString();
				}
			}
			catch (JsonSyntaxException | IllegalStateException e)
			{
				log.debug("Non-JSON error response from clan site", e);
			}
		}

		if (response.code() == 401)
		{
			return "Your plugin key was rejected — generate a new one on the clan site";
		}
		return "The clan site returned an error (" + response.code() + ")";
	}
}
