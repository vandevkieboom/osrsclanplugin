package com.timeserved.bingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a RuneLite client with this plugin side-loaded, for local testing.
 * Run via {@code ./gradlew runClient}.
 */
public class BingoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BingoPlugin.class);
		RuneLite.main(args);
	}
}
