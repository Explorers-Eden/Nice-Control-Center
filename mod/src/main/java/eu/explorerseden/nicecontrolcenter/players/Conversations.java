package eu.explorerseden.nicecontrolcenter.players;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Private messages between the dashboard and players: admins write from the player card, players
 * answer with /nccreply. Nothing goes to public chat. Kept in memory (last 50 per player).
 */
public final class Conversations {
	public record Message(long time, boolean fromPlayer, String text) {
	}

	private static final int KEEP = 50;
	private static final Map<UUID, Deque<Message>> messages = new LinkedHashMap<>();
	private static final Map<UUID, Integer> unread = new LinkedHashMap<>();
	private static final Map<UUID, Long> lastReply = new LinkedHashMap<>();

	private Conversations() {
	}

	public static synchronized void fromAdmin(UUID player, String text) {
		add(player, new Message(System.currentTimeMillis(), false, text));
	}

	/** A player's answer. Returns false if they're sending too fast. */
	public static synchronized boolean fromPlayer(UUID player, String text) {
		long now = System.currentTimeMillis();
		Long last = lastReply.get(player);
		if (last != null && now - last < 3000) {
			return false;
		}
		lastReply.put(player, now);
		add(player, new Message(now, true, text));
		unread.merge(player, 1, Integer::sum);
		return true;
	}

	private static void add(UUID player, Message message) {
		Deque<Message> list = messages.computeIfAbsent(player, k -> new ArrayDeque<>());
		list.addLast(message);
		while (list.size() > KEEP) {
			list.removeFirst();
		}
	}

	public static synchronized List<Message> thread(UUID player) {
		Deque<Message> list = messages.get(player);
		return list == null ? List.of() : new ArrayList<>(list);
	}

	public static synchronized int unread(UUID player) {
		return unread.getOrDefault(player, 0);
	}

	public static synchronized int unreadTotal() {
		return unread.values().stream().mapToInt(Integer::intValue).sum();
	}

	public static synchronized void markRead(UUID player) {
		unread.remove(player);
	}

	/** Players with messages, so they show up in the list even without a save file. */
	public static synchronized List<UUID> players() {
		return new ArrayList<>(messages.keySet());
	}
}
