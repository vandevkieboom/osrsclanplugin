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

	@ConfigItem(
		keyName = "enableClanCommands",
		name = "Clan chat commands",
		description = "Turn off the !rank, !verify, !needed, and !live chat commands entirely.",
		section = clanSection,
		position = 6
	)
	default boolean enableClanCommands()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "clanMessageColor",
		name = "Clan notification color",
		description = "Configure the color of broadcast, live-stream, and RuneProfile-sync notifications.",
		section = clanSection,
		position = 7
	)
	default Color clanMessageColor()
	{
		return new Color(0xFFFFFF80, true);
	}

	@ConfigSection(
		name = "Clan bingo",
		description = "Configure automatic bingo drop submissions and notifications.",
		position = 7
	)
	String bingoSection = "bingo";

	@ConfigItem(
		keyName = "showSidebar",
		name = "Show bingo board",
		description = "Show the bingo board tab in the sidebar. Turn off to hide the icon entirely.",
		section = bingoSection,
		position = 6
	)
	default boolean showSidebar()
	{
		return true;
	}

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
		return new Color(0xFF00FF6A, true);
	}

	@ConfigItem(
		keyName = "playDropEmote",
		name = "Crab dance on bingo drop",
		description = "Perform a silly crab dance when you receive a bingo drop.",
		section = bingoSection,
		position = 9
	)
	default boolean playDropEmote()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLiveCodewordOverlay",
		name = "Display codeword",
		description = "Displays an event codeword overlay on screen.",
		section = bingoSection,
		position = 12
	)
	default boolean showLiveCodewordOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "verificationCode",
		name = "Codeword",
		description = "Whatever code your clan admin announced for the current event.",
		section = bingoSection,
		position = 13
	)
	default String verificationCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showCodewordTimestamp",
		name = "Show timestamp",
		description = "Attach a timestamp to the codeword overlay.",
		section = bingoSection,
		position = 14
	)
	default boolean showCodewordTimestamp()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "codewordColor",
		name = "Codeword color",
		description = "Configure the color of the codeword text in the on-screen codeword overlay.",
		section = bingoSection,
		position = 15
	)
	default Color codewordColor()
	{
		return new Color(0x00FF6A);
	}

	@Alpha
	@ConfigItem(
		keyName = "timestampColor",
		name = "Timestamp color",
		description = "Configure the color of the timestamp text in the on-screen codeword overlay.",
		section = bingoSection,
		position = 16
	)
	default Color timestampColor()
	{
		return new Color(0xFFFFFFFF, true);
	}
}
