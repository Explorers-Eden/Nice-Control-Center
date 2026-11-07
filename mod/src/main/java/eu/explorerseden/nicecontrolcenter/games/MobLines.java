package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;

/**
 * Mob lines as dungeon markers and wave arenas write them: "zombie", "minecraft:skeleton x3",
 * "husk x2 {CustomName:'Guard'}"; several in one text with commas ("husk x6, skeleton x2"). Summoned like the
 * /summon command, so their data works the same everywhere. Every game mob carries the tag
 * mob_manager.settings.exclude (Nice Mob Manager leaves it alone), and mobs with data-driven variants (cows, pigs,
 * chickens, cats, wolves, frogs, zombie nautiluses; also what packs like Nice Mob Variants add) get a random one and a
 * random sound, never an "ender" one, unless the line sets its own.
 */
public final class MobLines {
	/** One line: the mob, how many, its data ("" or "{…}"). */
	public record Entry(String id, int count, String nbt) {
	}

	private MobLines() {
	}

	/** The variant registries of each mob with data-driven variants: its look, then its sound. */
	private static final java.util.Map<String, List<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>>> VARIANTS = java.util.Map.of(
			"minecraft:cow", List.of(net.minecraft.core.registries.Registries.COW_VARIANT, net.minecraft.core.registries.Registries.COW_SOUND_VARIANT),
			"minecraft:pig", List.of(net.minecraft.core.registries.Registries.PIG_VARIANT, net.minecraft.core.registries.Registries.PIG_SOUND_VARIANT),
			"minecraft:chicken", List.of(net.minecraft.core.registries.Registries.CHICKEN_VARIANT, net.minecraft.core.registries.Registries.CHICKEN_SOUND_VARIANT),
			"minecraft:cat", List.of(net.minecraft.core.registries.Registries.CAT_VARIANT, net.minecraft.core.registries.Registries.CAT_SOUND_VARIANT),
			"minecraft:wolf", List.of(net.minecraft.core.registries.Registries.WOLF_VARIANT, net.minecraft.core.registries.Registries.WOLF_SOUND_VARIANT),
			"minecraft:frog", List.of(net.minecraft.core.registries.Registries.FROG_VARIANT),
			"minecraft:zombie_nautilus", List.of(net.minecraft.core.registries.Registries.ZOMBIE_NAUTILUS_VARIANT));
	private static final java.util.Random RANDOM = new java.util.Random();

	/**
	 * ",variant:\"…\",sound_variant:\"…\"" picked at random from what the server knows (packs included), never an
	 * "ender" one; nothing when the line sets its own or the mob has no variants.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static String variants(MinecraftServer server, Entry e) {
		List<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>> regs = VARIANTS.get(e.id());
		if (regs == null) return "";
		StringBuilder out = new StringBuilder();
		String[] keys = { "variant", "sound_variant" };
		for (int i = 0; i < regs.size(); i++) {
			if (java.util.regex.Pattern.compile("(?<![A-Za-z_])\"?" + keys[i] + "\"?\\s*:").matcher(e.nbt()).find()) continue;
			List<String> ids = new ArrayList<>();
			try {
				server.registryAccess().lookupOrThrow((net.minecraft.resources.ResourceKey) regs.get(i)).listElementIds().forEach(k -> {
					String id = ((net.minecraft.resources.ResourceKey<?>) k).identifier().toString();
					if (!id.toLowerCase(Locale.ROOT).contains("ender")) ids.add(id);
				});
			} catch (RuntimeException ex) {
				continue;
			}
			if (!ids.isEmpty()) out.append(",").append(keys[i]).append(":\"").append(ids.get(RANDOM.nextInt(ids.size()))).append("\"");
		}
		return out.toString();
	}

	/** One line; null when there's no mob in it. */
	public static Entry parse(String line) {
		String text = line == null ? "" : line.strip();
		int brace = text.indexOf('{');
		String nbt = brace >= 0 ? text.substring(brace).strip() : "";
		String[] words = (brace >= 0 ? text.substring(0, brace) : text).strip().split("\\s+");
		if (words.length == 0 || words[0].isEmpty()) return null;
		String id = words[0].contains(":") ? words[0] : "minecraft:" + words[0];
		int count = 1;
		for (String w : words) if (w.matches("[xX]\\d{1,2}")) count = Math.max(1, Math.min(32, Integer.parseInt(w.substring(1))));
		return new Entry(id.toLowerCase(Locale.ROOT), count, nbt);
	}

