package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * The Katters Structures bosses (by Katter, Explorer's Eden) for wave arenas and dungeons, written in like a mob:
 * {@code katters:arachne}, {@code katters:raj}, {@code katters:rusta}, {@code katters:tenku}, {@code katters:theron}.
 * Built into the mod (no datapack functions needed); they drop nothing.
 */
public final class KatterBosses {
	public static final List<String> KEYS = List.of("arachne", "raj", "rusta", "tenku", "theron");
	/** NCC's own bosses ("ncc:frost_warden"…). */
	public static final List<String> NCC = List.of("frost_warden", "hive_queen", "ember_colossus", "tempest", "drowned_admiral", "bone_architect", "creeper_king", "echo_shepherd", "coven_matron",
			"phantom_monarch", "piglin_warlord", "ender_stalker", "iron_tyrant",
			"purpur_sentinel", "cinder_lord", "weeping_ghast", "dire_alpha",
			"heartwood_creaking", "abyssal_eye", "rotting_giant", "arch_evoker",
			"oxidized_engine", "ramlord", "llama_matriarch", "panda_grandmaster");
	/** Every boss as written in lists and markers. */
	public static List<String> all() {
		List<String> out = new ArrayList<>();
		for (String k : KEYS) out.add("katters:" + k);
		for (String k : NCC) out.add("ncc:" + k);
		return out;
	}

	/** A name as typed in /game boss: the full "ncc:tempest" or just "tempest" (when only one set has it). */
	public static String resolve(String typed) {
		String s = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
		if (key(s) != null) return s;
		if (KEYS.contains(s)) return "katters:" + s;
		if (NCC.contains(s)) return "ncc:" + s;
		return null;
	}

	private static final List<BossFight> fights = new ArrayList<>();
	private static final Map<UUID, BossFight> owner = new HashMap<>();

	private KatterBosses() {
	}

	/** A Katters boss line ("katters:theron", any case). */
	public static boolean is(String id) {
		return key(id) != null;
	}

	/** "katters:theron" → "theron", "ncc:tempest" → "ncc_tempest"; null when it's no boss of ours. */
	private static String key(String id) {
		if (id == null) return null;
		String s = id.strip().toLowerCase(Locale.ROOT);
		if (s.startsWith("katters:")) return KEYS.contains(s.substring(8)) ? s.substring(8) : null;
		if (s.startsWith("ncc:")) return NCC.contains(s.substring(4)) ? "ncc_" + s.substring(4) : null;
		return null;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (BossFight f : List.copyOf(fights)) {
				if (f.over) {
					drop(f);
					continue;
				}
				try {
					if (!f.alive()) {
						f.finish();
						drop(f);
						continue;
					}
					f.step();
				} catch (RuntimeException e) {
					NiceControlCenter.LOGGER.warn("Games: boss {} stopped after an error: {}", f.name(), e.toString());
					f.finish();
					drop(f);
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (BossFight f : List.copyOf(fights)) f.finish();
			fights.clear();
			owner.clear();
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			BossFight f = owner.get(entity.getUUID());
			if (f == null || f.over || f.applying) return true;
			// (/kill and the void always go through.)
			if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
			return f.allowDamage(entity, source, amount);
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			BossFight f = owner.get(entity.getUUID());
			if (f != null && !f.over) f.damaged(entity, source, taken);
			if (entity instanceof ServerPlayer p && source.getEntity() != null) {
				BossFight by = owner.get(source.getEntity().getUUID());
				if (by != null && !by.over) by.hit(p, source.getEntity());
			}
		});
		// Nothing drops: what fell this tick next to a dying piece goes.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!owner.containsKey(entity.getUUID()) || !(entity.level() instanceof ServerLevel level)) return;
			for (Entity e : level.getEntitiesOfClass(Entity.class, entity.getBoundingBox().inflate(3), x -> (x instanceof ItemEntity || x instanceof ExperienceOrb) && x.tickCount <= 1)) e.discard();
		});
	}

	static void own(Entity e, BossFight f) {
		owner.put(e.getUUID(), f);
	}

	private static void drop(BossFight f) {
		fights.remove(f);
		owner.values().removeIf(x -> x == f);
	}

	/**
	 * The boss at the spot. {@code tags} go on everything it makes (the game's tag), {@code bossTag} on its main bodies
	 * (null: none); {@code bar}: its own boss bar for players close by. Null when it's no Katters boss or the dimension
	 * is gone.
	 */
	public static BossFight spawn(MinecraftServer server, String id, String dim, double x, double y, double z, List<String> tags, String bossTag, boolean bar) {
		return spawn(server, id, dim, x, y, z, tags, bossTag, bar, null);
	}

	/** The same, its bar only for {@code viewers} (the game's players; null: anyone within 48 blocks). */
	public static BossFight spawn(MinecraftServer server, String id, String dim, double x, double y, double z, List<String> tags, String bossTag, boolean bar,
			java.util.function.Predicate<ServerPlayer> viewers) {
		return spawn(server, id, dim, x, y, z, tags, bossTag, bar, viewers, null, 1);
	}

	/**
	 * The same, kept inside {@code bounds} (the arena or dungeon box; null: anywhere) and with {@code healthScale}
	 * times the health (a wave arena's "% more health per wave").
	 */
	public static BossFight spawn(MinecraftServer server, String id, String dim, double x, double y, double z, List<String> tags, String bossTag, boolean bar,
			java.util.function.Predicate<ServerPlayer> viewers, net.minecraft.world.phys.AABB bounds, float healthScale) {
		String k = key(id);
		ServerLevel level = eu.explorerseden.nicecontrolcenter.games.GameRuntime.levelOf(server, dim);
		if (k == null || level == null) return null;
		BossFight.pendingBounds = bounds;
		BossFight.pendingScale = healthScale <= 0 ? 1 : healthScale;
		BossFight f;
		try {
			f = make(k, level, tags, bossTag, bar, x, y, z);
		} finally {
			BossFight.pendingBounds = null;
			BossFight.pendingScale = 1;
		}
		f.viewers = viewers;
		fights.add(f);
		NiceControlCenter.LOGGER.info("Games: boss {} summoned in {} at {} {} {}{}", f.name(), dim, Math.round(x), Math.round(y), Math.round(z),
				healthScale > 1.001f ? " (health x" + String.format(Locale.ROOT, "%.2f", healthScale) + ")" : "");
		return f;
	}

	private static BossFight make(String k, ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		return switch (k) {
			case "arachne" -> new ArachneFight(level, tags, bossTag, bar, x, y, z);
			case "raj" -> new RajFight(level, tags, bossTag, bar, x, y, z);
			case "rusta" -> new RustaFight(level, tags, bossTag, bar, x, y, z);
			case "tenku" -> new TenkuFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_frost_warden" -> new FrostWardenFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_hive_queen" -> new HiveQueenFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_ember_colossus" -> new EmberColossusFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_tempest" -> new TempestFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_drowned_admiral" -> new DrownedAdmiralFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_bone_architect" -> new BoneArchitectFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_creeper_king" -> new CreeperKingFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_echo_shepherd" -> new EchoShepherdFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_coven_matron" -> new CovenMatronFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_phantom_monarch" -> new PhantomMonarchFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_piglin_warlord" -> new PiglinWarlordFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_ender_stalker" -> new EnderStalkerFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_iron_tyrant" -> new IronTyrantFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_purpur_sentinel" -> new PurpurSentinelFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_cinder_lord" -> new CinderLordFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_weeping_ghast" -> new WeepingGhastFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_dire_alpha" -> new DireAlphaFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_heartwood_creaking" -> new HeartwoodCreakingFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_abyssal_eye" -> new AbyssalEyeFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_rotting_giant" -> new RottingGiantFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_arch_evoker" -> new ArchEvokerFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_oxidized_engine" -> new OxidizedEngineFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_ramlord" -> new RamlordFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_llama_matriarch" -> new LlamaMatriarchFight(level, tags, bossTag, bar, x, y, z);
			case "ncc_panda_grandmaster" -> new PandaGrandmasterFight(level, tags, bossTag, bar, x, y, z);
			default -> new TheronFight(level, tags, bossTag, bar, x, y, z);
		};
	}

	/** A Katters boss's name, for messages ("katters:raj" → "Raj Raksha"). */
	public static String title(String id) {
		String k = key(id);
		if (k == null) return id;
		return switch (k) {
			case "arachne" -> "Arachne, Mother of Spiders";
			case "raj" -> "Raj Raksha";
			case "rusta" -> "Rústa, Chieftain of the Ravaged Lands";
			case "tenku" -> "Tenkū Mizu Gami";
			case "ncc_frost_warden" -> "The Frost Warden";
			case "ncc_hive_queen" -> "The Hive Queen";
			case "ncc_ember_colossus" -> "The Ember Colossus";
			case "ncc_tempest" -> "The Tempest";
			case "ncc_drowned_admiral" -> "The Drowned Admiral";
			case "ncc_bone_architect" -> "The Bone Architect";
			case "ncc_creeper_king" -> "The Creeper King";
			case "ncc_echo_shepherd" -> "The Echo Shepherd";
			case "ncc_coven_matron" -> "The Coven Matron";
			case "ncc_phantom_monarch" -> "The Phantom Monarch";
			case "ncc_piglin_warlord" -> "The Piglin Warlord";
			case "ncc_ender_stalker" -> "The Ender Stalker";
			case "ncc_iron_tyrant" -> "The Iron Tyrant";
			case "ncc_purpur_sentinel" -> "The Purpur Sentinel";
			case "ncc_cinder_lord" -> "The Cinder Lord";
			case "ncc_weeping_ghast" -> "The Weeping Ghast";
			case "ncc_dire_alpha" -> "The Dire Alpha";
			case "ncc_heartwood_creaking" -> "The Heartwood Creaking";
			case "ncc_abyssal_eye" -> "The Abyssal Eye";
			case "ncc_rotting_giant" -> "The Rotting Giant";
			case "ncc_arch_evoker" -> "The Arch-Evoker";
			case "ncc_oxidized_engine" -> "The Oxidized Engine";
			case "ncc_ramlord" -> "The Ramlord";
			case "ncc_llama_matriarch" -> "The Llama Matriarch";
			case "ncc_panda_grandmaster" -> "The Panda Grandmaster";
			default -> "Magus Theron the Betrayer";
		};
	}
}
