package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gameplay tweaks that need the server mod (a data pack can't do them), kept in
 * config/nicecontrolcenter-tweaks.json: anvil limits, nether portals in any shape, tree felling and vein
 * mining, the AFK tag and the tab list, chat mentions, the grindstone, spawners, saplings, bone meal, tridents and dispensers. Written by the panel, read by the mod when the file changes;
 * Minecraft-free so both compile it. Everything is off until switched on, and vanilla clients work.
 */
public final class Tweaks {
	public static final String FILE = "nicecontrolcenter-tweaks.json";

	public static final class Anvil {
		/** Never "Too Expensive!": the full cost is charged however high it is. */
		public boolean noTooExpensive = false;
		/** Renaming alone (nothing in the second slot) costs renameCost levels. */
		public boolean cheapRename = false;
		public int renameCost = 1;
	}

	public static final class Portals {
		/** Any closed obsidian frame (circles, arches…) lights as a nether portal, not just rectangles. */
		public boolean anyShape = false;
		/** The most portal blocks one frame may hold. */
		public int maxBlocks = 400;
	}

	/** What sneaking does to tree felling and vein mining. */
	public enum Sneak {
		/** Sneaking breaks just the one block (the default). */
		DISABLES,
		/** Only sneaking fells the whole tree / vein. */
		ENABLES,
		/** Always the whole tree / vein. */
		IGNORED
	}

	public static final class Felling {
		public boolean enabled = false;
		/** instant: the whole tree breaks at once; shiftDown: each hit removes the top log and the tree sinks. */
		public String breakMode = "instant";
		/** Tools that fell trees (ids or #tags). */
		public List<String> tools = new ArrayList<>(List.of("#minecraft:axes"));
		public List<String> deniedTools = new ArrayList<>();
		/** Blocks that count as logs and leaves (ids or #tags). */
		public List<String> logs = new ArrayList<>(List.of("#minecraft:logs"));
		public List<String> deniedLogs = new ArrayList<>();
		public List<String> leaves = new ArrayList<>(List.of("#minecraft:leaves"));
		public Sneak sneak = Sneak.DISABLES;
		/** Stop before the tool would break. */
		public boolean preserveTool = true;
		/** Tool wear for each log of a felled tree (the chopped one included): 1 = normal, 0.5 = half, 0 = none, 2 = double. */
		public double durability = 1.0;
		/** The tree tips over and falls before its logs drop (instant mode). */
		public boolean animation = true;
		public int maxLogs = 256;
		/** Bigger trees: "skip" (only the one log breaks) or "cutUpTo" (the first maxLogs). */
		public String tooBig = "skip";
		/** Leaves (not placed by players) needed next to the top of the trunk, so player builds aren't felled. */
		public int minLeaves = 3;
		/** A trunk may be made of different log types. */
		public boolean mixedLogs = false;
		/** Leaves around the felled tree fall within a few seconds. */
		public boolean fastLeafDecay = true;
		public int leafRadius = 7;
		/** Nether trees: their wart blocks and shroomlights break with them. */
		public boolean netherWarts = true;
		/** Mangroves: their roots break with them. */
		public boolean mangroveRoots = true;
		/** All drops land where the first log was broken. */
		public boolean dropsAtBreakPoint = false;
		public boolean inCreative = false;
	}

	public static final class Veins {
		public boolean enabled = false;
		public List<String> tools = new ArrayList<>(List.of("#minecraft:pickaxes"));
		public List<String> deniedTools = new ArrayList<>();
		/** Blocks that count as ore (ids or #tags); a vein is connected blocks of the same kind. */
		public List<String> blocks = new ArrayList<>(List.of("#c:ores", "minecraft:ancient_debris"));
		public List<String> deniedBlocks = new ArrayList<>();
		public Sneak sneak = Sneak.DISABLES;
		public boolean preserveTool = true;
		/** Tool wear for each block of a mined vein (the mined one included): 1 = normal, 0.5 = half, 0 = none, 2 = double. */
		public double durability = 1.0;
		public int maxBlocks = 64;
		public boolean dropsAtBreakPoint = false;
		public boolean inCreative = false;
	}

	public static final class Afk {
		/** Players who did nothing for a while get a tag next to their name in the tab list. */
		public boolean enabled = false;
		public int minutes = 5;
		public String tag = "AFK";
	}

	public static final class TabList {
		/** A header and footer in the tab list, updated every second. */
		public boolean enabled = false;
		/** § colour codes and {players} {max} {tps} {mspt} {ping} {time}; \n for a new line. */
		public String header = "§6§lExplorer's Eden\n§7{players}/{max} players online";
		public String footer = "§7TPS §a{tps} §8· §7Ping §a{ping} ms §8· §7{time}";
		/** An empty line between the header/footer and the player list. */
		public boolean gap = true;
		/** Each player's dimension after their name. */
		public boolean showDimension = false;
		/** Name and colour (#rrggbb) per dimension id; dimensions not listed show their id's name in grey. */
		public Map<String, Dimension> dimensions = defaultDimensions();
	}

	public static final class Dimension {
		public String name = "";
		public String color = "#AAAAAA";

		public Dimension() {
		}

		public Dimension(String name, String color) {
			this.name = name;
			this.color = color;
		}
	}

	private static Map<String, Dimension> defaultDimensions() {
		Map<String, Dimension> m = new LinkedHashMap<>();
		m.put("minecraft:overworld", new Dimension("Overworld", "#55FF55"));
		m.put("minecraft:the_nether", new Dimension("Nether", "#FF5555"));
		m.put("minecraft:the_end", new Dimension("The End", "#D88CFF"));
		return m;
	}

	/** The name shown for a dimension: the configured one, else "the_nether" → "Nether". */
	public static Dimension dimension(TabList t, String id) {
		Dimension d = t.dimensions.get(id);
		if (d != null && !d.name.isBlank()) return d;
		String path = id.substring(id.indexOf(':') + 1).replaceFirst("^the_", "").replace('_', ' ');
		String name = path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
		return new Dimension(name, d != null ? d.color : "#AAAAAA");
	}

	public static final class Chat {
		/** "@Name" in chat plays the plop for that player and tells them who mentioned them. */
		public boolean mentions = false;
		/** The mention is coloured in the message (clients may mark such messages as changed by the server). */
		public boolean highlight = true;
		public String highlightColor = "#FFD166";
		/** A mention of a linked player who isn't online pings them on Discord (panel's bridge). */
		public boolean discordPing = true;
	}

	public static final class Grindstone {
		/** A book in the grindstone's second slot takes the item's enchantments onto an enchanted book, for levels. */
		public boolean booksEnabled = false;
		/** Levels per enchantment level (Sharpness V + Unbreaking III = 8 × this), between min and max. */
		public double levelsPerEnchantLevel = 1;
		public int minLevels = 1;
		public int maxLevels = 30;
		/** Keep the item's anvil work penalty (off: it starts fresh, like vanilla grinding). */
		public boolean keepRepairCost = false;
	}

	public static final class Spawners {
		/** Spawners mined with one of the tools (and Silk Touch, unless switched off) drop as an item that keeps its mob. */
		public boolean silkTouch = false;
		public boolean requireSilkTouch = true;
		/** Tools that may pick spawners up (ids or #tags). */
		public List<String> tools = new ArrayList<>(List.of("#minecraft:pickaxes"));
		/** Mobs whose spawners never drop (ids). */
		public List<String> deniedMobs = new ArrayList<>();
		public boolean dropXp = false;
		/** Tool wear for picking a spawner up: 1 = normal, 0.5 = half, 5 = five times (up to 100). */
		public double durability = 1.0;
	}

	public static final class Saplings {
		static final List<String> GROUND = List.of("#minecraft:substrate_overworld", "minecraft:clay");
		/** Saplings lying on the ground plant themselves after a while. */
		public boolean replant = false;
		public int delaySeconds = 20;
		/** The chance (in percent) that a sapling plants itself at all; decided once per sapling. */
		public int chance = 100;
		public List<String> items = new ArrayList<>(List.of("#minecraft:saplings", "minecraft:mangrove_propagule"));
		/**
		 * The blocks they may grow on (ids or #tags); empty is wherever the sapling itself can grow. By default the
		 * game's natural ground (dirt, grass, podzol, mycelium, moss, mud) and clay for mangroves, not farmland.
		 */
		public List<String> ground = new ArrayList<>(GROUND);
		/** No other sapling or log closer than this many blocks. */
		public int spacing = 2;
		/** Also inside land claims. */
		public boolean inClaims = false;
	}

	public static final class BoneMeal {
		public boolean sugarCane = false;
		public boolean cactus = false;
		public boolean netherWart = false;
		public boolean lilyPads = false;
		public boolean vines = false;
		public boolean flowers = false;
		/** How tall sugar cane and cactus may grow with bone meal. */
		public int maxHeight = 3;
		/** How far flowers spread. */
		public int flowerRadius = 3;
	}

	public static final class Tridents {
		/** A Loyalty trident thrown into the void comes back. */
		public boolean voidReturn = false;
	}

	public static final class Dispensers {
		/** Dispensers place block items in front of them instead of dropping them (droppers still drop). */
		public boolean placeBlocks = false;
	}

	public static final class Settings {
		public Anvil anvil = new Anvil();
		public Portals portals = new Portals();
		public Felling felling = new Felling();
		public Veins veins = new Veins();
		public Afk afk = new Afk();
		public TabList tabList = new TabList();
		public Chat chat = new Chat();
		public Grindstone grindstone = new Grindstone();
		public Spawners spawners = new Spawners();
		public Saplings saplings = new Saplings();
		public BoneMeal boneMeal = new BoneMeal();
		public Tridents tridents = new Tridents();
		public Dispensers dispensers = new Dispensers();

		public Settings fill() {
			if (anvil == null) anvil = new Anvil();
			if (portals == null) portals = new Portals();
			if (felling == null) felling = new Felling();
			if (veins == null) veins = new Veins();
			if (afk == null) afk = new Afk();
			if (tabList == null) tabList = new TabList();
			if (chat == null) chat = new Chat();
			if (grindstone == null) grindstone = new Grindstone();
			if (spawners == null) spawners = new Spawners();
			if (saplings == null) saplings = new Saplings();
			if (boneMeal == null) boneMeal = new BoneMeal();
			if (tridents == null) tridents = new Tridents();
			if (dispensers == null) dispensers = new Dispensers();
			if (chat.highlightColor == null || !chat.highlightColor.matches("#[0-9a-fA-F]{6}")) chat.highlightColor = "#FFD166";
			spawners.deniedMobs = ids(spawners.deniedMobs);
			spawners.tools = ids(spawners.tools);
			saplings.items = ids(saplings.items);
			if (saplings.ground == null) saplings.ground = new ArrayList<>(Saplings.GROUND);
			saplings.ground = ids(saplings.ground);
			felling.tools = ids(felling.tools);
			felling.deniedTools = ids(felling.deniedTools);
			felling.logs = ids(felling.logs);
			felling.deniedLogs = ids(felling.deniedLogs);
			felling.leaves = ids(felling.leaves);
			if (felling.sneak == null) felling.sneak = Sneak.DISABLES;
			if (!"shiftDown".equals(felling.breakMode)) felling.breakMode = "instant";
			if (!"cutUpTo".equals(felling.tooBig)) felling.tooBig = "skip";
			veins.tools = ids(veins.tools);
			veins.deniedTools = ids(veins.deniedTools);
			veins.blocks = ids(veins.blocks);
			veins.deniedBlocks = ids(veins.deniedBlocks);
			if (veins.sneak == null) veins.sneak = Sneak.DISABLES;
			if (afk.tag == null) afk.tag = "AFK";
			if (tabList.header == null) tabList.header = "";
			if (tabList.footer == null) tabList.footer = "";
			if (tabList.dimensions == null) tabList.dimensions = defaultDimensions();
			tabList.dimensions.values().removeIf(d -> d == null);
			for (Dimension d : tabList.dimensions.values()) {
				if (d.name == null) d.name = "";
				d.name = d.name.strip();
				if (d.color == null || !d.color.matches("#[0-9a-fA-F]{6}")) d.color = "#AAAAAA";
			}
			return this;
		}
	}

	private Tweaks() {
	}

	/** "axes" → "minecraft:axes", "#axes" → "#minecraft:axes"; blanks and doubles dropped. */
	private static List<String> ids(List<String> list) {
		if (list == null) return new ArrayList<>();
		List<String> out = new ArrayList<>();
		for (String raw : list) {
			if (raw == null || raw.isBlank()) continue;
			String t = raw.strip().toLowerCase(Locale.ROOT);
			boolean tag = t.startsWith("#");
			if (tag) t = t.substring(1);
			if (!t.contains(":")) t = "minecraft:" + t;
			t = (tag ? "#" : "") + t;
			if (!out.contains(t)) out.add(t);
		}
		return out;
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (!(s.grindstone.levelsPerEnchantLevel >= 0 && s.grindstone.levelsPerEnchantLevel <= 10)) return "Grindstone levels per enchantment level are 0 to 10.";
		if (s.grindstone.minLevels < 0 || s.grindstone.maxLevels > 100 || s.grindstone.minLevels > s.grindstone.maxLevels) return "Grindstone costs are 0 to 100 levels, the least not above the most.";
		if (!(s.spawners.durability >= 0 && s.spawners.durability <= 100)) return "Tool wear is 0 to 100 times.";
		if (s.saplings.delaySeconds < 5 || s.saplings.delaySeconds > 600) return "Saplings plant themselves after 5 to 600 seconds.";
		if (s.saplings.spacing < 0 || s.saplings.spacing > 8) return "Sapling spacing is 0 to 8 blocks.";
		if (s.saplings.chance < 1 || s.saplings.chance > 100) return "The chance for saplings is 1 to 100 percent.";
		if (s.boneMeal.maxHeight < 2 || s.boneMeal.maxHeight > 16) return "Bone meal grows plants 2 to 16 blocks tall.";
		if (s.boneMeal.flowerRadius < 1 || s.boneMeal.flowerRadius > 8) return "Flowers spread 1 to 8 blocks.";
		if (s.anvil.renameCost < 1 || s.anvil.renameCost > 39) return "Renaming costs 1 to 39 levels.";
		if (s.portals.maxBlocks < 6 || s.portals.maxBlocks > 4096) return "A portal holds 6 to 4096 blocks.";
		if (s.felling.maxLogs < 2 || s.felling.maxLogs > 4096) return "Trees are 2 to 4096 logs.";
		if (s.felling.minLeaves < 0 || s.felling.minLeaves > 64) return "Leaves needed are 0 to 64.";
		if (s.felling.leafRadius < 1 || s.felling.leafRadius > 16) return "The leaf radius is 1 to 16 blocks.";
		if (!(s.felling.durability >= 0 && s.felling.durability <= 100) || !(s.veins.durability >= 0 && s.veins.durability <= 100)) return "Tool wear is 0 to 100 times.";
		for (Dimension d : s.tabList.dimensions.values()) if (d.name.length() > 32) return "Dimension names are up to 32 characters.";
		if (s.veins.maxBlocks < 2 || s.veins.maxBlocks > 1024) return "Veins are 2 to 1024 blocks.";
		if (s.afk.minutes < 1 || s.afk.minutes > 120) return "AFK after 1 to 120 minutes.";
		if (s.afk.tag.length() > 16) return "The AFK tag is up to 16 characters.";
		if (s.tabList.header.length() > 500 || s.tabList.footer.length() > 500) return "Header and footer are up to 500 characters each.";
		for (String id : s.spawners.deniedMobs) if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + id + "\" isn't a mob id.";
		for (List<String> l : List.of(s.spawners.tools, s.saplings.items, s.saplings.ground, s.felling.tools, s.felling.deniedTools, s.felling.logs, s.felling.deniedLogs, s.felling.leaves,
				s.veins.tools, s.veins.deniedTools, s.veins.blocks, s.veins.deniedBlocks)) {
			for (String id : l) if (!id.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + id + "\" isn't an id or #tag.";
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
