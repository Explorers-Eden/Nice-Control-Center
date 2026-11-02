package eu.explorerseden.nicecontrolcenter.games;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Game and dungeon text in Title Case ("Wave 3 In 4 s · Get Ready!"). Left as typed: commands (from "/" to
 * where the sentence carries on), ids and placeholders (anything with : / _ { } # < > [ ] @ = or a digit),
 * units after numbers (5 s, x3), and player names.
 */
public final class Words {
	/** Names of players seen since the start (kept as they spell them). */
	private static final Set<String> names = ConcurrentHashMap.newKeySet();
	/** Words after a command that are the sentence again ("/game leave to get out"). */
	private static final Set<String> AFTER_COMMAND = Set.of("to", "or", "and", "in", "where", "then", "for", "first", "again", "when", "with", "from", "at",
			"on", "if", "while", "takes", "take", "leaves", "you", "them", "is", "are", "as", "next", "here", "there", "afterwards");
	private static final Set<String> UNITS = Set.of("s", "ms", "m", "h", "x", "min", "sec");

	private Words() {
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> names.add(handler.getPlayer().getGameProfile().name()));
	}

	/** Component.literal, in Title Case. */
	public static MutableComponent lit(String text) {
		return Component.literal(title(text));
	}

	public static String title(String text) {
		if (text == null || text.isEmpty()) return text == null ? "" : text;
		StringBuilder out = new StringBuilder(text.length());
		boolean command = false;
		String before = "";
		int i = 0, n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				out.append(c);
				i++;
				continue;
			}
			int j = i;
			while (j < n && !Character.isWhitespace(text.charAt(j))) j++;
			String word = text.substring(i, j);
			String bare = word.replaceAll("^[^\\p{L}\\p{N}/]+|[^\\p{L}\\p{N}]+$", "");
			if (command && AFTER_COMMAND.contains(bare.toLowerCase(java.util.Locale.ROOT))) command = false;
			if (word.startsWith("/") || word.matches("[(\"'«]+/.*")) command = true;
			if (command || keep(word, bare, before)) out.append(word);
			else out.append(capital(word));
			// A command ends with the word that closes a clause.
			if (command && word.matches(".*[.,;:)!?\"]$") && !word.startsWith("/")) command = false;
			if (command && word.startsWith("/") && word.matches(".*[.,;)!?\"]$")) command = false;
			before = bare;
			i = j;
		}
		return out.toString();
	}

	private static boolean keep(String word, String bare, String before) {
		if (bare.isEmpty()) return true;
		if (word.matches(".*[:/_{}#<>\\[\\]@=].*") && !word.matches("[^:/_{}#<>\\[\\]@=]+[:)!?.,;]+")) return true;
		if (bare.chars().anyMatch(Character::isDigit)) return true;
		if (UNITS.contains(bare) && before.matches("[\\d.,]+")) return true;
		String name = bare.endsWith("'s") ? bare.substring(0, bare.length() - 2) : bare;
		return names.contains(name);
	}

	/** The first letter up (after any opening marks like ( " ✦). */
	private static String capital(String word) {
		for (int k = 0; k < word.length(); k++) {
			char c = word.charAt(k);
			if (Character.isLetter(c)) {
				if (Character.isUpperCase(c)) return word;
				return word.substring(0, k) + Character.toUpperCase(c) + word.substring(k + 1);
			}
			if (Character.isDigit(c)) return word;
		}
		return word;
	}
}
