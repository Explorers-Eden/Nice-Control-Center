package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.games.Aggro;

/**
 * The Master Smith (NCC): the village armorer with a heavy hammer. He slams the ground, drops anvils where rings
 * warned, forges copper golems at his forge (break the copper block in time or a Forged Golem steps out) and tempers
 * the village's golems: resistance for a while.
 */
final class MasterSmithFight extends BossFight {
	private static final int RGB = 0x6E6E78;
	private static final String FORGED = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Forged Golem\","
			+ "attributes:[{id:\"minecraft:max_health\",base:30},{id:\"minecraft:scale\",base:1.4},{id:\"minecraft:movement_speed\",base:0.3}]}";

	private final LivingEntity smith;
	private final List<Entity> forged = new ArrayList<>();
	private Target forge;
	private long forgeDone = -1;

	MasterSmithFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		smith = (LivingEntity) spawn("minecraft:villager", x, y, z, villager("The Master Smith", "#6E6E78", "armorer", 160, 2.5), true, false);
		later(10, () -> {
			if (!dead(smith) && rally(smith.position(), 32) > 0)
				say(smith.position(), 40, styled("The Master Smith ", RGB, true, false).copy().append(plain("bangs his hammer: the village takes up arms!")));
		});
	}

	@Override
	String name() {
		return "The Master Smith";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return smith;
	}

	@Override
	boolean alive() {
		return !dead(smith);
	}

	@Override
	void tick() {
		forged.removeIf(BossFight::dead);
		Vec3 at = smith.position();
		ServerPlayer p = nearest(at, 40);
		if (p != null) stride(smith, p, 0.07, 2, 5);
		// The hammer slam every 8 s when someone is close.
		if (ticks % 160 == 40 && !near(at, 7).isEmpty()) {
			for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(smith.position(), 5, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ANVIL.defaultBlockState())));
			smith.setDeltaMovement(Vec3.ZERO);
			later(20, () -> {
				if (dead(smith)) return;
				sound(smith.position(), SoundEvents.ANVIL_LAND, 2.5f, 0.6f);
				shockwave(smith, smith.position(), 6, 6, 1.0, 0.5);
			});
		}
		// Anvils every 10 s on three spots (the players', and around them).
		if (ticks % 200 == 100) {
			List<ServerPlayer> ps = near(at, 32);
			for (int i = 0; i < 3 && !ps.isEmpty(); i++) {
				Vec3 c = ps.get(i % ps.size()).position();
				Vec3 spot = i < ps.size() ? c : c.add(random.nextGaussian() * 3, 0, random.nextGaussian() * 3);
				dropBlock(spot, "minecraft:anvil", 30, smith, 1.6, 7, null);
			}
			if (!ps.isEmpty()) sound(at, SoundEvents.ANVIL_USE, 2, 0.7f);
		}
		// The forge: a copper block next to him; not broken in 8 s, it becomes a golem (two at most).
		if (forge == null && ticks % 400 == 200 && forged.size() < 2) {
			double a = random.nextDouble() * Math.PI * 2;
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(at.add(Math.cos(a) * 4, 0, Math.sin(a) * 4)));
			forge = target("minecraft:copper_block", spot, 30, "Golem Forge", true);
			forgeDone = ticks + 160;
			sound(spot, SoundEvents.SMITHING_TABLE_USE, 2, 0.8f);
			say(at, 40, styled("The Master Smith ", RGB, true, false).copy().append(plain("starts forging a golem. Break the copper!")));
		}
		if (forge != null) {
			Vec3 f = forge.hitbox == null ? at : forge.hitbox.position();
			if (forge.broken()) {
				forge = null;
				say(at, 40, styled("The Master Smith ", RGB, true, false).copy().append(plain("curses his ruined work.")));
			} else if (ticks >= forgeDone) {
				if (forge.display != null) forge.display.discard();
				if (forge.hitbox != null) forge.hitbox.discard();
				forge = null;
				Entity g = spawn("minecraft:copper_golem", f.x, f.y, f.z, FORGED, false, false);
				if (g instanceof Mob m) {
					Aggro.make(m, 4);
					forged.add(g);
					if (p != null) m.setTarget(p);
				}
				sound(f, SoundEvents.ANVIL_USE, 2, 1.2f);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, f.x, f.y + 1, f.z, 30, 0.5, 0.6, 0.5, 0.2);
			} else if (ticks % 5 == 0) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, f.x, f.y + 1, f.z, 4, 0.4, 0.4, 0.4, 0.1);
				if (ticks % 20 == 0) sound(f, SoundEvents.ANVIL_USE, 1, 1.5f);
			}
		}
		// Tempering every 25 s: the golems take less damage for 6 s.
		if (ticks % 500 == 350) {
			List<LivingEntity> golems = new ArrayList<>();
			for (Mob m : rallied()) if (m instanceof net.minecraft.world.entity.animal.golem.AbstractGolem) golems.add(m);
			for (Entity g : forged) if (g instanceof LivingEntity l) golems.add(l);
			for (LivingEntity g : golems) {
				effect(g, MobEffects.RESISTANCE, 120, 1);
				level.sendParticles(ParticleTypes.WAX_ON, g.getX(), g.getY() + g.getBbHeight() / 2, g.getZ(), 12, 0.4, 0.6, 0.4, 0.1);
			}
			if (!golems.isEmpty()) {
				sound(at, SoundEvents.GRINDSTONE_USE, 2, 0.8f);
				say(at, 40, styled("The Master Smith ", RGB, true, false).copy().append(plain("tempers his golems: they shrug off blows for a while.")));
			}
		}
	}
}
