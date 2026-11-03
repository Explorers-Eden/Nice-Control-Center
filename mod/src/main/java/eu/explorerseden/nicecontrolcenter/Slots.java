package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Slot Machines (Games → Slot Machines, /slots in game): machines placed from the built-in looks, each of a type with
 * its cost in points and a list of wins (three symbols, a chance, a payout). Pulling the lever costs the points, the
 * shelf above spins and stops on a row. Kept in config/nicecontrolcenter-slots.json. Minecraft-free so the mod and the
 * panel both compile it.
 */
public final class Slots {
	public static final String FILE = "nicecontrolcenter-slots.json";
	/** The built-in looks (structures data/nicecontrolcenter/structure/slot_machine/<look>.nbt). */
	public static final List<String> LOOKS = List.of("acacia", "bamboo", "mangrove", "poplar", "warped");

	private Slots() {
	}

	public static final class Settings {
		public boolean enabled = true;
		public List<Type> types = new ArrayList<>();
		public List<Machine> machines = new ArrayList<>();
		/** Removed in the panel: the mod takes their blocks away (also after a restart), then empties this. */
		public List<Machine> takeAway = new ArrayList<>();

		public Type type(String idOrName) {
			if (idOrName == null) return null;
			for (Type t : types) if (t.id.equals(idOrName) || t.name.equalsIgnoreCase(idOrName)) return t;
			return null;
		}

		public Settings fill() {
			if (types == null) types = new ArrayList<>();
			if (machines == null) machines = new ArrayList<>();
			if (takeAway == null) takeAway = new ArrayList<>();
			takeAway.removeIf(m -> m == null || m.base == null || m.base.dim == null);
			for (Machine m : takeAway) m.rotation = Math.floorMod(m.rotation, 4);
			types.removeIf(t -> t == null);
			machines.removeIf(m -> m == null || m.base == null || m.base.dim == null);
			Set<String> ids = new HashSet<>();
			for (Type t : types) {
				if (t.id == null || t.id.isBlank() || !ids.add(t.id)) {
					t.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(t.id);
				}
				t.fill();
			}
			ids.clear();
			for (Machine m : machines) {
				if (m.id == null || m.id.isBlank() || !ids.add(m.id)) {
					m.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(m.id);
				}
				if (m.type == null) m.type = "";
				if (m.look == null || !LOOKS.contains(m.look)) m.look = LOOKS.get(0);
				m.rotation = Math.floorMod(m.rotation, 4);
			}
			return this;
		}
	}

	/** A kind of machine: what a pull costs and what it can win. */
	public static final class Type {
		public String id = "";
		/** One word, for the commands. */
		public String name = "";
		/** What players see; empty = the name. */
		public String title = "";
		/** Points a pull costs. */
		public long cost = 10;
		/** Extra symbols that only show up on losing rows (item ids), besides the wins' own. */
		public List<String> fillers = new ArrayList<>(List.of("minecraft:coal", "minecraft:rotten_flesh", "minecraft:bone"));
		/** What can be won; each pull wins at most one of them (their chances add up, the rest is a loss). */
		public List<Win> wins = new ArrayList<>();
		/** Seconds the reels spin (the last one stops then). */
		public double spinSeconds = 2.5;

		public String shown() {
			return title == null || title.isBlank() ? name : title.strip();
		}

		/** The chance of any win (%). */
		public double winChance() {
			double sum = 0;
			for (Win w : wins) sum += w.chance;
			return sum;
		}

		/** Points paid back per point put in, on average (1 = all of it). */
		public double returnRate() {
			if (cost <= 0) return 0;
			double sum = 0;
			for (Win w : wins) sum += w.chance / 100 * w.payout;
			return sum / cost;
		}

		/** Every symbol the reels show. */
		public List<String> symbols() {
			Set<String> all = new LinkedHashSet<>();
			for (Win w : wins) all.addAll(w.symbols);
			all.addAll(fillers);
			return new ArrayList<>(all);
		}

