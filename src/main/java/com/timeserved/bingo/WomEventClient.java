package com.timeserved.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.time.Instant;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to Wise Old Man's own public API directly — deliberately kept separate from
 * {@link BingoApiClient}, whose class doc scopes it to "the Time Served clan site". Backs the
 * "Wise Old Man competitions" chat announcement only; no plugin key involved, since this never
 * touches the clan site at all.
 */
@Slf4j
@Singleton
class WomEventClient
{
	private static final String WOM_BASE_URL = "https://api.wiseoldman.net/v2";

	/**
	 * Same clan group id the site hardcodes (see clanrankings' WOM_GROUP_ID in src/constants.ts) —
	 * consistent with this plugin already being hardcoded to a single clan rather than
	 * configurable per-install.
	 */
	private static final int WOM_GROUP_ID = 22206;

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	WomEventClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/** One entry from GET /groups/{id}/competitions. */
	static class Competition
	{
		long id;
		String title;
		String metric;
		String startsAt;
		String endsAt;
	}

	/**
	 * The competition currently running right now (startsAt <= now <= endsAt), or null if none is —
	 * deliberately doesn't fall back to "upcoming" the way the site's own event-hiscores tab does,
	 * since an announcement should mean "this just started," not "one's coming up."
	 */
	void fetchOngoingCompetition(Consumer<Competition> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.parse(WOM_BASE_URL + "/groups/" + WOM_GROUP_ID + "/competitions");
		if (url == null)
		{
			onError.accept("Invalid WOM API URL");
			return;
		}
		url = url.newBuilder().addQueryParameter("limit", "20").build();

		Request request = new Request.Builder().url(url).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to fetch Wise Old Man competitions", e);
				onError.accept("Could not reach the Wise Old Man API");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closeable = response)
				{
					ResponseBody body = closeable.body();
					if (!closeable.isSuccessful() || body == null)
					{
						onError.accept("The Wise Old Man API returned an error (" + closeable.code() + ")");
						return;
					}
					Competition[] competitions = gson.fromJson(body.charStream(), Competition[].class);
					onSuccess.accept(findOngoing(competitions));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Malformed Wise Old Man competitions response", e);
					onError.accept("The Wise Old Man API returned an unexpected response");
				}
			}
		});
	}

	private static Competition findOngoing(Competition[] competitions)
	{
		if (competitions == null)
		{
			return null;
		}
		Instant now = Instant.now();
		for (Competition competition : competitions)
		{
			if (competition.startsAt == null || competition.endsAt == null)
			{
				continue;
			}
			try
			{
				Instant startsAt = Instant.parse(competition.startsAt);
				Instant endsAt = Instant.parse(competition.endsAt);
				if (!now.isBefore(startsAt) && !now.isAfter(endsAt))
				{
					return competition;
				}
			}
			catch (RuntimeException e)
			{
				// A malformed timestamp on one entry shouldn't stop the rest from being checked.
			}
		}
		return null;
	}
}
