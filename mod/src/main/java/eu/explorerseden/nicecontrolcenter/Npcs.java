package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * NPCs (panel: Community → NPCs, in game: /npc): mannequins with a skin, pose and equipment, standing on a spot or
 * walking a path, with actions on right-click and on a hit, talking lines and an optional reward shop. The same NPC
 * can be used as a hostile in mob pools ("npc:knight x2"), fighting with what it holds. Kept in
 * config/nicecontrolcenter-npcs.json. Minecraft-free so the mod and the panel both compile it.
 */
public final class Npcs {
	public static final String FILE = "nicecontrolcenter-npcs.json";
	public static final List<String> POSES = List.of("standing", "crouching", "sleeping", "swimming", "fall_flying");

	private Npcs() {
	}

	public static final class Settings {
		public boolean enabled = true;
		public List<Npc> npcs = new ArrayList<>();
		/** Reward shop NPCs were moved over (once). */
		public boolean shopsMigrated = false;
		/** For skins from any PNG link (mineskin.org, free); empty = none set. */
		public String mineskinKey = "";

		public Npc npc(String idOrName) {
			if (idOrName == null) return null;
			for (Npc n : npcs) if (n.id.equals(idOrName) || n.name.equalsIgnoreCase(idOrName)) return n;
			return null;
		}

		public Settings fill() {
			if (npcs == null) npcs = new ArrayList<>();
			if (mineskinKey == null) mineskinKey = "";
			mineskinKey = mineskinKey.strip();
			npcs.removeIf(n -> n == null);
			Set<String> ids = new HashSet<>();
			for (Npc n : npcs) {
				if (n.id == null || n.id.isBlank() || !ids.add(n.id)) {
					n.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(n.id);
				}
				n.fill();
			}
			return this;
		}
	}

	public static final class Npc {
		public String id = "";
		/** One word, for the commands and mob pools (npc:<name>). */
		public String name = "";
		/** Shown above it; empty = no name tag. */
		public String title = "";
		/** A line under the name (the mannequin's description); empty = none. Talking lines show there too. */
		public String description = "";
		/** A player name, a textures.minecraft.net link or hash, or the base64 value; empty = the default look. */
		public String skin = "";
		/** A PNG link's skin as uploaded through MineSkin: its texture value, the link it was made from, or why it failed. */
		public String skinValue = "";
		public String skinSource = "";
		public String skinError = "";
		public String pose = "standing";
		/** "right" or "left". */
		public String mainHand = "right";
		/** Items in the kit format (stack JSON plus "slot": 0 main hand, 40 off hand, 36 feet … 39 head). */
		public List<String> equipment = new ArrayList<>();
		/** Where it stands (or starts walking); null = not placed (e.g. only used as a hostile in mob pools). */
		public Games.Pos spot;
		public boolean lookAtPlayers = true;

		/** Walking: checkpoints in order; empty = it stays on its spot. */
		public List<Checkpoint> path = new ArrayList<>();
		/** "loop" (back to the first), "back" (back and forth) or "random" (any other checkpoint next). */
		public String pathMode = "loop";
		/** Walking speed (0.1–1; 0.25 is a calm walk). */
		public double walkSpeed = 0.25;

		/** On a right-click and on a hit. */
		public List<Action> onClick = new ArrayList<>();
		public List<Action> onHit = new ArrayList<>();
		/** Seconds before the same player can set it off again. */
		public int cooldownSeconds = 1;
		/** A reward shop opened on a right-click (shop id; empty = none). */
		public String shop = "";

		/** Talking lines: one now and then to players close by, and (clickLines) the next one on each right-click. */
		public List<String> lines = new ArrayList<>();
		public int lineRadius = 8;
		public int lineEverySeconds = 20;
		public boolean clickLines = false;

		/** As a hostile in mob pools: its stats (it fights with what it holds). */
		public Hostile hostile = new Hostile();

		public String shown() {
			return title == null || title.isBlank() ? name : title.strip();
		}

		public boolean walks() {
			return path != null && !path.isEmpty();
		}