	/** Several lines split at commas (not the ones inside {…}, [...] or quotes). */
	public static List<Entry> parseList(String text) {
		List<Entry> out = new ArrayList<>();
		if (text == null) return out;
		int depth = 0;
		char quote = 0;
		StringBuilder cur = new StringBuilder();
		for (char ch : text.toCharArray()) {
			if (quote != 0) {
				if (ch == quote) quote = 0;
			} else if (ch == '"' || ch == '\'') {
				quote = ch;
			} else if (ch == '{' || ch == '[') {
				depth++;
			} else if (ch == '}' || ch == ']') {
				depth = Math.max(0, depth - 1);
			} else if (ch == ',' && depth == 0) {
				Entry e = parse(cur.toString());
				if (e != null) out.add(e);
				cur.setLength(0);
				continue;
			}
			cur.append(ch);
		}
		Entry e = parse(cur.toString());
		if (e != null) out.add(e);
		return out;
	}

	/** Nice Mob Manager leaves mobs with this tag alone. */
	public static final String NMM_EXCLUDE = "mob_manager.settings.exclude";
	/** Where Nice Mob Manager changes nothing at all: the game dimension and the dungeons. */
	private static final java.util.Set<String> NO_MOB_MANAGER = java.util.Set.of("eden:flying_fair", eu.explorerseden.nicecontrolcenter.Dungeons.DIMENSION);

