package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Arch-Evoker (NCC): fangs in patterns (a line at a player, a ring around itself, a spiral), a storm of vexes,
 * and at half health a floating totem that shields it completely until it's broken; fangs come faster meanwhile.
 */
final class ArchEvokerFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{text:\"The Arch-Evoker\",color:\"#3E8E5C\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:0.6},{id:\"minecraft:follow_range\",base:40},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String VEX = "{DeathLootTable:\"minecraft:empty\",life_ticks:300,CustomName:\"Storm Vex\"}";

	private final LivingEntity evoker;
	private final List<Entity> vexes = new ArrayList<>();
	private Target totem;
	private int pattern;

	ArchEvokerFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		evoker = (LivingEntity) spawn("minecraft:evoker", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Arch-Evoker";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return evoker;
	}

	@Override
	boolean alive() {
		return !dead(evoker);
	}

	private boolean shielded() {
		return totem != null && !totem.broken();
	}

	/** The totem shields it completely. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == evoker && shielded()) {
			Vec3 at = evoker.position();
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 2, at.z, 15, 0.6, 1, 0.6, 0.2);
			return false;
		}
		return true;
	}

	private void fang(double x, double y, double z, int warmup) {
		spawn("minecraft:evoker_fangs", x, y, z, "{Warmup:" + warmup + "}", false, true);
	}

	@Override
	void tick() {
		vexes.removeIf(BossFight::dead);
		Vec3 at = evoker.position();
		int every = shielded() ? 60 : 110;
		if (ticks % every == 30) {
			ServerPlayer p = nearest(at, 30);
			if (p != null) {
				sound(at, SoundEvents.EVOKER_PREPARE_ATTACK, 2, 0.8f);
				switch (pattern++ % 3) {
					case 0 -> {
						// A line at the player.
						Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
						if (d.lengthSqr() < 0.01) d = new Vec3(1, 0, 0);
						d = d.normalize();
						for (int i = 1; i <= 16; i++) fang(at.x + d.x * i * 1.25, groundAt(at.add(d.scale(i * 1.25))).getY(), at.z + d.z * i * 1.25, i * 2);
					}
					case 1 -> {
						// Two rings around itself.
						for (int ring = 0; ring < 2; ring++) {
							int n = ring == 0 ? 8 : 14;
							double r = ring == 0 ? 2.5 : 4.5;
							for (int i = 0; i < n; i++) {
								double a = Math.PI * 2 * i / n;
								fang(at.x + Math.cos(a) * r, groundAt(at.add(Math.cos(a) * r, 0, Math.sin(a) * r)).getY(), at.z + Math.sin(a) * r, 6 + ring * 8);
							}
						}
					}
					default -> {
						// A spiral out from itself.
						for (int i = 0; i < 24; i++) {
							double a = i * 0.55, r = 1.5 + i * 0.35;
							fang(at.x + Math.cos(a) * r, groundAt(at.add(Math.cos(a) * r, 0, Math.sin(a) * r)).getY(), at.z + Math.sin(a) * r, i);
						}
					}
				}
			}
		}
		// A vex storm every 20 s (up to six).
		if (ticks % 400 == 200 && vexes.size() < 6) {
			sound(at, SoundEvents.EVOKER_PREPARE_SUMMON, 2, 0.8f);
			for (int i = 0; i < 4; i++) {
				Entity v = spawn("minecraft:vex", at.x + random.nextGaussian(), at.y + 2, at.z + random.nextGaussian(), VEX, false, false);
				if (v != null) vexes.add(v);
			}
		}
		if (totem == null && share(evoker) <= 0.5f) {
			Vec3 spot = at.add(random.nextBoolean() ? 6 : -6, 2, random.nextBoolean() ? 6 : -6);
			if (bounds != null) spot = new Vec3(Math.max(bounds.minX + 1, Math.min(bounds.maxX - 1, spot.x)), Math.min(bounds.maxY - 2, spot.y), Math.max(bounds.minZ + 1, Math.min(bounds.maxZ - 1, spot.z)));
			totem = target("item:minecraft:totem_of_undying", spot, 40 * healthScale, "Warding Totem", true);
			sound(at, SoundEvents.TOTEM_USE, 2, 0.8f);
			say(at, 40, styled("The Arch-Evoker ", 0x3E8E5C, true, false).copy().append(plain("raises a warding totem. Break it!")));
		}
	}
}
