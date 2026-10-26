package eu.explorerseden.nicecontrolcenter;

import java.util.regex.Pattern;

/** Log lines that look alarming but aren't. Minecraft-free: the mod's error list and the panel's log files both use it. */
public final class LogNoise {
	public static final Pattern HARMLESS = Pattern.compile(
			"Invalid path in pack: .*\\.DS_Store|Non-directory entry .*\\.DS_Store|SERVER IS RUNNING IN OFFLINE|The server will make no attempt to authenticate|resource-pack-id missing, using default"
					+ "|While this makes the game possible to play without internet|To change this, set \"online-mode\""
					+ "|Unable to parse version|TranslationConventionLogWarnings|No key layers in MapLike"
					// A connection closed while still pinging/handshaking (server list, scanners): vanilla noise.
					+ "|Error sending packet clientbound/minecraft:disconnect|handleDisconnection\\(\\) called twice"
					// Lag between client and server, ender pearls of players logging out: vanilla noise.
					+ "|Mismatch in destroy block pos|moved wrongly|moved too quickly|UUID of added entity already exists|Failed to spawn player ender pearl"
					// Recipes for items a mod or pack doesn't have (optional compat): the recipe just isn't there.
					+ "|(?:couldn't parse|failed to load|unknown|unrecognized|parsing error).{0,80}recipe|recipe.{0,120}(?:not found|missing|unknown item|unknown registry key)",
			Pattern.CASE_INSENSITIVE);

	private LogNoise() {
	}
}