	/**
	 * Every entity in Flying Fair and the dungeon dimension gets Nice Mob Manager's exclude tag the moment it's there
	 * (spawned, loaded with its chunk, or come through a portal), before the datapack's next look: no new health, speed,
	 * size or loot for game mobs, decoration or anything else there.
	 */
	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof net.minecraft.world.entity.player.Player || entity.entityTags().contains(NMM_EXCLUDE)) return;
			if (NO_MOB_MANAGER.contains(level.dimension().identifier().toString())) entity.addTag(NMM_EXCLUDE);
		});
	}

	/** The weapon a mob spawns with in the wild (null = none or not always). */
	// ---------------------------------------------------------------- melee or ranged

	/** Hostile mobs that shoot or cast from afar without a weapon to hold. */
	private static final java.util.Set<String> NATIVE_RANGED = java.util.Set.of("blaze", "ghast", "shulker", "breeze", "evoker", "witch");
	private static final java.util.Map<String, String> KINDS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.regex.Pattern MAINHAND = java.util.regex.Pattern.compile("mainhand:\\{id:\"([^\"]+)\"");

	/**
	 * "ranged" or "melee" for a mob line, from what the mob really does: the ones that only shoot (blazes, ghasts,
	 * witches…) are ranged; the ones that can shoot (skeletons, pillagers, piglins, drowned…) only with a bow,
	 * crossbow or trident in hand (from the line's data, else what they'd get anyway); peaceful and neutral mobs the
	 * way {@link Aggro} has them fight (llamas and snow golems shoot; parrots, bats, allays, fish spit); else melee.
	 */
	public static String kind(net.minecraft.server.level.ServerLevel level, Entry e) {
		if (e == null || e.id().startsWith("npc:")) return "melee";
		return KINDS.computeIfAbsent(e.id() + "|" + e.nbt(), k -> {
			java.util.regex.Matcher m = MAINHAND.matcher(e.nbt());
			String hand = m.find() ? m.group(1) : weapon(e.id());
			try {
				var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(e.id()));
				net.minecraft.world.entity.Entity made = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				return made == null ? "melee" : kind(made, hand);
			} catch (RuntimeException ex) {
				return "melee";
			}
		});
	}

	/** The same for a mob that's already there (its own main hand when {@code hand} is null). */
	public static String kind(net.minecraft.world.entity.Entity mob, String hand) {
		String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
		if (NATIVE_RANGED.contains(path)) return "ranged";
		if (mob instanceof net.minecraft.world.entity.Mob m) {
			Boolean shoots = Aggro.shoots(m);
			if (shoots != null) return shoots ? "ranged" : "melee";
		}
		if (!(mob instanceof net.minecraft.world.entity.monster.RangedAttackMob) && !(mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob)) return "melee";
		if (hand == null && mob instanceof net.minecraft.world.entity.LivingEntity l) hand = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(l.getMainHandItem().getItem()).toString();
		return hand != null && (hand.endsWith("bow") || hand.endsWith("crossbow") || hand.endsWith("trident")) ? "ranged" : "melee";
	}

	static String weapon(String id) {
		return switch (id) {
			case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:parched", "minecraft:illusioner" -> "minecraft:bow";
			case "minecraft:wither_skeleton" -> "minecraft:stone_sword";
			case "minecraft:pillager" -> "minecraft:crossbow";
			case "minecraft:vindicator" -> "minecraft:iron_axe";
			case "minecraft:zombified_piglin" -> "minecraft:golden_sword";
			case "minecraft:piglin" -> RANDOM.nextBoolean() ? "minecraft:golden_sword" : "minecraft:crossbow";
			case "minecraft:piglin_brute" -> "minecraft:golden_axe";
			default -> null;
		};
	}

	/** Summons one mob of the entry (count is up to the caller), kept from despawning, with extra data (e.g. tags). */
	public static void summon(MinecraftServer server, String dim, double x, double y, double z, Entry e, String extra) {
		// An NPC made in Community → NPCs, fighting with what it holds; it keeps the caller's tags.
		if (e.id().startsWith("npc:")) {
			List<String> tags = new ArrayList<>();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("Tags:\\[([^\\]]*)\\]").matcher(extra == null ? "" : extra);
			if (m.find()) for (String t : m.group(1).split(",")) if (!t.isBlank()) tags.add(t.strip().replace("\"", ""));
			eu.explorerseden.nicecontrolcenter.npcs.NpcRuntime.spawnHostile(server, dim, x, y, z, e.id().substring(4), tags);
			return;
		}
		// The exclude tag goes into the caller's Tags (one Tags list only), else a list of its own.
		String tags = extra != null && extra.contains("Tags:[") ? extra.replaceFirst("Tags:\\[", "Tags:[\"" + NMM_EXCLUDE + "\",") : (extra == null || extra.isEmpty() ? "" : extra + ",") + "Tags:[\"" + NMM_EXCLUDE + "\"]";
		String own = "PersistenceRequired:1b," + tags + variants(server, e);
		// /summon with NBT skips the mob's usual gear: skeletons get their bow etc. unless the line sets a main hand.
		String nbt = e.nbt();
		String weapon = weapon(e.id());
		if (weapon != null && !nbt.contains("mainhand") && !nbt.contains("HandItems")) {
			String hand = "mainhand:{id:\"" + weapon + "\",count:1}";
			int at = nbt.indexOf("equipment:{");
			if (at >= 0) nbt = nbt.substring(0, at + 11) + hand + (nbt.charAt(at + 11) == '}' ? "" : ",") + nbt.substring(at + 11);
			else own += ",equipment:{" + hand + "}";
		}
		String data = nbt.isEmpty() ? "{" + own + "}" : "{" + own + "," + nbt.substring(1);
		ConsoleRunner.quiet(server, String.format(Locale.ROOT, "execute in %s run summon %s %.2f %.2f %.2f %s", dim, e.id(), x, y, z, data));
	}
}
