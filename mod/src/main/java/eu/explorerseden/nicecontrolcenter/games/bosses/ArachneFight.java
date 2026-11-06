package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Arachne, Mother of Spiders (Katters Structures, by Katter): a big spider whose attacks get nastier as her health
 * drops (cobwebs and wind bursts, then poison, then harming), brood when hurt, mining fatigue around her, and arrows
 * near her fall to the ground.
 */
final class ArachneFight extends BossFight {
	private static final String NBT = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{bold:true,color:\"dark_green\",text:\"Arachne, Mother of Spiders\"},"
			+ "active_effects:[{id:\"minecraft:weaving\",amplifier:1,duration:-1,show_particles:0b}],attributes:[{id:\"minecraft:attack_knockback\",base:2},{id:\"minecraft:attack_damage\",base:6},"
			+ "{id:\"minecraft:armor\",base:4},{id:\"minecraft:follow_range\",base:48},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:max_health\",base:160},"
			+ "{id:\"minecraft:safe_fall_distance\",base:128},{id:\"minecraft:scale\",base:2},{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String BROOD = "{DeathLootTable:\"minecraft:empty\",Health:2f,CustomName:\"Spider Brood\",active_effects:[{id:\"minecraft:weaving\",amplifier:1,duration:2,show_particles:0b},"
			+ "{id:\"minecraft:wind_charged\",amplifier:1,duration:-1,show_particles:0b}],attributes:[{id:\"minecraft:armor\",base:0},{id:\"minecraft:follow_range\",base:48},"
			+ "{id:\"minecraft:gravity\",base:0.02},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:max_health\",base:2},{id:\"minecraft:safe_fall_distance\",base:128},"
			+ "{id:\"minecraft:scale\",base:0.25},{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity spider;

	ArachneFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		spider = (LivingEntity) spawn("minecraft:spider", x, y, z, NBT, true, false);
	}

	@Override
	String name() {
		return "Arachne, Mother of Spiders";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return spider;
	}

	@Override
	boolean alive() {
		return !dead(spider);
	}

	@Override
	void tick() {
		defend(spider, 10);
		if (ticks % 40 != 0) return;
		Vec3 at = spider.position();
		float health = spider.getHealth();
		int tier = health <= 30 ? 3 : health <= 60 ? 2 : health <= 90 ? 1 : 0;
		if (tier > 0) attack(tier);
		for (ServerPlayer p : near(at, 35)) effect(p, MobEffects.MINING_FATIGUE, 200, 4);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, around(spider, 35),
				e -> List.of("spider", "cave_spider", "silverfish").contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()))) {
			effect(e, MobEffects.WEAVING, 200, 4);
		}
	}

	/** One attack for everyone within 15 blocks: easy 1–2, mid 1–4, hard 1–6. */
	private void attack(int tier) {
		int pick = 1 + random.nextInt(tier * 2);
		for (ServerPlayer p : near(spider.position(), 15)) {
			Vec3 at = p.position();
			switch (pick) {
				case 1 -> burst(at, "minecraft:weaving", tier == 3 ? 3 : tier == 2 ? 2 : 1);
				case 2 -> windBurst(at);
				case 3, 4 -> dropFromAbove(at, splash(tier == 3 ? "minecraft:strong_poison" : "minecraft:poison"), 60);
				default -> dropFromAbove(at, splash("minecraft:harming"), 60);
			}
		}
	}

	/** Hurt by a player: half the time a spider, cave spider or silverfish of her brood comes to help. */
	@Override
	void hurt(LivingEntity piece, DamageSource source) {
		if (piece != spider || !(source.getEntity() instanceof ServerPlayer) || random.nextInt(2) != 0) return;
		if (near(spider.position(), 16).isEmpty()) return;
		String type = switch (random.nextInt(3)) {
			case 0 -> "minecraft:spider";
			case 1 -> "minecraft:cave_spider";
			default -> "minecraft:silverfish";
		};
		Entity e = spawn(type, spider.getX(), spider.getY(), spider.getZ(), BROOD, false, false);
		if (e != null) e.setCustomNameVisible(false);
	}
}
