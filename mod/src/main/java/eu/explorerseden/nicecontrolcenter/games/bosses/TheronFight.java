package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Magus Theron the Betrayer (Katters Structures, by Katter): an illusioner by his lightning rod. He wakes when someone
 * comes close; at 150, 100 and 50 health he flies up out of reach and sends 4–6 minions, coming back once they're
 * beaten. Killed, he returns as "Theron, the Ravaged Betrayer" (a ravager); the fight is won when that one falls. He
 * talks the whole time.
 */
final class TheronFight extends BossFight {
	private static final String ROD = "{CustomNameVisible:0b,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-1f,-0.5625f,-1f],scale:[2f,1.125f,2f]},"
			+ "block_state:{Name:\"minecraft:lightning_rod\",Properties:{facing:\"up\",powered:\"false\",waterlogged:\"false\"}}}";
	private static final String ORB = "{teleport_duration:5,shadow_strength:0f,transformation:{left_rotation:[-0.3535534f,0.14644665f,0.3535534f,0.85355335f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[5.9604645e-8f,-0.85355335f,0.14644663f],scale:[1f,1f,1f]},block_state:{Name:\"minecraft:redstone_block\"}}";
	private static final String MAGUS = "{Invulnerable:1b,CustomNameVisible:1b,DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,NoAI:1b,CanPickUpLoot:0b,Health:200f,PatrolLeader:0b,CanJoinRaid:0b,"
			+ "CustomName:{bold:true,color:\"light_purple\",italic:true,text:\"Magus Theron the Betrayer\"},equipment:{mainhand:{id:\"minecraft:bow\",count:1,components:{\"minecraft:enchantments\":"
			+ "{\"minecraft:power\":1,\"minecraft:punch\":2}}}},drop_chances:{mainhand:0.0},attributes:[{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:max_health\",base:200},"
			+ "{id:\"minecraft:movement_speed\",base:0.2},{id:\"minecraft:scale\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String RAVAGER = "{CustomNameVisible:1b,DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CanPickUpLoot:0b,Health:25f,PatrolLeader:0b,CanJoinRaid:0b,"
			+ "CustomName:{bold:true,color:\"light_purple\",italic:true,text:\"Theron, the Ravaged Betrayer\"},attributes:[{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:max_health\",base:100},"
			+ "{id:\"minecraft:scale\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String MINION = "{CustomNameVisible:0b,DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CanPickUpLoot:0b,PatrolLeader:0b,CanJoinRaid:0b,"
			+ "CustomName:{bold:true,color:\"dark_purple\",italic:true,text:\"Theron's Minions\"}";
	private static final int PURPLE = 0xFF55FF;
	private static Map<String, List<String>> lines;

	private final Entity rod;
	private final Entity orb;
	private final Vec3 home;
	private LivingEntity magus;
	private LivingEntity ravager;
	/** 0 waiting, 1/3/5/7 fighting, 2/4/6 hiding behind minions, 8 the ravager, 9 won. */
	private int phase;
	private final List<Entity> minions = new ArrayList<>();

	TheronFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		home = new Vec3(x, y, z);
		rod = spawn("minecraft:block_display", x, y + 0.5625, z, ROD, false, true);
		orb = spawn("minecraft:block_display", x, y + 2, z, ORB, false, true);
		magus = (LivingEntity) spawn("minecraft:illusioner", x, y, z - 1, MAGUS, true, false);
	}

	@Override
	String name() {
		return "Magus Theron the Betrayer";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.PURPLE;
	}

	@Override
	LivingEntity body() {
		return phase >= 8 ? ravager : magus;
	}

	@Override
	boolean alive() {
		if (phase >= 9) return false;
		if (phase == 8) return !dead(ravager);
		// Killed: the ravager comes next (taken away by a cleanup: the fight is over).
		return !dead(magus) || killed(magus);
	}

	@Override
	void tick() {
		if (orb != null && !orb.isRemoved() && ticks % 5 == 0) orb.setYRot(orb.getYRot() + 10);
		if (phase == 8) {
			if (dead(ravager)) phase = 9;
			return;
		}
		// Killed (in any phase): his last form comes at once.
		if (dead(magus)) {
			final_();
			return;
		}
		if (ticks % 20 != 0) return;
		for (ServerPlayer p : near(home, 75)) effect(p, MobEffects.MINING_FATIGUE, 240, 0);
		minions.removeIf(BossFight::dead);
		switch (phase) {
			case 0 -> {
				if (!near(magus.position(), 20).isEmpty()) {
					wake();
					phase = 1;
				}
			}
			case 1, 3, 5 -> {
				// (150, 100 and 50 of his 200, as shares: a wave may give him more.)
				float at = magus.getMaxHealth() * (phase == 1 ? 0.75f : phase == 3 ? 0.5f : 0.25f);
				if (magus.getHealth() <= at) {
					hide();
					phase++;
				}
			}
			case 2, 4, 6 -> {
				if (minions.stream().noneMatch(m -> m.distanceToSqr(home) <= 25 * 25)) {
					wake();
					if (random.nextInt(2) == 0) talk("realize");
					phase++;
				}
			}
			case 7 -> {
				if (magus.distanceToSqr(home) > 120 * 120) final_();
			}
			default -> {
			}
		}
		if (phase == 8) return;
		// Pulled back to his rod when he strays.
		if (phase % 2 == 1 && !dead(magus)) {
			double d = magus.distanceToSqr(home);
			if (d >= 20 * 20 && d <= 100 * 100) magus.teleportTo(home.x, home.y, home.z - 1);
		}
		// He talks.
		int r = random.nextInt(100);
		switch (phase) {
			case 1 -> {
				if (r < 10) talk("arrogant");
			}
			case 3 -> {
				if (r < 5) talk("arrogant");
				else if (r < 10) talk("boast");
			}
			case 5 -> {
				if (r < 5) talk("boast");
				else if (r < 10) talk("angry");
			}
			case 7 -> {
				if (r < 10) talk("angry");
			}
			default -> {
			}
		}
	}

	private void wake() {
		eu.explorerseden.nicecontrolcenter.compat.Invulnerable.set(magus, false);
		if (magus instanceof Mob m) m.setNoAi(false);
		magus.removeEffect(MobEffects.INVISIBILITY);
		magus.removeEffect(MobEffects.REGENERATION);
		magus.teleportTo(home.x, home.y, home.z - 1);
	}

	/** Up out of reach, untouchable, and his minions come. */
	private void hide() {
		magus.teleportTo(home.x, home.y + 15, home.z);
		eu.explorerseden.nicecontrolcenter.compat.Invulnerable.set(magus, true);
		if (magus instanceof Mob m) m.setNoAi(true);
		effect(magus, MobEffects.INVISIBILITY, -1, 1);
		effect(magus, MobEffects.REGENERATION, -1, 0);
		talk("retreat");
		int n = 4 + random.nextInt(3);
		for (int i = 0; i < n; i++) {
			double x = Math.floor(home.x) + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(5)) + 0.5;
			double z = Math.floor(home.z) + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(5)) + 0.5;
			int k = random.nextInt(5);
			String id = k < 2 ? "minecraft:pillager" : k < 4 ? "minecraft:vindicator" : "minecraft:evoker";
			String hand = k < 2 ? ",equipment:{mainhand:{id:\"minecraft:crossbow\",count:1}},drop_chances:{mainhand:0.0}"
					: k < 4 ? ",equipment:{mainhand:{id:\"minecraft:stone_axe\",count:1}},drop_chances:{mainhand:0.0}" : "";
			Entity m = spawn(id, x, home.y, z, MINION + hand + "}", false, false);
			if (m != null) minions.add(m);
		}
	}

	/** His last form. */
	private void final_() {
		phase = 8;
		Vec3 at = dead(magus) ? home : magus.position();
		if (!dead(magus)) magus.discard();
		ravager = (LivingEntity) spawn("minecraft:ravager", at.x, at.y, at.z, RAVAGER, true, false);
		if (orb != null) orb.discard();
		sound(at, SoundEvents.AMETHYST_CLUSTER_BREAK, 20, 1);
		sound(at, SoundEvents.RAVAGER_ROAR, 30, 1);
		say(at, 30, line("Magus Theron the Betrayer", "NOOOOOOOOOOOOOOOO"));
		say(at, 30, line("Magus Theron the Betrayer", "You will never get me alive!"));
		say(at, 30, line("Magus Theron the Betrayer", "Tremble before my final form!!!!"));
		say(at, 30, line("Theron, the Ravaged Betrayer", "ROOOOOOOOOAAAARRRRRR"));
		if (ravager == null) phase = 9;
	}

	private void talk(String kind) {
		List<String> pool = lines().getOrDefault(kind, List.of());
		if (pool.isEmpty()) return;
		Vec3 at = dead(magus) ? home : magus.position();
		say(at, 30, line("Magus Theron the Betrayer", pool.get(random.nextInt(pool.size()))));
	}

	private static Component line(String who, String text) {
		return Component.empty().append(styled(who + ": ", PURPLE, false, true)).append(plain(text));
	}

	/** His lines (written by Katter), read once from the mod. */
	private static synchronized Map<String, List<String>> lines() {
		if (lines != null) return lines;
		Map<String, List<String>> out = new HashMap<>();
		try (InputStream in = TheronFight.class.getResourceAsStream("/nicecontrolcenter/katters_theron.json")) {
			if (in != null) {
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String k : o.keySet()) {
					if (!o.get(k).isJsonArray()) continue;
					List<String> l = new ArrayList<>();
					for (JsonElement e : o.getAsJsonArray(k)) l.add(e.getAsString());
					out.put(k, l);
				}
			}
		} catch (Exception e) {
			eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.warn("Games: Theron's lines didn't load: {}", e.toString());
		}
		lines = out;
		return lines;
	}
}
