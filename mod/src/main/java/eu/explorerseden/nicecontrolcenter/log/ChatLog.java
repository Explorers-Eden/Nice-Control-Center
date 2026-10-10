package eu.explorerseden.nicecontrolcenter.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;

/**
 * What players see in chat, for the dashboard's Chat tab: player chat, /say and /me, joins, leaves,
 * deaths and advancements, and Discord messages, both from Discord: JustSync ("[Discord] &lt;name&gt; text",
 * sent as a server broadcast) and from the panel's Discord bridge ({@link #relay}). Kept in memory.
 */
public final class ChatLog {
	/** One line. kind: chat, say, emote, discord, event or system. replyTo only for Discord replies. */
	public record Entry(long seq, long time, String kind, String name, String text, String replyTo) {
	}

	private static final int MAX = 1000;
	/** JustSync's default formats: "[Discord] <name> text" and " [Discord] <name replied to other> text". */
	private static final Pattern DISCORD_ANGLE = Pattern.compile("^\\s*\\[Discord]\\s*<(.+?)(?: replied to (.+?))?>\\s?(.*)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern DISCORD_COLON = Pattern.compile("^\\s*\\[Discord]\\s*([^:\\n]{1,64}):\\s?(.*)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern DISCORD_PREFIX = Pattern.compile("^\\s*\\[Discord]\\s*(.*)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	private static final Deque<Entry> entries = new ArrayDeque<>();
	private static long seq;
	/** Set on the server thread while {@link #relay} broadcasts, so its own message isn't logged twice. */
	private static boolean relaying;

	private ChatLog() {
	}

	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				add("chat", sender.getGameProfile().name(), message.signedContent(), null));
		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) ->
				add(params.chatType().is(ChatType.EMOTE_COMMAND) ? "emote" : "say", source.getTextName(), message.signedContent(), null));
		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (overlay || relaying) {
				return;
			}
			if (message.getContents() instanceof TranslatableContents tr) {
				String key = tr.getKey();
				if (key.startsWith("multiplayer.player.") || key.startsWith("death.") || key.startsWith("chat.type.advancement.")) {
					add("event", "", message.getString(), null);
				} else {
					add("system", "", message.getString(), null);
				}
				return;
			}
			String text = message.getString();
			Matcher m = DISCORD_ANGLE.matcher(text);
			if (m.matches()) {
				add("discord", m.group(1), m.group(3).strip(), m.group(2));
				return;
			}
			m = DISCORD_COLON.matcher(text);
			if (m.matches()) {
				add("discord", m.group(1).strip(), m.group(2).strip(), null);
				return;
			}
			m = DISCORD_PREFIX.matcher(text);
			if (m.matches()) {
				add("discord", "", m.group(1).strip(), null);
				return;
			}
			add("system", "", text, null);
		});
	}

	/**
	 * Server thread. A message from the panel's Discord bridge: shown to everyone as
	 * "[Discord] name: text", in the server log, and in the Chat tab. Never run as a command.
	 */
	public static void relay(MinecraftServer server, String name, String text) {
		MutableComponent line = Component.empty()
				.append(Component.literal("[Discord] ").withColor(0x7289DA))
				.append(Component.literal(name).withColor(0xFFFFFF))
				.append(Component.literal(": " + text).withColor(0xAAAAAA));
		relaying = true;
		try {
			server.getPlayerList().broadcastSystemMessage(line, false);
		} finally {
			relaying = false;
		}
		add("discord", name, text, null);
	}

	private static synchronized void add(String kind, String name, String text, String replyTo) {
		if (text == null || text.isBlank()) {
			return;
		}
		entries.addLast(new Entry(++seq, System.currentTimeMillis(), kind, name == null ? "" : name, text, replyTo));
		while (entries.size() > MAX) {
			entries.removeFirst();
		}
	}

	/** Lines after {@code after} (a seq), oldest first. */
	public static synchronized List<Entry> after(long after) {
		List<Entry> result = new ArrayList<>();
		for (Entry e : entries) {
			if (e.seq() > after) {
				result.add(e);
			}
		}
		return result;
	}
}
