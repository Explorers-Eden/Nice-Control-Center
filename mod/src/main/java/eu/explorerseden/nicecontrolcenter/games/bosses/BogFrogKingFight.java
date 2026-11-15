package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Bog Frog King (NCC): a huge frog. Its tongue pulls players in, its leaps land in a splash of slime blocks, and
 * it calls slimes, swallowing one to heal when it's hurt.
 */
final class BogFrogKingFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,variant:\"minecraft:temperate\",Health:170f,CustomName:{text:\"The Bog Frog King\",color:\"#7BA34A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:170},{id:\"minecraft:scale\",base:3},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:step_height\",base:1.5},"
			+ "{id:\"minecraft:safe_fall_distance\",base:64},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String SLIME = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Size:1,CustomName:\"Bog Slime\"}";

	private final LivingEntity king;
	private final List<LivingEntity> slimes = new ArrayList<>();
	private Vec3 leap;
	private long leapFrom = -1;

	BogFrogKingFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		king = (LivingEntity) spawn("minecraft:frog", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Bog Frog King";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return king;
	}

	@Override
	boolean alive() {
		return !dead(king);
	}

	@Override
	void tick() {
		slimes.removeIf(BossFight::dead);
		Vec3 at = king.position();
		if (leap != null) {
			if (ticks >= leapFrom && (king.onGround() || ticks > leapFrom + 50)) {
				leap = null;
				sound(king.position(), snd("entity.slime.squish"), 2, 0.5f);
				shockwave(king, king.position(), 4, 5, 1.0, 0.5);
				// Slime blocks splash around where it landed, gone after 5 s.
				BlockPos c = groundAt(king.position());
				for (int i = 0; i < 6; i++) tempBlock(c.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3), Blocks.SLIME_BLOCK.defaultBlockState(), 100);
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		stride(king, p, 0.24, 1.8, 5);
		// The tongue every 7 s: the nearest player further than 3 blocks is pulled in.
		if (ticks % 140 == 40) {
			for (ServerPlayer q : near(at, 14)) {
				if (q.distanceToSqr(king) < 3 * 3) continue;
				line(at.add(0, 1.5, 0), q.position().add(0, 1, 0), ParticleTypes.ITEM_SLIME);
				sound(at, snd("entity.frog.tongue"), 2, 0.6f);
				q.hurtServer(level, level.damageSources().mobAttack(king), 4);
				Vec3 in = at.subtract(q.position()).multiply(1, 0, 1).normalize().scale(1.6);
				push(q, new Vec3(in.x, 0.5, in.z));
				break;
			}
		}
		// A leap every 10 s onto a player.
		if (ticks % 200 == 120) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position()));
			leap = spot;
			leapFrom = Long.MAX_VALUE;
			for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(spot, 3, ParticleTypes.ITEM_SLIME));
			sound(at, snd("entity.frog.long_jump"), 2, 0.6f);
			later(20, () -> {
				if (dead(king)) {
					leap = null;
					return;
				}
				Vec3 d = spot.subtract(king.position());
				king.setDeltaMovement(d.x / 20, 1.1, d.z / 20);
				leapFrom = ticks + 6;
			});
		}
		// Slimes every 15 s, at most four.
		if (ticks % 300 == 200 && slimes.size() < 4) {
			for (int i = 0; i < 2; i++) {
				LivingEntity s = minion("minecraft:slime", at.x + random.nextGaussian() * 2, at.y, at.z + random.nextGaussian() * 2, SLIME, 2);
				if (s != null) slimes.add(s);
			}
		}
		// Hurt: swallows one of its slimes every 12 s to heal 20.
		if (ticks % 240 == 0 && share(king) < 0.5f) {
			for (LivingEntity s : slimes) {
				if (s.distanceToSqr(king) > 10 * 10) continue;
				line(at.add(0, 1.5, 0), s.position(), ParticleTypes.ITEM_SLIME);
				s.discard();
				heal(king, 20);
				sound(at, snd("entity.frog.eat"), 2, 0.6f);
				say(at, 40, styled("The Bog Frog King ", 0x7BA34A, true, false).copy().append(plain("gulps down a slime!")));
				break;
			}
		}
	}
}
