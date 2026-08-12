package com.timeserved.bingo;

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
}
