package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

/**
 * The Llama Matriarch (NCC): a big llama in a decorated blanket with her caravan. Spit volleys from all of them, a
 * stampede of the caravan along a marked line, and her lead lash pulls the farthest player in.
 */
final class LlamaMatriarchFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Tame:0b,Variant:1,Health:140f,CustomName:{text:\"The Llama Matriarch\",color:\"#E3B778\",bold:true},"
			+ "equipment:{body:{id:\"minecraft:red_carpet\",count:1}},drop_chances:{body:0.0},attributes:[{id:\"minecraft:max_health\",base:140},{id:\"minecraft:scale\",base:2.5},"
			+ "{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String CARAVAN = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Tame:0b,CustomName:\"Caravan Llama\",equipment:{body:{id:\"minecraft:yellow_carpet\",count:1}},"
			+ "drop_chances:{body:0.0},attributes:[{id:\"minecraft:max_health\",base:20}]}";

	private final LivingEntity matriarch;
	private final List<Entity> caravan = new ArrayList<>();
	private Vec3 stampede;
	private long stampedeUntil = -1;
	private final Set<ServerPlayer> trampled = new HashSet<>();

	LlamaMatriarchFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		matriarch = (LivingEntity) spawn("minecraft:llama", x, y, z, BODY, true, false);
		for (int i = 0; i < 3 && matriarch != null; i++) {
			Entity c = spawn("minecraft:llama", x + random.nextGaussian() * 2, y, z + random.nextGaussian() * 2, CARAVAN, false, false);
			if (c != null) caravan.add(c);
		}
	}

	@Override
	String name() {
		return "The Llama Matriarch";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return matriarch;
	}

	@Override
	boolean alive() {
		return !dead(matriarch);
	}

	/** A spit from the llama at the player. */
	private void spit(Entity from, ServerPlayer p) {
		if (!(from instanceof Llama l) || dead(l)) return;
		LlamaSpit s = new LlamaSpit(level, l);
		Vec3 d = p.getEyePosition().subtract(s.position());
		s.shoot(d.x, d.y + Math.sqrt(d.x * d.x + d.z * d.z) * 0.15, d.z, 1.6f, 6f);
		adopt(s, false, true);
		sound(from.position(), SoundEvents.LLAMA_SPIT, 1.5f, 1);
	}

	@Override
	void tick() {
		caravan.removeIf(BossFight::dead);
		Vec3 at = matriarch.position();
		// The stampede: the caravan runs the line.
		if (ticks <= stampedeUntil && stampede != null) {
			for (Entity c : caravan) {
				Vec3 d = stampede.subtract(c.position()).multiply(1, 0, 1);
				if (d.lengthSqr() > 1) c.setDeltaMovement(d.normalize().scale(0.8).add(0, c.getDeltaMovement().y, 0));
				for (ServerPlayer p : near(c.position(), 1.8)) {
					if (!trampled.add(p)) continue;
					p.hurtServer(level, level.damageSources().mobAttack(matriarch), 5);
					push(p, d.normalize().scale(1.5).add(0, 0.5, 0));
				}
			}
		}
		ServerPlayer near = nearest(at, 32);
		if (near == null) return;
		// Closing in when players keep away.
		if (near.distanceToSqr(matriarch) > 10 * 10 && ticks % 5 == 0) {
			Vec3 d = near.position().subtract(at).multiply(1, 0, 1).normalize();
			matriarch.setDeltaMovement(d.scale(0.18).add(0, matriarch.getDeltaMovement().y, 0));
		}
		// Spit every 4 s: her and each caravan llama at a player.
		if (ticks % 80 == 0) {
			List<ServerPlayer> ps = near(at, 28);
			if (!ps.isEmpty()) {
				spit(matriarch, ps.get(random.nextInt(ps.size())));
				int i = 1;
				for (Entity c : caravan) {
					ServerPlayer t = ps.get(random.nextInt(ps.size()));
					later(i++ * 4, () -> spit(c, t));
				}
			}
		}
		// A stampede every 15 s along a marked line.
		if (ticks % 300 == 150 && !caravan.isEmpty()) {
			Vec3 d = near.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? near.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 6));
			for (int t = 0; t < 25; t += 5) later(t, () -> {
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = at.add(end.subtract(at).scale(f));
					level.sendParticles(ParticleTypes.CLOUD, q.x, q.y + 0.1, q.z, 1, 0, 0, 0, 0);
				}
			});
			sound(at, SoundEvents.LLAMA_ANGRY, 2, 0.7f);
			say(at, 40, styled("The Llama Matriarch: ", 0xE3B778, true, false).copy().append(plain("Caravan, charge!")));
			later(25, () -> {
				stampede = end;
				stampedeUntil = ticks + 30;
				trampled.clear();
			});
		}
		// The lead lash every 12 s: the farthest player within 30 blocks is pulled in.
		if (ticks % 240 == 60) {
			ServerPlayer far = null;
			for (ServerPlayer p : near(at, 30)) if (far == null || p.distanceToSqr(matriarch) > far.distanceToSqr(matriarch)) far = p;
			if (far != null && far.distanceToSqr(matriarch) > 6 * 6) {
				Vec3 in = at.subtract(far.position()).multiply(1, 0, 1).normalize().scale(1.8);
				push(far, new Vec3(in.x, 0.6, in.z));
				sound(far.position(), net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.withDefaultNamespace("entity.leash_knot.place")), 2, 0.8f);
				Vec3 from = far.position().add(0, 1, 0);
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = from.add(at.add(0, 2, 0).subtract(from).scale(f));
					level.sendParticles(ParticleTypes.CRIT, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
			}
		}
	}
}
