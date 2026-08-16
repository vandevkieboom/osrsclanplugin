package com.timeserved.bingo;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Persists bingo drop proof submissions that failed to send to disk, so a
 * client restart doesn't silently lose them — the previous in-memory-only
 * retry queue was wiped on every restart, which means losing the only
 * evidence of something that actually happened in game, with no way to
 * reconstruct it afterwards.
 */
@Slf4j
@Singleton
public class PendingSubmissionStore
{
	private static final File PENDING_DIR = new File(RuneLite.RUNELITE_DIR, "timeserved-bingo-pending");

	// Garbage-collect anything older than this. Prevents unbounded disk
	// growth if retries keep failing (e.g. the site URL is simply wrong)
	// and nobody notices for a long time.
	private static final long MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7);

	private final Gson gson;

	@Inject
	public PendingSubmissionStore(Gson gson)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
	}

	/** One persisted drop proof, awaiting retry. */
	public static class PendingItem
	{
		public String id;
		public long timestamp;

		public String tileId;
		public String tileName;
		public Integer itemId;
		public String itemName;
		public String screenshotFile;
	}

	private void init()
	{
		if (!PENDING_DIR.exists())
		{
			PENDING_DIR.mkdirs();
		}
	}

	/** Persists a failed drop submission (screenshot + tile/item details) to disk. Returns null if the write failed. */
	public PendingItem saveProof(String tileId, String tileName, int itemId, String itemName, byte[] png)
	{
		init();
		PendingItem item = new PendingItem();
		item.timestamp = System.currentTimeMillis();
		item.id = "proof-" + tileId + "-" + item.timestamp;
		item.tileId = tileId;
		item.tileName = tileName;
		item.itemId = itemId;
		item.itemName = itemName;
		item.screenshotFile = item.id + ".png";

		File pngFile = new File(PENDING_DIR, item.screenshotFile);
		try (FileOutputStream fos = new FileOutputStream(pngFile))
		{
			fos.write(png);
		}
		catch (IOException e)
		{
			log.warn("Failed to save pending proof screenshot: {}", e.getMessage());
			return null;
		}

		if (!writeJson(item))
		{
			pngFile.delete();
			return null;
		}
		return item;
	}

	private boolean writeJson(PendingItem item)
	{
		File jsonFile = new File(PENDING_DIR, item.id + ".json");
		try (Writer w = new FileWriter(jsonFile))
		{
			gson.toJson(item, w);
			return true;
		}
		catch (IOException e)
		{
			log.warn("Failed to save pending submission metadata: {}", e.getMessage());
			return false;
		}
	}

	/** Loads everything persisted from a previous session, pruning anything past MAX_AGE_MILLIS as it goes. */
	public List<PendingItem> loadAll()
	{
		init();
		List<PendingItem> result = new ArrayList<>();
		File[] jsonFiles = PENDING_DIR.listFiles((dir, name) -> name.endsWith(".json"));
		if (jsonFiles == null)
		{
			return result;
		}

		long now = System.currentTimeMillis();
		for (File f : jsonFiles)
		{
			try (Reader r = new FileReader(f))
			{
				PendingItem item = gson.fromJson(r, PendingItem.class);
				if (item == null)
				{
					continue;
				}
				if (now - item.timestamp > MAX_AGE_MILLIS)
				{
					log.debug("Pruning expired pending submission {} ({} days old)",
						item.id, TimeUnit.MILLISECONDS.toDays(now - item.timestamp));
					remove(item);
					continue;
				}
				result.add(item);
			}
			catch (Exception e)
			{
				log.warn("Failed to read pending submission {}: {}", f.getName(), e.getMessage());
			}
		}
		return result;
	}

	/** Reads back a proof item's screenshot bytes, or null if missing/unreadable. */
	public byte[] readScreenshot(PendingItem item)
	{
		if (item.screenshotFile == null)
		{
			return null;
		}
		try
		{
			return Files.readAllBytes(new File(PENDING_DIR, item.screenshotFile).toPath());
		}
		catch (IOException e)
		{
			log.warn("Failed to read pending screenshot {}: {}", item.screenshotFile, e.getMessage());
			return null;
		}
	}

	/** Deletes a persisted item (and its screenshot, if it has one) — call once it's been submitted successfully, or given up on. */
	public void remove(PendingItem item)
	{
		if (item.screenshotFile != null)
		{
			new File(PENDING_DIR, item.screenshotFile).delete();
		}
		new File(PENDING_DIR, item.id + ".json").delete();
	}
}
