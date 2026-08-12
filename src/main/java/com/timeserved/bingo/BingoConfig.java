package com.timeserved.bingo;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BingoConfig.GROUP)
public interface BingoConfig extends Config
{
	String GROUP = "timeservedbingo";

	@ConfigSection(
		name = "Connection",
		description = "Links this client to your account on the clan site",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigItem(
		keyName = "apiKey",
		name = "Plugin key",
		description = "Generate this on the clan site under Settings -> RuneLite plugin keys, then paste it here."
			+ "<br><br><b>Privacy warning:</b> while this plugin is enabled and a key is set, receiving a drop that"
			+ " matches one of your team's bingo tiles will upload a screenshot of your game client, along with the"
			+ " matched item id, to timeserved.vercel.app. Leave the key empty to disable all uploads.",
		secret = true,
		section = connectionSection,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigSection(
		name = "Behaviour",
		description = "What the plugin does when it finds a match",
		position = 2
	)
	String behaviourSection = "behaviour";

	@ConfigItem(
		keyName = "notifyOnSubmit",
		name = "Chat message on submit",
		description = "Print a game chat message whenever a proof is submitted, or fails to submit.",
		section = behaviourSection,
		position = 3
	)
	default boolean notifyOnSubmit()
	{
		return true;
	}

	@ConfigSection(
		name = "Overlays",
		description = "On-screen highlights and the event verification watermark",
		position = 4
	)
	String overlaySection = "overlays";

	@ConfigItem(
		keyName = "highlightGroundItems",
		name = "Loot beam on bingo drops",
		description = "Show a loot beam over ground items that would satisfy one of your team's tiles.",
		section = overlaySection,
		position = 5
	)
	default boolean highlightGroundItems()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "groundItemHighlightColor",
		name = "Beam color",
		description = "Color of the loot beam over matching ground items.",
		section = overlaySection,
		position = 6
	)
	default Color groundItemHighlightColor()
	{
		return new Color(255, 215, 0, 180);
	}

	@ConfigItem(
		keyName = "showVerificationOverlay",
		name = "Show verification watermark",
		description = "Show the event's codephrase and a live timestamp on-screen, so a manually-taken"
			+ " screenshot can be tied to the live event.",
		section = overlaySection,
		position = 7
	)
	default boolean showVerificationOverlay()
	{
		return true;
	}
}
