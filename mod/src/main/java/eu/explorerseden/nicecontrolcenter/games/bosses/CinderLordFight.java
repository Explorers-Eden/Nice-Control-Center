package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Cinder Lord (NCC): a big blaze in a ring of three fire shields. While any shield is up it takes only a quarter
 * of the damage; a shield breaks after three snowballs or five hits (players get snowballs). Flame pillars rise where
 * the ground warned; with its shields down it fires spirals of fireballs.
 */
final class CinderLordFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:140f,CustomName:{text:\"The Cinder Lord\",color:\"#FF8C1A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:140},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	/** Shield strength: a snowball takes 5, a hit 3. */
	private static final int SHIELD = 15;

	private final LivingEntity lord;
	private final Target[] shields = new Target[3];
	private double spin;

	CinderLordFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		lord = (LivingEntity) spawn("minecraft:blaze", x, y, z, BODY, true, false);
		for (int i = 0; i < 3 && lord != null; i++) {
			shields[i] = target("minecraft:magma_block", lord.position().add(2.5, 1.5, 0), 1000, "Fire Shield", false);
			shields[i].hits = SHIELD;
		}
		later(2, () -> {
			for (ServerPlayer p : players(new Vec3(x, y, z))) give(p, new ItemStack(Items.SNOWBALL, 16));
		});
	}

	@Override
	String name() {
		return "The Cinder Lord";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return lord;
	}

	@Override
	boolean alive() {
		return !dead(lord);
	}

	private boolean shielded() {
		for (Target t : shields) if (t != null && !t.broken()) return true;
		return false;
	}

	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		for (Target t : shields) {
			if (t == null || piece != t.hitbox) continue;
			// Shields count blows, not damage: snowballs break them fastest.
			boolean snow = source.getDirectEntity() instanceof Snowball;
			if (!snow && !(source.getEntity() instanceof ServerPlayer)) return false;
			t.hits -= snow ? 5 : 3;
			Vec3 at = t.hitbox.position();
			level.sendParticles(snow ? ParticleTypes.SNOWFLAKE : ParticleTypes.LAVA, at.x, at.y + 0.5, at.z, 12, 0.3, 0.3, 0.3, 0.05);
			sound(at, SoundEvents.FIRE_EXTINGUISH, 1, 1.4f);
			if (t.hits <= 0) t.hitbox.discard();
			return false;
		}
		if (piece == lord && shielded() && amount > 0) {
			hurtAs(lord, amount * 0.25f, source.getEntity());
			return false;
		}
		return true;
	}

	@Override
	void tick() {
		Vec3 at = lord.position();
		// The shields circle it.
		spin += 0.06;
		for (int i = 0; i < 3; i++) {
			Target t = shields[i];
			if (t == null || t.broken()) continue;
			double a = spin + Math.PI * 2 * i / 3;
			t.moveTo(at.add(Math.cos(a) * 2.8, 1.2, Math.sin(a) * 2.8));
			if (ticks % 4 == 0) level.sendParticles(ParticleTypes.FLAME, t.hitbox.getX(), t.hitbox.getY() + 0.5, t.hitbox.getZ(), 2, 0.2, 0.2, 0.2, 0.01);
		}
		// Snowballs topped up every 20 s.
		if (ticks % 400 == 200) {
			for (ServerPlayer p : players(at)) if (p.getInventory().countItem(Items.SNOWBALL) < 8) give(p, new ItemStack(Items.SNOWBALL, 8));
		}
		// Flame pillars every 8 s under up to two players.
		if (ticks % 160 == 80) {
			List<ServerPlayer> ps = near(at, 28);
			for (int i = 0; i < Math.min(2, ps.size()); i++) {
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(ps.get(i).position()));
				for (int t = 0; t < 25; t += 5) later(t, () -> telegraph(spot, 1.5, ParticleTypes.SMALL_FLAME));
				later(25, () -> {
					for (int h = 0; h < 6; h++) level.sendParticles(ParticleTypes.FLAME, spot.x, spot.y + h * 0.6, spot.z, 8, 0.4, 0.2, 0.4, 0.02);
					sound(spot, SoundEvents.BLAZE_SHOOT, 1.5f, 0.6f);
					for (ServerPlayer p : near(spot, 1.7)) {
						p.hurtServer(level, level.damageSources().mobAttack(lord), 6);
						p.igniteForSeconds(4);
					}
				});
			}
		}
		// Shields down: a spiral of eight fireballs every 3 s.
		if (!shielded() && ticks % 60 == 0) {
			sound(at, SoundEvents.BLAZE_SHOOT, 2, 0.5f);
			double base = ticks / 60 * 0.4;
			for (int i = 0; i < 8; i++) {
				double a = base + Math.PI * 2 * i / 8;
				Vec3 d = new Vec3(Math.cos(a), -0.05, Math.sin(a)).scale(0.7);
				fireball(spawn("minecraft:small_fireball", at.x + d.x * 2, at.y + 1.5, at.z + d.z * 2, "{acceleration_power:0.06d,Motion:[" + d.x + "d," + d.y + "d," + d.z + "d]}", false, true));
			}
		}
	}
}
