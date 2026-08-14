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
		description = "Configure the connection to the clan site.",
		position = 0
	)
	String connectingSection = "connecting";

	@ConfigItem(
		keyName = "apiKey",
		name = "Plugin key",
		description = "Generate a plugin key on the clan site under Settings -> RuneLite plugin keys.",
		secret = true,
		section = connectingSection,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigSection(
		name = "Clan",
		description = "Configure clan-related features and chat commands.",
		position = 2
	)
	String clanSection = "clan";

	@ConfigItem(
		keyName = "notifyLiveStreams",
		name = "Notify when clan members go live",
		description = "Post a chat message when a clan member goes live on Twitch.",
		section = clanSection,
		position = 3
	)
	default boolean notifyLiveStreams()
	{
		return true;
	}

	@ConfigItem(
		keyName = "remindRuneProfileSync",
		name = "Remind me to sync RuneProfile",
		description = "Remind you once per session to sync RuneProfile if it hasn't been set up.",
		section = clanSection,
		position = 4
	)
	default boolean remindRuneProfileSync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyBroadcasts",
		name = "Clan broadcasts",
		description = "Show messages sent by admins from the clan site.",
		section = clanSection,
		position = 5
	)
	default boolean notifyBroadcasts()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "clanMessageColor",
		name = "Clan message color",
		description = "Configure the color of clan chat messages, reminders, and broadcasts.",
		section = clanSection,
		position = 6
	)
	default Color clanMessageColor()
	{
		return new Color(97, 175, 239);
	}

	@ConfigSection(
		name = "Clan bingo",
		description = "Configure automatic bingo drop submissions and notifications.",
		position = 7
	)
	String bingoSection = "bingo";

	@ConfigItem(
		keyName = "notifyOnSubmit",
		name = "Notify on bingo submit",
		description = "Post a chat message when bingo proof is submitted, or fails to submit.",
		section = bingoSection,
		position = 7
	)
	default boolean notifyOnSubmit()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "submitMessageColor",
		name = "Bingo message color",
		description = "Configure the color of bingo submission messages.",
		section = bingoSection,
		position = 8
	)
	default Color submitMessageColor()
	{
		return new Color(0, 200, 83);
	}

	@ConfigItem(
		keyName = "playDropEmote",
		name = "Emote on bingo drop",
		description = "Perform an emote when you receive a bingo drop.",
		section = bingoSection,
		position = 9
	)
	default boolean playDropEmote()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightGroundItems",
		name = "Highlight bingo drops",
		description = "Configure whether to show lootbeams for bingo drops.",
		section = bingoSection,
		position = 10
	)
	default boolean highlightGroundItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showVerificationOverlay",
		name = "Verification overlay",
		description = "Show the verification code and a live timestamp, so it's baked into every proof screenshot.",
		section = bingoSection,
		position = 12
	)
	default boolean showVerificationOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "verificationCode",
		name = "Verification code",
		description = "Whatever code your clan admin announced for the current event (e.g. in Discord) — not fetched from the site, just typed in here.",
		section = bingoSection,
		position = 13
	)
	default String verificationCode()
	{
		return "";
	}

	@Alpha
	@ConfigItem(
		keyName = "groundItemHighlightColor",
		name = "Highlight color",
		description = "Configure the color of lootbeams for bingo drops.",
		section = bingoSection,
		position = 11
	)
	default Color groundItemHighlightColor()
	{
		return new Color(255, 215, 0, 180);
	}
}
