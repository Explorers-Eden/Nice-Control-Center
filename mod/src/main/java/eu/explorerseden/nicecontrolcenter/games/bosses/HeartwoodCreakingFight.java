package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * The Heartwood Creaking (NCC): a giant creaking that only moves while nobody looks at it, and can't be hurt while
 * its three resin hearts stand around the arena. Break the hearts, then the creaking. Roots snare players now and
 * then; at half health one heart grows back, once.
 */
final class HeartwoodCreakingFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:120f,CustomName:{text:\"The Heartwood Creaking\",color:\"#E07B39\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:120},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:follow_range\",base:40},"
			+ "{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity creaking;
	private final Vec3 home;
	private boolean regrown;

	HeartwoodCreakingFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		home = new Vec3(x, y, z);
		creaking = (LivingEntity) spawn("minecraft:creaking", x, y, z, BODY, true, false);
		for (int i = 0; i < 3; i++) heart(i * Math.PI * 2 / 3);
	}

	private void heart(double angle) {
		double r = 6 + random.nextInt(5);
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(home.add(Math.cos(angle) * r, 2, Math.sin(angle) * r)));
		if (bounds != null) spot = new Vec3(Math.max(bounds.minX + 1, Math.min(bounds.maxX - 1, spot.x)), spot.y, Math.max(bounds.minZ + 1, Math.min(bounds.maxZ - 1, spot.z)));
		target("minecraft:creaking_heart", spot, 30 * healthScale, "Resin Heart", true);
	}

	@Override
	String name() {
		return "The Heartwood Creaking";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return creaking;
	}

	@Override
	boolean alive() {
		return !dead(creaking);
	}

	private boolean guarded() {
		for (Target t : targets) if (!t.broken()) return true;
		return false;
	}

	/** Untouchable while a heart stands. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == creaking && guarded()) {
			Vec3 at = creaking.position();
			level.sendParticles(ParticleTypes.CRIMSON_SPORE, at.x, at.y + 2, at.z, 12, 0.8, 1, 0.8, 0);
			if (source.getEntity() instanceof ServerPlayer p) p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Its hearts protect it: break the Resin Hearts first")
					.withStyle(net.minecraft.ChatFormatting.GOLD));
			return false;
		}
		return true;
	}

	@Override
	void tick() {
		Vec3 at = creaking.position();
		// Hearts pulse (and a line of particles from each to the creaking while they stand).
		if (ticks % 20 == 0) {
			for (Target t : targets) {
				if (t.broken()) continue;
				Vec3 h = t.hitbox.position().add(0, 0.5, 0);
				Vec3 d = at.add(0, 2, 0).subtract(h);
				for (double f = 0; f <= 1; f += 0.1) {
					Vec3 q = h.add(d.scale(f));
					level.sendParticles(ParticleTypes.CRIMSON_SPORE, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
			}
		}
		// Root snares every 10 s: resin around a player, slowed.
		if (ticks % 200 == 100) {
			ServerPlayer p = nearest(at, 30);
			if (p != null) {
				BlockPos c = p.blockPosition();
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos b = c.offset(dx, 0, dz);
						if (level.getBlockState(b.below()).isFaceSturdy(level, b.below(), net.minecraft.core.Direction.UP))
							tempBlock(b, Blocks.RESIN_CLUMP.defaultBlockState().setValue(BlockStateProperties.DOWN, true), 100);
					}
				}
				effect(p, MobEffects.SLOWNESS, 80, 2);
				sound(p.position(), SoundEvents.CREAKING_ACTIVATE, 1.5f, 0.7f);
			}
		}
		if (!regrown && !guarded() && share(creaking) <= 0.5f) {
			regrown = true;
			heart(random.nextDouble() * Math.PI * 2);
			sound(at, SoundEvents.CREAKING_HEART_SPAWN, 3, 0.7f);
			say(at, 40, styled("The Heartwood Creaking ", 0xE07B39, true, false).copy().append(plain("grows a new heart!")));
		}
	}
}