		void fill() {
			if (name == null) name = "";
			if (title == null) title = "";
			cost = Math.max(0, Math.min(1_000_000, cost));
			spinSeconds = Math.max(1, Math.min(10, spinSeconds == 0 ? 2.5 : spinSeconds));
			if (fillers == null) fillers = new ArrayList<>();
			fillers = new ArrayList<>(fillers.stream().filter(x -> x != null && !x.isBlank()).map(Slots::itemId).toList());
			if (wins == null) wins = new ArrayList<>();
			wins.removeIf(w -> w == null);
			for (Win w : wins) w.fill();
		}
	}

	/** A winning row: three symbols, its chance per pull (%), the points it pays and commands it runs. */
	public static final class Win {
		/** The three symbols left to right (item ids); one given = three of it. */
		public List<String> symbols = new ArrayList<>();
		public double chance = 1;
		public long payout = 0;
		/** Run as the server for the player ({player}, {uuid}, {machine}), without /. */
		public List<String> commands = new ArrayList<>();
		/** Shown to the player (empty = "<symbols>: +<payout>"); {player}, {payout}. */
		public String message = "";
		/** Told to everyone online (big wins). */
		public boolean announce = false;

		void fill() {
			if (symbols == null) symbols = new ArrayList<>();
			symbols = new ArrayList<>(symbols.stream().filter(x -> x != null && !x.isBlank()).map(Slots::itemId).toList());
			if (symbols.size() == 1 || symbols.size() == 2) while (symbols.size() < 3) symbols.add(symbols.get(symbols.size() - 1));
			if (symbols.size() > 3) symbols = new ArrayList<>(symbols.subList(0, 3));
			chance = Math.max(0, Math.min(100, chance));
			payout = Math.max(0, Math.min(100_000_000, payout));
			if (commands == null) commands = new ArrayList<>();
			commands.removeIf(c -> c == null || c.isBlank());
			if (message == null) message = "";
		}
	}

	/** A placed machine: its base block (the one with the lever on it), turned in quarter turns from the saved look. */
	public static final class Machine {
		public String id = "";
		public String type = "";
		public String look = "acacia";
		public Games.Pos base;
		/** 0–3 quarter turns clockwise (0 = the lever on the north side). */
		public int rotation = 0;
	}

	/** "diamond" → "minecraft:diamond". */
	public static String itemId(String s) {
		String t = s.strip().toLowerCase(Locale.ROOT);
		return t.contains(":") ? t : "minecraft:" + t;
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Set<String> names = new HashSet<>();
		for (Type t : s.types) {
			if (!t.name.matches("[A-Za-z0-9_-]{1,32}")) return "A slot machine type needs a one-word name (letters, digits, - and _).";
			if (!names.add(t.name.toLowerCase(Locale.ROOT))) return "Two slot machine types are called \"" + t.name + "\".";
			if (t.title.length() > 48) return "The shown name of " + t.name + " is up to 48 characters.";
			if (t.wins.size() > 30) return t.name + " has more than 30 wins.";
			if (t.winChance() > 100.0001) return "The wins of " + t.name + " add up to more than 100 % (" + Math.round(t.winChance() * 100) / 100.0 + " %).";
			for (Win w : t.wins) {
				if (w.symbols.size() != 3) return "A win of " + t.name + " needs its symbols (one item for three of a kind, or three).";
				for (String id : w.symbols) if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + id + "\" in " + t.name + " isn't an item id.";
				for (String c : w.commands) if (c.length() > 2000 || c.contains("\n")) return "A command of " + t.name + " is too long.";
				if (w.message.length() > 200) return "A win message of " + t.name + " is longer than 200 characters.";
			}
			for (String id : t.fillers) if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + id + "\" in " + t.name + " isn't an item id.";
			if (t.symbols().size() < 2) return t.name + " needs at least two different symbols (wins and losing symbols together).";
		}
		return null;
	}

	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings().fill();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings().fill();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings().fill();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
