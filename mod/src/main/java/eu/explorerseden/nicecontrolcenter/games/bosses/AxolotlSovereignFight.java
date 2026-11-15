package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Axolotl Sovereign (NCC): a giant axolotl. It heals while wet, leaves puddles under players, plays dead once
 * when low (untouchable and healing, unless it's set alight), and calls axolotl kin to bite.
 */
final class AxolotlSovereignFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Variant:2,Health:160f,CustomName:{text:\"The Axolotl Sovereign\",color:\"#F59BC7\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:3},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:step_height\",base:1.5},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String KIN = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Variant:0,CustomName:\"Axolotl Kin\",attributes:[{id:\"minecraft:max_health\",base:8}]}";

	private final LivingEntity sovereign;
	private final List<LivingEntity> kin = new ArrayList<>();
	private boolean playedDead;
	private long deadUntil = -1;

	AxolotlSovereignFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		sovereign = (LivingEntity) spawn("minecraft:axolotl", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Axolotl Sovereign";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.PINK;
	}

	@Override
	LivingEntity body() {
		return sovereign;
	}

	@Override
	boolean alive() {
		return !dead(sovereign);
	}

	private boolean playingDead() {
		return ticks < deadUntil;
	}

	/** Playing dead: nothing gets through, except fire (it dries it out and wakes it). */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece != sovereign || !playingDead()) return true;
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || sovereign.isOnFire()) {
			deadUntil = ticks;
			say(sovereign.position(), 40, styled("The Axolotl Sovereign: ", 0xF59BC7, true, false).copy().append(plain("Hot! Too hot!")));
			return true;
		}
		level.sendParticles(ParticleTypes.BUBBLE_POP, sovereign.getX(), sovereign.getY() + 1, sovereign.getZ(), 6, 0.6, 0.4, 0.6, 0.05);
		return false;
	}

	@Override
	void tick() {
		// Never dries out on land (a boss isn't beaten by the sun).
		sovereign.setAirSupply(sovereign.getMaxAirSupply());
		kin.removeIf(BossFight::dead);
		Vec3 at = sovereign.position();
		boolean wet = sovereign.isInWaterOrRain() || level.getBlockState(sovereign.blockPosition()).is(Blocks.WATER);
		if (ticks % 20 == 0 && (wet || playingDead())) {
			heal(sovereign, playingDead() ? 4 : 2);
			level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 1, at.z, 12, 0.8, 0.5, 0.8, 0.1);
		}
		// Low: plays dead once, 6 s.
		if (!playedDead && share(sovereign) < 0.25f) {
			playedDead = true;
			deadUntil = ticks + 120;
			sound(at, snd("entity.axolotl.death"), 1.5f, 0.6f);
			say(at, 40, styled("The Axolotl Sovereign ", 0xF59BC7, true, false).copy().append(plain("goes limp… (fire wakes it)")));
		}
		if (playingDead()) {
			sovereign.setDeltaMovement(0, sovereign.getDeltaMovement().y, 0);
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		stride(sovereign, p, 0.28, 1.6, 6);
		// Puddles under everyone every 8 s (gone again after 6 s).
		if (ticks % 160 == 60) {
			sound(at, snd("entity.axolotl.splash"), 2, 0.6f);
			for (ServerPlayer q : near(at, 28)) {
				BlockPos b = groundAt(q.position());
				for (BlockPos c : new BlockPos[] { b, b.north(), b.south(), b.east(), b.west() }) tempBlock(c, Blocks.WATER.defaultBlockState(), 120, true);
				level.sendParticles(ParticleTypes.SPLASH, q.getX(), q.getY() + 0.2, q.getZ(), 20, 1, 0.1, 1, 0.1);
			}
		}
		// A water jet every 6 s at someone further away: slows and pushes.
		if (ticks % 120 == 30) {
			for (ServerPlayer q : near(at, 24)) {
				if (q.distanceToSqr(sovereign) < 6 * 6) continue;
				line(at.add(0, 1.5, 0), q.position().add(0, 1, 0), ParticleTypes.SPLASH);
				q.hurtServer(level, level.damageSources().mobAttack(sovereign), 4);
				effect(q, MobEffects.SLOWNESS, 60, 1);
				push(q, q.position().subtract(at).multiply(1, 0, 1).normalize().scale(1.2).add(0, 0.4, 0));
				break;
			}
		}
		// Kin every 15 s, at most four.
		if (ticks % 300 == 100 && kin.size() < 4) {
			for (int i = 0; i < 2; i++) {
				LivingEntity k = minion("minecraft:axolotl", at.x + random.nextGaussian() * 2, at.y, at.z + random.nextGaussian() * 2, KIN, 3);
				if (k != null) kin.add(k);
			}
			sound(at, snd("entity.axolotl.idle_air"), 2, 0.8f);
		}
		for (LivingEntity k : kin) k.setAirSupply(k.getMaxAirSupply());
	}
}