		void fill() {
			if (name == null) name = "";
			if (title == null) title = "";
			if (description == null) description = "";
			if (skin == null) skin = "";
			skin = skin.strip();
			if (skinValue == null) skinValue = "";
			if (skinSource == null) skinSource = "";
			if (skinError == null) skinError = "";
			if (pose == null || !POSES.contains(pose)) pose = "standing";
			if (!"left".equals(mainHand)) mainHand = "right";
			if (equipment == null) equipment = new ArrayList<>();
			equipment.removeIf(e -> e == null || e.isBlank());
			if (spot != null && spot.dim == null) spot = null;
			if (path == null) path = new ArrayList<>();
			path.removeIf(c -> c == null || c.pos == null || c.pos.dim == null);
			for (Checkpoint c : path) c.waitSeconds = Math.max(0, Math.min(600, c.waitSeconds));
			if (!"back".equals(pathMode) && !"random".equals(pathMode)) pathMode = "loop";
			walkSpeed = Math.max(0.1, Math.min(1, walkSpeed == 0 ? 0.25 : walkSpeed));
			for (List<Action> list : List.of(onClick == null ? onClick = new ArrayList<>() : onClick, onHit == null ? onHit = new ArrayList<>() : onHit)) {
				list.removeIf(a -> a == null || a.text == null || a.text.isBlank());
				for (Action a : list) {
					if (!"message".equals(a.type)) a.type = "command";
					if (!"player".equals(a.as)) a.as = "server";
					a.text = a.text.strip();
				}
			}
			cooldownSeconds = Math.max(0, Math.min(86_400, cooldownSeconds));
			if (shop == null) shop = "";
			if (lines == null) lines = new ArrayList<>();
			lines.removeIf(l -> l == null || l.isBlank());
			lineRadius = Math.max(2, Math.min(32, lineRadius));
			lineEverySeconds = Math.max(5, Math.min(3600, lineEverySeconds));
			if (hostile == null) hostile = new Hostile();
			hostile.health = Math.max(1, Math.min(2048, hostile.health));
			hostile.damage = Math.max(0, Math.min(200, hostile.damage));
			hostile.speed = Math.max(0.05, Math.min(1, hostile.speed));
			hostile.armor = Math.max(0, Math.min(30, hostile.armor));
			hostile.followRange = Math.max(8, Math.min(64, hostile.followRange));
		}
	}

	/** A link to a picture somewhere other than Mojang's skin server (uploaded through MineSkin). */
	public static boolean pictureLink(String skin) {
		return skin != null && skin.matches("(?i)https?://\\S+") && !skin.matches("(?i)https?://textures\\.minecraft\\.net/.*");
	}

	public static final class Checkpoint {
		public Games.Pos pos;
		/** Seconds it stands there before walking on (looking the way it faced when the point was set). */
		public int waitSeconds = 0;

		public Checkpoint() {
		}

		public Checkpoint(Games.Pos pos, int waitSeconds) {
			this.pos = pos;
			this.waitSeconds = waitSeconds;
		}
	}

	/** A command (as the server or as the player, without /) or a message to the player, with {player}, {uuid}, {npc}. */
	public static final class Action {
		public String type = "command";
		public String text = "";
		public String as = "server";
	}

	public static final class Hostile {
		public double health = 20;
		public double damage = 3;
		public double speed = 0.25;
		public double armor = 0;
		public double followRange = 32;
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Set<String> names = new HashSet<>();
		for (Npc n : s.npcs) {
			if (!n.name.matches("[A-Za-z0-9_-]{1,32}")) return "An NPC needs a one-word name (letters, digits, - and _).";
			if (!names.add(n.name.toLowerCase(Locale.ROOT))) return "Two NPCs are called \"" + n.name + "\".";
			if (n.title.length() > 64) return "The shown name of " + n.name + " is up to 64 characters.";
			if (n.description.length() > 100) return "The line under the name of " + n.name + " is up to 100 characters.";
			if (n.skin.length() > 8000) return "The skin of " + n.name + " is too long.";
			if (n.lines.size() > 50) return n.name + " has more than 50 talking lines.";
			for (String l : n.lines) if (l.length() > 200) return "A talking line of " + n.name + " is longer than 200 characters.";
			for (Action a : n.onClick) if (a.text.length() > 2000 || a.text.contains("\n")) return "An action of " + n.name + " is too long.";
			for (Action a : n.onHit) if (a.text.length() > 2000 || a.text.contains("\n")) return "An action of " + n.name + " is too long.";
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
