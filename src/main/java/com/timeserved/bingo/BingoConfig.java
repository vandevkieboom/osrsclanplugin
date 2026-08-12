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

	@Alpha
	@ConfigItem(
		keyName = "submitMessageColor",
		name = "Submit message color",
		description = "Color of the chat message printed when a proof is submitted, or fails to submit.",
		section = behaviourSection,
		position = 4
	)
	default Color submitMessageColor()
	{
		return new Color(0, 200, 83);
	}

	@ConfigItem(
		keyName = "playDropEmote",
		name = "Party emote on bingo drop",
		description = "Play the Party emote on yourself the moment a matching drop is detected. Purely a local"
			+ " rendering override (Actor.setAnimation, the same mechanism the game engine itself uses for your"
			+ " idle/walk animations) — it's never sent to the server, so nobody else sees it, and it doesn't"
			+ " block or delay anything you're doing.",
		section = behaviourSection,
		position = 5
	)
	default boolean playDropEmote()
	{
		return true;
	}

	@ConfigSection(
		name = "Ground items",
		description = "The loot beam over ground items that satisfy a tile",
		position = 6
	)
	String overlaySection = "overlays";

	@ConfigItem(
		keyName = "highlightGroundItems",
		name = "Loot beam on bingo drops",
		description = "Show a loot beam over ground items that would satisfy one of your team's tiles.",
		section = overlaySection,
		position = 7
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
		position = 8
	)
	default Color groundItemHighlightColor()
	{
		return new Color(255, 215, 0, 180);
	}

	@ConfigSection(
		name = "Event codeword",
		description = "A watermark for manually-taken screenshots, so they can be tied to the live event. Enter"
			+ " whatever codeword the event organiser gives you when it starts. Drag it with Alt held to move it,"
			+ " or resize it from its edge, like any other overlay.",
		position = 9
	)
	String codewordSection = "codeword";

	@ConfigItem(
		keyName = "showVerificationOverlay",
		name = "Display codeword",
		description = "Show the codeword below as a small on-screen watermark.",
		section = codewordSection,
		position = 10
	)
	default boolean showVerificationOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "codephrase",
		name = "Codeword",
		description = "The codeword the event organiser gives you once the event starts.",
		section = codewordSection,
		position = 11
	)
	default String codephrase()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showVerificationTimestamp",
		name = "Show timestamp",
		description = "Include a live UTC timestamp next to the codeword.",
		section = codewordSection,
		position = 12
	)
	default boolean showVerificationTimestamp()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "verificationCodeColor",
		name = "Codeword color",
		description = "Color of the codeword text in the watermark.",
		section = codewordSection,
		position = 13
	)
	default Color verificationCodeColor()
	{
		return new Color(0, 255, 106);
	}

	@Alpha
	@ConfigItem(
		keyName = "verificationTimestampColor",
		name = "Timestamp color",
		description = "Color of the timestamp text in the watermark.",
		section = codewordSection,
		position = 14
	)
	default Color verificationTimestampColor()
	{
		return Color.WHITE;
	}
}
