package eu.explorerseden.nicecontrolcenter.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A copy of the in-game chat for the dashboard's Chat tab, with colors and formatting: everything
 * players get in their chat window (player chat, /say, /me, /msg, tellraw, joins, leaves, deaths,
 * advancements, command feedback, Discord messages from Discord: JustSync or the panel's bridge).
 * The same message sent to several players at once is one line, with who got it. Kept in memory.
 */
public final class ChatLog {
	/** A run of text with one style. Color as "#rrggbb"; url when clicking opens a link. */
	public record Segment(String t, String c, boolean b, boolean i, boolean u, boolean s, boolean o, String url) {
	}

	/**
	 * One line. kind: chat, discord, event or system. to: who got it, or null for everyone online.
	 */
	public record Entry(long seq, long time, String kind, List<Segment> parts, String text, List<String> to) {
	}

	private static final class Pending {
		final long seq;
		final long time;
		final String kind;
		final List<Segment> parts;
		final String text;
		final int online;
		final Set<String> to = new LinkedHashSet<>();

		Pending(long seq, long time, String kind, List<Segment> parts, String text, int online) {
			this.seq = seq;
			this.time = time;
			this.kind = kind;
			this.parts = parts;
			this.text = text;
			this.online = online;
		}

		Entry entry() {
			return new Entry(seq, time, kind, parts, text, online == 0 || to.size() >= online ? null : List.copyOf(to));
		}
	}

	private static final int MAX = 1000;
	/** Sends of the same message to several players arrive within this window. */
	private static final long MERGE_MS = 250;
	private static final Pattern DISCORD = Pattern.compile("^\\s*\\[Discord]", Pattern.CASE_INSENSITIVE);

	private static final Deque<Pending> entries = new ArrayDeque<>();
	private static long seq;

	private ChatLog() {
	}

	/** A message landed in a player's chat window. Any thread. */
	public static void received(ServerPlayer player, Component message, boolean chat) {
		int online = player.level().getServer().getPlayerCount();
		add(message, chat, player.getGameProfile().name(), online);
	}

	/** A message to everyone while nobody is online. */
	public static void unheard(Component message, boolean chat) {
		add(message, chat, null, 0);
	}

	/** Server thread. A message from the panel's Discord bridge, shown to everyone as "[Discord] name: text". */
	public static void relay(MinecraftServer server, String name, String text) {
		MutableComponent line = Component.empty()
				.append(Component.literal("[Discord] ").withColor(0x7289DA))
				.append(Component.literal(name).withColor(0xFFFFFF))
				.append(Component.literal(": " + text).withColor(0xAAAAAA));
		server.getPlayerList().broadcastSystemMessage(line, false);
	}

	private static void add(Component message, boolean chat, String player, int online) {
		List<Segment> parts = segments(message);
		String text = message.getString();
		if (text.isBlank()) {
			return;
		}
		long now = System.currentTimeMillis();
		synchronized (ChatLog.class) {
			Iterator<Pending> recent = entries.descendingIterator();
			while (recent.hasNext()) {
				Pending p = recent.next();
				if (now - p.time > MERGE_MS) {
					break;
				}
				if (p.text.equals(text) && p.parts.equals(parts) && player != null && !p.to.contains(player)) {
					p.to.add(player);
					return;
				}
			}
			Pending p = new Pending(++seq, now, kind(message, chat, text), parts, text, online);
			if (player != null) {
				p.to.add(player);
			}
			entries.addLast(p);
			while (entries.size() > MAX) {
				entries.removeFirst();
			}
		}
	}

	private static String kind(Component message, boolean chat, String text) {
		if (DISCORD.matcher(text).find()) {
			return "discord";
		}
		if (chat) {
			return "chat";
		}
		if (message.getContents() instanceof TranslatableContents tr) {
			String key = tr.getKey();
			if (key.startsWith("multiplayer.player.") || key.startsWith("death.") || key.startsWith("chat.type.advancement.")) {
				return "event";
			}
		}
		return "system";
	}

	/** The message as styled runs, translations resolved like the server's language. */
	private static List<Segment> segments(Component message) {
		List<Segment> parts = new ArrayList<>();
		message.visit((style, string) -> {
			if (!string.isEmpty()) {
				Segment next = segment(style, string);
				Segment last = parts.isEmpty() ? null : parts.get(parts.size() - 1);
				if (last != null && sameStyle(last, next)) {
					parts.set(parts.size() - 1, new Segment(last.t() + string, last.c(), last.b(), last.i(), last.u(), last.s(), last.o(), last.url()));
				} else {
					parts.add(next);
				}
			}
			return Optional.empty();
		}, Style.EMPTY);
		return parts;
	}

	private static Segment segment(Style style, String text) {
		TextColor color = style.getColor();
		String url = style.getClickEvent() instanceof ClickEvent.OpenUrl open ? open.uri().toString() : null;
		return new Segment(text, color == null ? null : String.format("#%06x", color.getValue() & 0xFFFFFF), style.isBold(), style.isItalic(),
				style.isUnderlined(), style.isStrikethrough(), style.isObfuscated(), url);
	}

	private static boolean sameStyle(Segment a, Segment b) {
		return java.util.Objects.equals(a.c(), b.c()) && a.b() == b.b() && a.i() == b.i() && a.u() == b.u() && a.s() == b.s()
				&& a.o() == b.o() && java.util.Objects.equals(a.url(), b.url());
	}

	/** Lines after {@code after} (a seq), oldest first. Lines still collecting recipients are sent again. */
	public static synchronized Map<String, Object> after(long after) {
		List<Entry> result = new ArrayList<>();
		long now = System.currentTimeMillis();
		long settled = after;
		for (Pending p : entries) {
			if (p.seq > after) {
				result.add(p.entry());
			}
			if (now - p.time > MERGE_MS) {
				settled = Math.max(settled, p.seq);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("lines", result);
		// The next poll starts here, so a line still collecting recipients comes again, complete.
		out.put("next", settled);
		// Lower than what the dashboard has seen: the server restarted.
		out.put("latest", seq);
		return out;
	}
}
