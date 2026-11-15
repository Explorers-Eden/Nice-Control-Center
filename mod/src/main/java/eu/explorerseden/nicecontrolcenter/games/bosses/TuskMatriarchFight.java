package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Tusk Matriarch (NCC): a great hoglin. Marked charges that throw players up, piglets at two thirds and one third,
 * and a weakness: a hit with warped fungus in hand stuns her (every player gets two).
 */
final class TuskMatriarchFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,IsImmuneToZombification:1b,Health:220f,CustomName:{text:\"The Tusk Matriarch\",color:\"#C1704A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:220},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:step_height\",base:1.5},"
			+ "{id:\"minecraft:attack_damage\",base:7},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String PIGLET = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,IsImmuneToZombification:1b,IsBaby:1b,Age:-24000,CustomName:\"Piglet\",attributes:[{id:\"minecraft:max_health\",base:14}]}";

	private final LivingEntity matriarch;
	private Vec3 charge;
	private long chargeUntil = -1;
	private final Set<ServerPlayer> tossed = new HashSet<>();
	private long stunnedUntil = -1;
	private int litters;
	private final Set<ServerPlayer> armed = new HashSet<>();

	TuskMatriarchFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		matriarch = (LivingEntity) spawn("minecraft:hoglin", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Tusk Matriarch";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return matriarch;
	}

	@Override
	boolean alive() {
		return !dead(matriarch);
	}

	/** A hit with warped fungus in hand: stunned 4 s (the fungus is used up). */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == matriarch && source.getEntity() instanceof ServerPlayer p && p.getMainHandItem().is(Items.WARPED_FUNGUS) && ticks >= stunnedUntil) {
			p.getMainHandItem().shrink(1);
			stunnedUntil = ticks + 80;
			charge = null;
			sound(matriarch.position(), snd("entity.hoglin.retreat"), 2, 0.7f);
			level.sendParticles(ParticleTypes.WARPED_SPORE, matriarch.getX(), matriarch.getY() + 1, matriarch.getZ(), 40, 1, 1, 1, 0.05);
			say(matriarch.position(), 40, styled("The Tusk Matriarch ", 0xC1704A, true, false).copy().append(plain("recoils from the fungus!")));
		}
		return true;
	}

	@Override
	void tick() {
		Vec3 at = matriarch.position();
		// Every player in the fight gets two warped fungi (game items, gone after).
		if (ticks % 20 == 1) {
			for (ServerPlayer p : players(at)) if (armed.add(p)) give(p, new ItemStack(Items.WARPED_FUNGUS, 2));
		}
		if (ticks < stunnedUntil) {
			matriarch.setDeltaMovement(0, matriarch.getDeltaMovement().y, 0);
			if (ticks % 5 == 0) level.sendParticles(ParticleTypes.WARPED_SPORE, at.x, at.y + 2, at.z, 6, 0.6, 0.3, 0.6, 0.01);
			return;
		}
		if (ticks <= chargeUntil && charge != null) {
			Vec3 dir = charge.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) matriarch.setDeltaMovement(dir.normalize().scale(1.0).add(0, matriarch.getDeltaMovement().y, 0));
			for (ServerPlayer p : near(at, 2.5)) {
				if (!tossed.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(matriarch), 7);
				push(p, dir.normalize().scale(0.6).add(0, 1.3, 0));
				sound(p.position(), snd("entity.hoglin.attack"), 2, 0.7f);
			}
			return;
		}
		// Piglets at two thirds and one third.
		if (litters == 0 && share(matriarch) < 0.66f || litters == 1 && share(matriarch) < 0.33f) {
			litters++;
			for (int i = 0; i < 3; i++) minion("minecraft:hoglin", at.x + random.nextGaussian() * 2, at.y, at.z + random.nextGaussian() * 2, PIGLET, 3);
			sound(at, snd("entity.hoglin.angry"), 2, 1.4f);
			say(at, 40, styled("The Tusk Matriarch: ", 0xC1704A, true, false).copy().append(plain("Little ones, to me!")));
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		// A charge every 8 s along a marked line.
		if (ticks % 160 == 50) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 6));
			for (int t = 0; t < 20; t += 4) later(t, () -> line(at.add(0, 0.1, 0), end.add(0, 0.1, 0), ParticleTypes.CRIMSON_SPORE));
			sound(at, snd("entity.hoglin.angry"), 2, 0.6f);
			later(20, () -> {
				if (ticks < stunnedUntil) return;
				charge = end;
				chargeUntil = ticks + 22;
				tossed.clear();
			});
		}
	}
}
