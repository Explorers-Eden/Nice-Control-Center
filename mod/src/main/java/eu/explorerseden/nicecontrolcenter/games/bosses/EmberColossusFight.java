package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Ember Colossus (NCC): a giant magma cube. At two thirds and one third of its health it bursts into smaller
 * burning cubes (the fight is won when all of them are out); rings of fire flare up around it and lava splashes land
 * where a ring of flames warned. Water hurts it.
 */
final class EmberColossusFight extends BossFight {
	private static String body(String name, int size, int health) {
		return "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Size:" + size + ",Health:" + health + "f,CustomName:{text:\"" + name + "\",color:\"#FF6A00\",bold:true},"
				+ "active_effects:[{id:\"minecraft:fire_resistance\",amplifier:0,duration:-1,show_particles:0b}],"
				+ "attributes:[{id:\"minecraft:max_health\",base:" + health + "},{id:\"minecraft:knockback_resistance\",base:0.7},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	}

	private final List<LivingEntity> cubes = new ArrayList<>();
	private LivingEntity core;
	private int splits;
	/** Where pieces died lately: the small cubes the game would split them into are taken away. */
	private final List<Object[]> deaths = new ArrayList<>();

	EmberColossusFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		core = (LivingEntity) spawn("minecraft:magma_cube", x, y, z, body("The Ember Colossus", 6, 240), true, false);
		if (core != null) cubes.add(core);
	}

	@Override
	String name() {
		return "The Ember Colossus";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		if (!dead(core)) return core;
		for (LivingEntity c : cubes) if (!dead(c)) return c;
		return null;
	}

	@Override
	boolean alive() {
		// (Still on for a moment after the last cube died: the game splits dead cubes a second later, those go too.)
		return cubes.stream().anyMatch(c -> !dead(c)) || !deaths.isEmpty() || cubes.stream().anyMatch(BossFight::killed);
	}

	@Override
	void tick() {
		for (LivingEntity c : List.copyOf(cubes)) {
			if (dead(c)) {
				deaths.add(new Object[] { c.position(), ticks + 60 });
				cubes.remove(c);
			}
		}
		// The game's own splitting would add more cubes: those go.
		deaths.removeIf(d -> (long) d[1] < ticks);
		for (Object[] d : deaths) {
			for (Entity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(BlockPos.containing((Vec3) d[0])).inflate(6),
					// (They copy the dead cube's name and tags, so only what the fight made itself stays.)
					x -> x.getType() == (core == null ? null : core.getType()) && !parts.contains(x))) {
				e.discard();
			}
		}
		LivingEntity big = body();
		if (big == null) return;
		Vec3 at = big.position();
		// Bursting at two thirds and one third (of the first body's health).
		if (!dead(core) && (splits == 0 && share(core) <= 0.66f || splits == 1 && share(core) <= 0.33f)) split();
		// Water hurts.
		if (ticks % 20 == 0) for (LivingEntity c : cubes) if (c.isInWater()) c.hurtServer(level, level.damageSources().drown(), 4);
		// A ring of fire every 7 s, a lava splash every 5 s.
		if (ticks % 140 == 0) fireRing(at, 3 + (int) (big.getBbWidth() / 2));
		if (ticks % 100 == 50) {
			ServerPlayer p = nearest(at, 30);
			if (p != null) splash(p.position());
		}
	}

	/** Into two (then three) burning cubes around where it stood; the core shrinks. */
	private void split() {
		splits++;
		Vec3 at = core.position();
		int n = splits == 1 ? 2 : 3;
		sound(at, SoundEvents.MAGMA_CUBE_SQUISH, 2, 0.5f);
		level.sendParticles(ParticleTypes.LAVA, at.x, at.y + 1, at.z, 40, 2, 1, 2, 0.1);
		say(at, 40, styled("The Ember Colossus ", 0xFF6A00, true, false).copy().append(plain("bursts apart!")));
		for (int i = 0; i < n; i++) {
			double a = Math.PI * 2 * i / n;
			LivingEntity c = (LivingEntity) spawn("minecraft:magma_cube", at.x + Math.cos(a) * 3, at.y + 1, at.z + Math.sin(a) * 3, body("Ember Shard", 3, 40), true, false);
			if (c != null) cubes.add(c);
		}
	}

	private void fireRing(Vec3 at, int radius) {
		sound(at, SoundEvents.BLAZE_SHOOT, 2, 0.6f);
		for (int i = 0; i < radius * 8; i++) {
			double a = Math.PI * 2 * i / (radius * 8);
			BlockPos b = groundAt(at.add(Math.cos(a) * radius, 1, Math.sin(a) * radius));
			if (level.getBlockState(b.below()).isFaceSturdy(level, b.below(), net.minecraft.core.Direction.UP)) tempBlock(b, Blocks.FIRE.defaultBlockState(), 100);
		}
	}

	/** Flames warn for 1.5 s, then lava lands: damage, burning, and a little fire. */
	private void splash(Vec3 target) {
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(target));
		for (int i = 0; i < 30; i += 5) later(i, () -> telegraph(spot, 2, ParticleTypes.FLAME));
		later(30, () -> {
			level.sendParticles(ParticleTypes.LAVA, spot.x, spot.y + 0.3, spot.z, 30, 1.2, 0.2, 1.2, 0.2);
			sound(spot, SoundEvents.LAVA_POP, 2, 0.6f);
			for (ServerPlayer p : near(spot, 2.2)) {
				p.hurtServer(level, level.damageSources().mobAttack(body() == null ? core : body()), 6);
				p.igniteForSeconds(4);
			}
			BlockPos b = BlockPos.containing(spot);
			if (level.getBlockState(b.below()).isFaceSturdy(level, b.below(), net.minecraft.core.Direction.UP)) tempBlock(b, Blocks.FIRE.defaultBlockState(), 60);
		});
	}
}
