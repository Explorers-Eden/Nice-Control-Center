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
 * The Bone Architect (NCC): a wither skeleton that builds. It walls a player in with bone (gone after a few seconds:
 * climb over, break through, or wait), arrows rain where a ring warned of them, and skeletons climb out of the ground
 * next to players, up to six at a time.
 */
final class BoneArchitectFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:220f,CustomName:{text:\"The Bone Architect\",color:\"#E8E1C8\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:stone_sword\",count:1}},drop_chances:{mainhand:0.0},attributes:[{id:\"minecraft:max_health\",base:220},{id:\"minecraft:scale\",base:2},"
			+ "{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String RISEN = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Risen Bones\",equipment:{mainhand:{id:\"minecraft:bow\",count:1}},drop_chances:{mainhand:0.0}}";

	private final LivingEntity architect;
	private final List<Entity> risen = new ArrayList<>();

	BoneArchitectFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		architect = (LivingEntity) spawn("minecraft:wither_skeleton", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Bone Architect";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return architect;
	}

	@Override
	boolean alive() {
		return !dead(architect);
	}

	@Override
	void tick() {
		risen.removeIf(BossFight::dead);
		Vec3 at = architect.position();
		// A bone wall around a player every 10 s.
		if (ticks % 200 == 60) {
			ServerPlayer p = nearest(at, 24);
			if (p != null) wall(p.blockPosition());
		}
		// Arrow rain every 8 s.
		if (ticks % 160 == 0) {
			ServerPlayer p = nearest(at, 30);
			if (p != null) rain(p.position());
		}
		// The dead rise: two at a time, every 9 s, up to six.
		if (ticks % 180 == 90 && risen.size() < 6) {
			List<ServerPlayer> ps = near(at, 24);
			for (int i = 0; i < 2 && !ps.isEmpty() && risen.size() < 6; i++) {
				ServerPlayer p = ps.get(random.nextInt(ps.size()));
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2)));
				level.sendParticles(ParticleTypes.ASH, spot.x, spot.y + 0.2, spot.z, 30, 0.4, 0.2, 0.4, 0);
				sound(spot, SoundEvents.SKELETON_AMBIENT, 1.5f, 0.6f);
				later(20, () -> {
					Entity s = spawn("minecraft:skeleton", spot.x, spot.y, spot.z, RISEN, false, false);
					if (s != null) risen.add(s);
				});
			}
		}
	}

	/** A ring of bone around the spot, three high, for six seconds. */
	private void wall(BlockPos c) {
		sound(Vec3.atCenterOf(c), SoundEvents.BONE_BLOCK_PLACE, 2, 0.6f);
		say(Vec3.atCenterOf(c), 40, styled("The Bone Architect: ", 0xE8E1C8, true, false).copy().append(plain("Hold still. I'm building.")));
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
				for (int dy = 0; dy < 3; dy++) tempBlock(c.offset(dx, dy, dz), Blocks.BONE_BLOCK.defaultBlockState(), 120);
			}
		}
	}

	/** A ring warns, then twelve arrows fall on it. */
	private void rain(Vec3 target) {
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(target));
		for (int t = 0; t < 30; t += 5) later(t, () -> telegraph(spot, 3, ParticleTypes.CRIT));
		later(30, () -> {
			for (int i = 0; i < 12; i++) {
				double x = spot.x + random.nextGaussian() * 1.6, z = spot.z + random.nextGaussian() * 1.6;
				spawn("minecraft:arrow", x, spot.y + 14, z, "{pickup:0b,damage:4d,Motion:[0d,-2.4d,0d]}", false, true);
			}
			sound(spot, SoundEvents.ARROW_SHOOT, 2, 0.6f);
		});
	}
}
