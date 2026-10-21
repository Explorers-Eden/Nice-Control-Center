package eu.explorerseden.nicecontrolcenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pets: floating player heads that follow their owner (/pets). The panel puts pets in the pet shop (a price
 * in the market's currency, how long the shop sells it, how many can be sold); players buy them from
 * /market or /pets, keep them for good, name them and pick how theirs moves. Each pet can have up to two
 * perks (small helps) that unlock as its bond with its owner grows, and every pet reacts to what happens.
 *
 * Settings in config/nicecontrolcenter-pets.json (written by the panel), who owns what in the world folder
 * (nicecontrolcenter-pets.json). Minecraft-free.
 */
public final class Pets {
	public static final String FILE = "nicecontrolcenter-pets.json";
	public static final String DATA_FILE = "nicecontrolcenter-pets.json";
	private static final int PURCHASES = 1000;
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{32,128}");
	private static final Pattern TEXTURE_URL = Pattern.compile("^https?://textures\\.minecraft\\.net/texture/([0-9a-fA-F]{32,128})$");

	private Pets() {
	}

	/** How a pet moves. */
	public enum Behavior {
		FLOAT("Float", "Hovers next to your shoulder"),
		SHOULDER("Shoulder", "Sits still on your shoulder"),
		FOLLOW("Follow", "Trails behind you"),
		LAZY("Lazy", "Stays put until you walk off, then catches up"),
		PEEK("Peek", "Hides behind your head and peeks over your shoulders"),
		BOUNCE("Bounce", "Hops up and down next to you"),
		ORBIT("Orbit", "Circles around your head"),
		HALO("Halo", "Spins in a small ring above your head"),
		SPIRAL("Spiral", "Winds up and down around you"),
		FIGURE_EIGHT("Figure Eight", "Loops a figure eight around you"),
		WANDER("Wander", "Drifts around you on its own"),
		SPIN("Spin", "Spins on the spot above your head"),
		WOBBLE("Wobble", "Floats next to you, wobbling its head"),
		GUIDE("Guide", "Floats ahead of you and looks back at you"),
		SLEEPY("Sleepy", "Dozes low by your feet"),
		PENDULUM("Pendulum", "Swings from side to side behind your head"),
		ZIGZAG("Zigzag", "Zips left and right behind you"),
		SATELLITE("Satellite", "Circles you over and under on a tilted ring"),
		DART("Dart", "Darts to a new spot around you every few seconds"),
		RANDOM("Random", "A different one every time you teleport, die or take your pet out");

		public final String label;
		public final String description;

		Behavior(String label, String description) {
			this.label = label;
			this.description = description;
		}

		public static Behavior of(String name) {
			if (name != null) for (Behavior b : values()) if (b.name().equalsIgnoreCase(name)) return b;
			return FLOAT;
		}
	}

	/** The trail a pet can leave (the mod turns these into the game's particles). */
	public enum Trail {
		OFF("Off", 0),
		SPARKLES("Wisps", 10),
		HEARTS("Hearts", 30),
		NOTES("Notes", 24),
		FLAMES("Flames", 10),
		SOUL_FLAMES("Soul Flames", 10),
		SNOW("Snow", 10),
		GLOW("Glow", 12),
		PETALS("Cherry Petals", 12),
		MAGIC("Magic", 6),
		FIREFLIES("Fireflies", 20),
		FIREWORKS("Fireworks", 12),
		TOTEM("Totem", 10),
		SOULS("Souls", 16),
		PORTAL("Portal", 6),
		NAUTILUS("Nautilus", 6),
		SPORES("Spores", 12),
		ASH("Ash", 8),
		PALE_LEAVES("Pale Leaves", 14),
		HAPPY("Happy", 14),
		AUTUMN_LEAVES("Autumn Leaves", 8),
		LUSH_LEAVES("Lush Leaves", 8),
		WITCH("Witch", 10),
		OMINOUS("Ominous", 8),
		RAID_OMEN("Raid Omen", 10),
		// Big and slow to fade: only now and then.
		CAMPFIRE_SMOKE("Campfire Smoke", 70),
		NOXIOUS_GAS("Noxious Gas", 50),
		RANDOM("Random", 0);

		public final String label;
		/** One particle every this many ticks. */
		public final int every;

		Trail(String label, int every) {
			this.label = label;
			this.every = every;
		}

		/** Trails a Random pick can land on. */
		public boolean real() {
			return this != OFF && this != RANDOM;
		}

		public static Trail of(String name) {
			if (name != null) for (Trail t : values()) if (t.name().equalsIgnoreCase(name)) return t;
			return OFF;
		}
	}

	/** Small helps a pet can have (two per pet, set in the panel; they unlock with the pet's bond level). */
	public enum Perk {
		LANTERN("Lantern", "Glows and lights up the dark around you", Group.HELPER),
		NIGHT_EYES("Night Eyes", "Glows, and brighter still deep underground", Group.HELPER),
		FETCH("Fetch", "Brings you what dropped from blocks you broke and mobs you killed", Group.HELPER),
		TRUFFLE("Truffle Nose", "Sniffs out suspicious sand and gravel nearby", Group.HELPER),
		FISHING("Fishing", "Now and then dips into water nearby and brings you a catch", Group.HELPER),
		GARDENER("Gardener", "Now and then helps a crop next to you grow", Group.HELPER),
		TORCHES("Torch Bearer", "Places one of your torches where it's dark enough for monsters", Group.HELPER),
		MINER("Miner", "Mines ore you can see and widens your tunnel, with your pickaxe", Group.HELPER),
		CHOPPER("Wood Chopper", "Helps chop the tree you started on, with your axe", Group.HELPER),
		GIFTS("Small Gifts", "Now and then finds something small for you", Group.STRONG),
		SECOND_WIND("Second Wind", "Heals you a little when you're nearly dead, now and then", Group.STRONG),
		NIPPER("Nipper", "Nips monsters that hurt you or that you attack, like a wolf", Group.STRONG),
		SHIELD("Shield", "Pushes monsters back that come too close", Group.STRONG);

		public enum Group { HELPER, STRONG }

		public final String label;
		public final String description;
		public final Group group;

		Perk(String label, String description, Group group) {
			this.label = label;
			this.description = description;
			this.group = group;
		}

		/** Null when it isn't one. */
		public static Perk of(String name) {
			if (name != null) for (Perk p : values()) if (p.name().equalsIgnoreCase(name)) return p;
			return null;
		}
	}

	/**
	 * What pets react to: a short move, particles, a sound and maybe a speech bubble (one of the lines, which
	 * admins can change). Warnings also tell the owner on their screen.
	 */
	public enum Reaction {
		TOOL("Tool about to break", true, 60, "It's breaking!", "Careful with that!"),
		HEALTH("Low health", true, 60, "Careful…", "Ouch…", "Heal up!"),
		HUNGER("Hungry", true, 120, "Food?", "Snack time?", "*rumble*"),
		DANGER("Danger", true, 30, "Behind you!", "Look out!", "!!"),
		AIR("Low air", true, 20, "Air!", "Up, up!"),
		INVENTORY("Inventory full", true, 120, "Full!", "No room!"),
		FIRE("Fire or lava", true, 30, "Hot hot!", "Fire!"),
		HURT("Owner hurt", false, 10, "!", "Hey!"),
		NIGHTFALL("Nightfall", false, 600, "☾", "Night's coming…"),
		UNDERWATER("Under water", false, 120, "blub", "o o o"),
		NETHER("Into the Nether", false, 60, "Hot!", "So warm…"),
		END("Into the End", false, 60, "…?", "Whoa…"),
		FISH("Fish caught", false, 10, "!", "Fish!", "Yum!"),
		HARVEST("Harvest", false, 60, "Nice!", "Crops!"),
		DEEP("Deep down", false, 600, "Dig dig", "So deep…"),
		SNOW("Snow", false, 600, "Brr", "Cold!"),
		HELLO("Taken out", false, 0, "Hi!", "Hello!", "Let's go!"),
		BYE("Put away", false, 0, "Bye!", "See you!"),
		JOIN("Owner joins", false, 0, "Missed you!", "You're back!"),
		EAT("Eating", false, 30, "Yum!", "Share?"),
		SLEEP("Sleeping", false, 60, "zZz"),
		MORNING("Morning", false, 600, "☀", "Morning!"),
		RESPAWN("Owner died", false, 0, "…", "Don't do that again…"),
		ADVANCEMENT("Advancement", false, 5, "★", "Well done!", "Woo!"),
		LEVEL30("Level 30", false, 60, "Whoa!", "So much XP!"),
		SHINY("Rare find", false, 10, "Shiny!", "Ooh!"),
		RAIN("Rain", false, 600, "☂", "Rain…"),
		THUNDER("Thunder", false, 120, "!!", "Eek!"),
		FLYING("Flying or falling", false, 30, "Wheee!", "Whoosh!"),
		RIDING("Riding", false, 60, "Giddy up!", "♪"),
		TRADE("Trading", false, 60, "$", "Good deal!"),
		BREED("Breeding", false, 60, "♥", "Babies!"),
		MEET("Meeting another pet", false, 600, "♥", "Hi friend!"),
		IDLE("Owner idle", false, 300, "…", "Bored…"),
		AFK("Owner AFK", false, 60, "zZz"),
		PAT("Pat", false, 3, "♥", "Hehe"),
		LEVEL_UP("Bond level up", false, 0, "Level {level}!", "Yay!"),
		BIRTHDAY("Pet's birthday", false, 0, "Happy pet day!", "✦ Party! ✦"),
		TOTEM("Totem used", false, 0, "That was close!", "Phew…"),
		KILL("Monster beaten", false, 120, "Got it!", "Take that!"),
		BOSS("Boss beaten", false, 0, "WE DID IT!", "Champion!"),
		ENCHANT("Enchanting", false, 60, "Sparkly!", "Magic!"),
		MUSIC("Music playing", false, 120, "♪", "♫ ♪"),
		BELL("Bell rung", false, 60, "Ding!", "Dong!"),
		DARK("Pitch dark", false, 300, "It's dark…", "Light?"),
		HIGH("Way up high", false, 600, "So high!", "Don't fall!"),
		SUNNY("Rain stops", false, 600, "☀", "Sun's back!"),
		RAID("Raid", false, 300, "Raid!", "Here they come!");

		public final String label;
		/** Also tells the owner on their screen (and doesn't wait for the gap between reactions). */
		public final boolean warning;
		/** The least time between two of these for one pet. */
		public final int cooldown;
		public final List<String> lines;

		Reaction(String label, boolean warning, int cooldown, String... lines) {
			this.label = label;
			this.warning = warning;
			this.cooldown = cooldown;
			this.lines = List.of(lines);
		}

		public static Reaction of(String name) {
			if (name != null) for (Reaction r : values()) if (r.name().equalsIgnoreCase(name)) return r;
			return null;
		}
	}

	/** A reaction as the panel set it: on or off, and its bubble lines (none: no bubble). */
	public static final class ReactionEntry {
		public boolean on = true;
		public List<String> lines = new ArrayList<>();
	}

	public static final class ReactionSettings {
		public boolean enabled = true;
		/** The least time between two reactions of one pet (warnings don't wait). */
		public int gapSeconds = 20;
		public Map<String, ReactionEntry> list = new LinkedHashMap<>();

		public ReactionSettings fill() {
			if (list == null) list = new LinkedHashMap<>();
			list.keySet().removeIf(k -> Reaction.of(k) == null);
			for (Reaction r : Reaction.values()) {
				ReactionEntry e = list.get(r.name());
				if (e == null) {
					e = new ReactionEntry();
					e.lines = new ArrayList<>(r.lines);
					list.put(r.name(), e);
				}
				if (e.lines == null) e.lines = new ArrayList<>();
				e.lines.replaceAll(l -> l == null ? "" : l.replaceAll("\\s+", " ").strip());
				e.lines.removeIf(String::isEmpty);
			}
			return this;
		}

		public ReactionEntry entry(Reaction r) {
			ReactionEntry e = list.get(r.name());
			return e != null ? e : new ReactionEntry();
		}
	}

	/** Something Small Gifts can find. */
	public static final class Gift {
		public String item;
		public int min = 1;
		public int max = 1;
		public int weight = 1;

		public Gift() {
		}

		public Gift(String item, int min, int max, int weight) {
			this.item = item;
			this.min = min;
			this.max = max;
			this.weight = weight;
		}
	}

	/** Bond levels and how strong each perk is. */
	public static final class PerkSettings {
		public boolean enabled = false;
		/** A pet's first and second perk work from these bond levels on (0: right away). */
		public int slot1Level = 2;
		public int slot2Level = 5;
		/** Level n takes this many minutes × n of the pet being out with its owner active (30: level 1 after 30 min, level 5 after 7.5 h). */
		public int levelMinutes = 30;
		public int maxLevel = 10;
		public int lanternLevel = 10;
		public int nightEyesLevel = 14;
		public int fetchRadius = 8;
		public int truffleRadius = 8;
		public int fishingMinutes = 5;
		public int gardenerSeconds = 120;
		public int torchSeconds = 20;
		public int minerSeconds = 10;
		public int chopperSeconds = 10;
		public int giftMinutes = 30;
		public List<Gift> gifts = defaultGifts();
		public int secondWindMinutes = 30;
		/** Damage per nip (2 = one heart) and the time between nips. */
		public double nipperDamage = 2;
		public double nipperSeconds = 1.5;
		public int shieldSeconds = 5;

		public PerkSettings fill() {
			if (gifts == null) gifts = defaultGifts();
			gifts.removeIf(g -> g == null || g.item == null || g.item.isBlank());
			for (Gift g : gifts) {
				g.item = g.item.strip().toLowerCase(Locale.ROOT);
				if (!g.item.contains(":")) g.item = "minecraft:" + g.item;
			}
			return this;
		}
	}

	public static List<Gift> defaultGifts() {
		return new ArrayList<>(List.of(new Gift("minecraft:bone_meal", 1, 3, 4), new Gift("minecraft:wheat_seeds", 2, 5, 3),
				new Gift("minecraft:string", 1, 3, 3), new Gift("minecraft:bone", 1, 2, 2), new Gift("minecraft:stick", 2, 4, 2),
				new Gift("minecraft:feather", 1, 2, 2), new Gift("minecraft:flint", 1, 1, 1)));
	}

	/** The total active minutes a pet needs for this bond level. */
	public static long minutesFor(int level, PerkSettings s) {
		return (long) s.levelMinutes * level * (level + 1) / 2;
	}

	/** The bond level for this many ticks of being out with its owner active. */
	public static int level(long bondTicks, PerkSettings s) {
		long minutes = bondTicks / 1200;
		int level = 0;
		while (level < s.maxLevel && minutesFor(level + 1, s) <= minutes) level++;
		return level;
	}

	/** How many of a pet's perks work at this bond level (0 to 2). */
	public static int unlockedSlots(int level, PerkSettings s) {
		return (level >= s.slot1Level ? 1 : 0) + (level >= s.slot2Level ? 1 : 0);
	}

	/** The bond level a perk slot (0 or 1) unlocks at. */
	public static int slotLevel(int slot, PerkSettings s) {
		return slot == 0 ? s.slot1Level : s.slot2Level;
	}

	/** A pet's name from its owner: plain text, at most 16 characters (empty: none). */
	public static String nickname(String in) {
		if (in == null) return "";
		String t = in.replaceAll("§.?", "").replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
		return t.length() > 16 ? t.substring(0, 16).strip() : t;
	}

	/** A pet in the shop. */
	public static final class Pet {
		public String id;
		public String name;
		/** Shown to players in the pet menus and on the info page; empty for none. */
		public String description = "";
		/** The head's skin: the base64 "textures" value (like on a player head). */
		public String texture;
		/** Head size (1 = half a block, like a player head); 0 until {@link Settings#fill()} sets it (files from before it was per pet). */
		public double size;
		/** In the market's currency; 0 is free. */
		public int price;
		/** The shop sells it until then (epoch ms); 0 is always. Bought pets stay with their players for good. */
		public long saleEnds;
		/** How many can be sold in all; 0 is no limit. */
		public int stock;
		/** Up to two perks: the first unlocks at bond level {@link PerkSettings#slot1Level}, the second at slot2Level. */
		public List<String> perks = new ArrayList<>();
		/** Off keeps the pet (and the ones players own) but stops selling it. */
		public boolean forSale = true;
		/**
		 * Taken out of the shop while players still have it: never sold again, but kept so its name and head
		 * can still be changed. The panel drops it once nobody has it any more.
		 */
		public boolean retired;

		/** In the shop right now (apart from being sold out). */
		public boolean onSale() {
			return forSale && !retired && (saleEnds == 0 || System.currentTimeMillis() < saleEnds);
		}

		/** The sale was limited in time and is over. */
		public boolean saleOver() {
			return saleEnds != 0 && System.currentTimeMillis() >= saleEnds;
		}
	}

	/** A new pet's head size. */
	public static final double DEFAULT_SIZE = 0.8;

	public static final class Settings {
		public boolean enabled = false;
		/** Before 1.13.12 one head size for every pet; now only what older pets start with (see {@link Pet#size}). */
		public double size = DEFAULT_SIZE;
		/** Players may give their pet a particle trail. */
		public boolean particles = true;
		public List<Pet> pets = new ArrayList<>();
		public PerkSettings perks = new PerkSettings();
		/** Players may name their pets (shown above them). */
		public boolean names = true;
		/** Colours offered for pet names (name → #RRGGBB). */
		public Map<String, String> nameColors = Claims.defaultColors();
		public ReactionSettings reactions = new ReactionSettings();

		public Settings fill() {
			if (pets == null) pets = new ArrayList<>();
			pets.removeIf(p -> p == null);
			if (perks == null) perks = new PerkSettings();
			perks.fill();
			if (nameColors == null || nameColors.isEmpty()) nameColors = Claims.defaultColors();
			if (reactions == null) reactions = new ReactionSettings();
			reactions.fill();
			for (Pet p : pets) {
				if (p.id == null || p.id.isBlank()) p.id = UUID.randomUUID().toString().substring(0, 8);
				if (p.name != null) p.name = p.name.strip();
				if (!(p.size > 0)) p.size = size >= 0.3 && size <= 2 ? size : DEFAULT_SIZE;
				p.description = p.description == null ? "" : p.description.replaceAll("\\s+", " ").strip();
				if (p.perks == null) p.perks = new ArrayList<>();
				List<String> perks = new ArrayList<>();
				for (String n : p.perks) {
					Perk k = Perk.of(n);
					if (k != null && !perks.contains(k.name())) perks.add(k.name());
				}
				p.perks = perks;
				String t = texture(p.texture);
				if (t != null) p.texture = t;
			}
			return this;
		}

		public Pet pet(String id) {
			for (Pet p : pets) if (p.id.equals(id)) return p;
			return null;
		}
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (!(s.size >= 0.3 && s.size <= 2)) return "The head size is 0.3 to 2.";
		if (s.pets.size() > 200) return "At most 200 pets.";
		Set<String> ids = new HashSet<>();
		for (Pet p : s.pets) {
			if (!p.id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(p.id)) return "A pet has a bad or doubled id.";
			if (p.name == null || p.name.isEmpty() || p.name.length() > 32) return "Every pet needs a name of 1 to 32 characters.";
			if (!(p.size >= 0.3 && p.size <= 2)) return "The head size of " + p.name + " is 0.3 to 2.";
			if (p.description.length() > 200) return "The description of " + p.name + " is up to 200 characters.";
			if (texture(p.texture) == null) return "The head texture of " + p.name + " isn't a skin value (base64 from a head generator) or a textures.minecraft.net link.";
			if (p.price < 0 || p.price > 10_000_000) return "The price of " + p.name + " is 0 to 10,000,000.";
			if (p.saleEnds < 0 || p.saleEnds > System.currentTimeMillis() + 3660 * 86_400_000L) return "The sale of " + p.name + " ends within ten years, or never.";
			if (p.stock < 0 || p.stock > 1_000_000) return "The amount of " + p.name + " is 0 (no limit) to 1,000,000.";
			if (p.perks.size() > 2) return p.name + " has more than two perks.";
			if (p.perks.contains(Perk.LANTERN.name()) && p.perks.contains(Perk.NIGHT_EYES.name())) return p.name + ": Night Eyes already is a lantern; pick one of the two.";
		}
		PerkSettings k = s.perks;
		if (k.maxLevel < 1 || k.maxLevel > 100) return "The highest bond level is 1 to 100.";
		if (k.slot1Level < 0 || k.slot1Level > k.maxLevel) return "The first perk unlocks at level 0 to " + k.maxLevel + ".";
		if (k.slot2Level < k.slot1Level || k.slot2Level > k.maxLevel) return "The second perk unlocks at level " + k.slot1Level + " to " + k.maxLevel + ".";
		if (k.levelMinutes < 1 || k.levelMinutes > 10_000) return "Minutes per level are 1 to 10,000.";
		if (k.lanternLevel < 1 || k.lanternLevel > 15 || k.nightEyesLevel < 1 || k.nightEyesLevel > 15) return "Lantern light is 1 to 15.";
		if (k.fetchRadius < 1 || k.fetchRadius > 10) return "Fetch reaches 1 to 10 blocks.";
		if (k.truffleRadius < 1 || k.truffleRadius > 16) return "Truffle Nose sniffs 1 to 16 blocks.";
		if (k.fishingMinutes < 1 || k.fishingMinutes > 1440) return "Fishing is every 1 to 1440 minutes.";
		if (k.gardenerSeconds < 5 || k.gardenerSeconds > 86_400) return "Gardener is every 5 to 86,400 seconds.";
		if (k.torchSeconds < 1 || k.torchSeconds > 3600) return "Torch Bearer waits 1 to 3600 seconds.";
		if (k.minerSeconds < 1 || k.minerSeconds > 3600 || k.chopperSeconds < 1 || k.chopperSeconds > 3600) return "Miner and Wood Chopper wait 1 to 3600 seconds.";
		if (k.giftMinutes < 1 || k.giftMinutes > 10_080) return "Small Gifts come every 1 to 10,080 minutes.";
		if (k.secondWindMinutes < 1 || k.secondWindMinutes > 10_080) return "Second Wind waits 1 to 10,080 minutes.";
		if (!(k.nipperDamage >= 0.5 && k.nipperDamage <= 20)) return "Nipper does 0.5 to 20 damage.";
		if (!(k.nipperSeconds >= 0.5 && k.nipperSeconds <= 60)) return "Nipper nips every 0.5 to 60 seconds.";
		if (k.shieldSeconds < 1 || k.shieldSeconds > 600) return "Shield pushes every 1 to 600 seconds.";
		if (k.gifts.size() > 50) return "At most 50 gifts.";
		for (Gift g : k.gifts) {
			if (!g.item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + g.item + "\" isn't an item id (like minecraft:bone_meal).";
			if (g.min < 1 || g.max < g.min || g.max > 64) return "Gift amounts are 1 to 64, the most at least the fewest.";
			if (g.weight < 1 || g.weight > 1000) return "Gift weights are 1 to 1000.";
		}
		if (s.nameColors.size() > 32) return "At most 32 name colours.";
		for (Map.Entry<String, String> e : s.nameColors.entrySet()) {
			if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 24) return "Colour names are 1 to 24 characters.";
			if (Claims.parseColor(e.getValue()) < 0) return "\"" + e.getValue() + "\" isn't a #RRGGBB colour.";
		}
		if (s.reactions.gapSeconds < 0 || s.reactions.gapSeconds > 3600) return "The time between reactions is 0 to 3600 seconds.";
		for (Map.Entry<String, ReactionEntry> e : s.reactions.list.entrySet()) {
			if (e.getValue().lines.size() > 10) return Reaction.of(e.getKey()).label + ": at most 10 lines.";
			for (String l : e.getValue().lines) if (l.length() > 32) return Reaction.of(e.getKey()).label + ": lines are up to 32 characters.";
		}
		return null;
	}

	/**
	 * A head texture in its stored form (the base64 value), from that value, a textures.minecraft.net link
	 * or the bare skin hash. Null when it's none of these.
	 */
	public static String texture(String in) {
		if (in == null) return null;
		String t = in.strip();
		if (t.isEmpty()) return null;
		Matcher url = TEXTURE_URL.matcher(t);
		String hash = url.matches() ? url.group(1).toLowerCase(Locale.ROOT) : HASH.matcher(t.toLowerCase(Locale.ROOT)).matches() ? t.toLowerCase(Locale.ROOT) : null;
		if (hash != null) {
			String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		}
		return skinHash(t) == null ? null : t;
	}

	/** The skin's hash on textures.minecraft.net from the base64 value, or null. */
	public static String skinHash(String texture) {
		if (texture == null) return null;
		try {
			String json = new String(Base64.getDecoder().decode(texture.strip()), StandardCharsets.UTF_8);
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String url = o.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
			Matcher m = TEXTURE_URL.matcher(url.strip());
			return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
		} catch (RuntimeException e) {
			// Not base64, not JSON or no skin in it.
			return null;
		}
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
		writeJson(file, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings));
	}

	/** "13 Nov 2026": the day a sale ends. */
	public static String date(long time) {
		return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(time));
	}

	// ---------------------------------------------------------------- who owns what

	/** A pet a player has: a copy of its name and head, so it stays even if the shop drops it. */
	public static final class Owned {
		public String pet;
		public String name;
		public String texture;
		public long since;
		/** Before 1.13.0's release each pet had its own way of moving; now it's the owner's ({@link Owner#behavior}). */
		public String behavior;
		/** Ticks it was out with its owner active: its bond (see {@link #level}). */
		public long bond;
		/** The name its owner gave it (empty: none) and its colour (#RRGGBB). */
		public String nickname = "";
		public String nameColor = "#FFFFFF";
	}

	public static final class Owner {
		public String name;
		public List<Owned> pets = new ArrayList<>();
		/** The pet that's out, or null. */
		public String active;
		/** How their pet moves, and its particle trail (for whichever pet is out). */
		public String behavior;
		public String trail;
		/** While a pet is out, show a random one of theirs instead (picked again after a teleport, death or taking it out). */
		public boolean random;
		/** The pet that was out last, for /pets on. */
		public String last;
		/** Perks the owner switched off for their pets. */
		public Set<String> perksOff = new HashSet<>();
		/** Their pets react to what happens (moves, particles, bubbles). */
		public boolean reactions = true;
		/** The name they gave their pet floats above it (false: nobody sees it). */
		public boolean showName = true;
		/** Active ticks towards the next Small Gift, and when Second Wind can help again (epoch ms). */
		public long giftTicks;
		public long secondWindAt;

		/** Puts the pet away, remembering it for /pets on. */
		public void putAway() {
			if (active != null) last = active;
			active = null;
		}

		/** Takes out the pet that was out last (or the first one). Null when they have none. */
		public Owned takeOut() {
			Owned o = last == null ? null : owned(last);
			if (o == null && !pets.isEmpty()) o = pets.get(0);
			if (o != null) active = o.pet;
			return o;
		}

		public Owned owned(String pet) {
			for (Owned o : pets) if (o.pet.equals(pet)) return o;
			return null;
		}
	}

	public static final class Purchase {
		public String pet;
		public String petName;
		public String buyer;
		public String buyerName;
		public int price;
		public long time;
	}

	public static final class Data {
		/** Pets sold so far, per pet id (for the shop's amount). */
		public Map<String, Integer> sold = new LinkedHashMap<>();
		public Map<String, Owner> owners = new LinkedHashMap<>();
		public List<Purchase> purchases = new ArrayList<>();

		public Data fill() {
			if (sold == null) sold = new LinkedHashMap<>();
			if (owners == null) owners = new LinkedHashMap<>();
			if (purchases == null) purchases = new ArrayList<>();
			owners.values().removeIf(o -> o == null);
			for (Owner o : owners.values()) {
				if (o.pets == null) o.pets = new ArrayList<>();
				o.pets.removeIf(p -> p == null || p.pet == null || p.texture == null);
				if (o.perksOff == null) o.perksOff = new HashSet<>();
				for (Owned p : o.pets) {
					p.nickname = nickname(p.nickname);
					if (Claims.parseColor(p.nameColor) < 0) p.nameColor = "#FFFFFF";
				}
				if (o.behavior == null) {
					Owned out = o.active == null ? null : o.owned(o.active);
					o.behavior = Behavior.of(out != null ? out.behavior : null).name();
				}
				if (o.trail == null) o.trail = Trail.OFF.name();
			}
			return this;
		}

		public Owner owner(String uuid, String name) {
			Owner o = owners.computeIfAbsent(uuid, k -> {
				Owner n = new Owner();
				n.behavior = Behavior.FLOAT.name();
				n.trail = Trail.OFF.name();
				return n;
			});
			if (name != null) o.name = name;
			return o;
		}

		public int sold(String pet) {
			return sold.getOrDefault(pet, 0);
		}

		/** How many are left to buy, or -1 for no limit. */
		public int left(Pet p) {
			return p.stock <= 0 ? -1 : Math.max(0, p.stock - sold(p.id));
		}

		/** Why the player can't buy the pet now (apart from paying), or null. */
		public String cannotBuy(String uuid, Pet p) {
			if (!p.onSale()) return "That pet isn't for sale any more.";
			if (left(p) == 0) return "That pet is sold out.";
			Owner o = owners.get(uuid);
			Owned have = o == null ? null : o.owned(p.id);
			if (have != null) return "You already have " + p.name + ".";
			return null;
		}

		/** A purchase (already paid): the pet is the player's for good. */
		public void buy(String uuid, String name, Pet p, long now) {
			Owner o = owner(uuid, name);
			Owned have = o.owned(p.id);
			if (have == null) {
				have = new Owned();
				have.pet = p.id;
				have.since = now;
				o.pets.add(have);
			}
			have.name = p.name;
			have.texture = p.texture;
			sold.merge(p.id, 1, Integer::sum);
			Purchase b = new Purchase();
			b.pet = p.id;
			b.petName = p.name;
			b.buyer = uuid;
			b.buyerName = name;
			b.price = p.price;
			b.time = now;
			purchases.add(b);
			while (purchases.size() > PURCHASES) purchases.remove(0);
		}

		/** An admin gives the pet (not a sale). False when they have it already. */
		public boolean give(String uuid, String name, Pet p, long now) {
			Owner o = owner(uuid, name);
			if (o.owned(p.id) != null) return false;
			Owned have = new Owned();
			have.pet = p.id;
			have.since = now;
			have.name = p.name;
			have.texture = p.texture;
			o.pets.add(have);
			return true;
		}

		/** Takes a pet away from a player. False when they didn't have it. */
		public boolean take(String uuid, String pet) {
			Owner o = owners.get(uuid);
			if (o == null || !o.pets.removeIf(p -> p.pet.equals(pet))) return false;
			if (pet.equals(o.active)) o.active = null;
			return true;
		}
	}

	public static Data readData(Path file) throws IOException {
		if (!Files.exists(file)) return new Data().fill();
		Data d = Json.GSON.fromJson(Files.readString(file), Data.class);
		return d == null ? new Data().fill() : d.fill();
	}

	public static void writeData(Path file, Data data) throws IOException {
		writeJson(file, Json.GSON.toJson(data));
	}

	private static void writeJson(Path file, String json) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
