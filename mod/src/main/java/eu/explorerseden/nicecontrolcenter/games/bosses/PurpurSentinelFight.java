package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.phys.Vec3;

/**
 * The Purpur Sentinel (NCC): a giant shulker. Volleys of homing bullets (levitation), teleports to marked spots
 * around its post, and its closed shell takes little damage: hit it while it's open. At half health three turret
 * shulkers join.
 */
final class PurpurSentinelFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Color:10b,Health:140f,CustomName:{text:\"The Purpur Sentinel\",color:\"#C38BD9\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:140},{id:\"minecraft:scale\",base:3},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String TURRET = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Color:10b,CustomName:\"Purpur Turret\"}";

	private final LivingEntity sentinel;
	private final Vec3 home;
	private boolean turrets;

	PurpurSentinelFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		home = new Vec3(x, y, z);
		sentinel = (LivingEntity) spawn("minecraft:shulker", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Purpur Sentinel";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.PURPLE;
	}

	@Override
	LivingEntity body() {
		return sentinel;
	}

	@Override
	boolean alive() {
		return !dead(sentinel);
	}

	@Override
	void tick() {
		Vec3 at = sentinel.position();
		// A volley every 6 s: a homing bullet at each of up to three players.
		if (ticks % 120 == 0) {
			List<ServerPlayer> ps = near(at, 32);
			for (int i = 0; i < Math.min(3, ps.size()); i++) {
				ServerPlayer p = ps.get(i);
				later(i * 6, () -> {
					if (dead(sentinel) || p.isRemoved()) return;
					adopt(new ShulkerBullet(level, sentinel, p, Direction.Axis.Y), false, true);
					sound(sentinel.position(), SoundEvents.SHULKER_SHOOT, 1.5f, 0.8f);
				});
			}
		}
		// A teleport every 12 s to one of four spots round its post, marked a second before.
		if (ticks % 240 == 160) {
			double a = Math.PI / 2 * random.nextInt(4) + Math.PI / 4;
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(home.add(Math.cos(a) * 6, 2, Math.sin(a) * 6)));
			for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(spot, 1.5, ParticleTypes.REVERSE_PORTAL));
			later(20, () -> {
				if (dead(sentinel)) return;
				level.sendParticles(ParticleTypes.PORTAL, sentinel.getX(), sentinel.getY() + 1.5, sentinel.getZ(), 40, 1, 1, 1, 0.3);
				sentinel.teleportTo(spot.x, spot.y, spot.z);
				sound(spot, SoundEvents.SHULKER_TELEPORT, 2, 0.8f);
			});
		}
		if (!turrets && share(sentinel) <= 0.5f) {
			turrets = true;
			say(at, 40, styled("The Purpur Sentinel ", 0xC38BD9, true, false).copy().append(plain("calls its turrets.")));
			for (int i = 0; i < 3; i++) {
				double a = Math.PI * 2 * i / 3;
				Vec3 s = Vec3.atBottomCenterOf(groundAt(home.add(Math.cos(a) * 4, 2, Math.sin(a) * 4)));
				spawn("minecraft:shulker", s.x, s.y, s.z, TURRET, false, false);
			}
		}
	}
}
