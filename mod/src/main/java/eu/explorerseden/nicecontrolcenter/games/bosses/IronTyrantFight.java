package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Iron Tyrant (NCC): a towering iron golem that turned on the players. Ground slams launch those close by,
 * boulders land where a ring of dust warned; at half health it plates up: more armour, slower, harder hits.
 */
final class IronTyrantFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,PlayerCreated:0b,Health:300f,CustomName:{text:\"The Iron Tyrant\",color:\"#A8A8A8\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:300},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String BOULDER = "{teleport_duration:14,block_state:{Name:\"minecraft:cobblestone\"},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[-0.75f,0f,-0.75f],scale:[1.5f,1.5f,1.5f]}}";

	private final LivingEntity tyrant;
	private boolean plated;

	IronTyrantFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		tyrant = (LivingEntity) spawn("minecraft:iron_golem", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Iron Tyrant";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return tyrant;
	}

	@Override
	boolean alive() {
		return !dead(tyrant);
	}

	@Override
	void tick() {
		Vec3 at = tyrant.position();
		// Always after the nearest player (golems don't go for players on their own).
		if (ticks % 20 == 0) {
			ServerPlayer p = nearest(at, 40);
			if (p != null && tyrant instanceof Mob m) {
				if (tyrant instanceof NeutralMob n) n.startPersistentAngerTimer();
				m.setTarget(p);
			}
		}
		// A ground slam every 9 s when someone is close.
		if (ticks % 180 == 60 && !near(at, 7).isEmpty()) {
			for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(tyrant.position(), 6, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState())));
			later(20, () -> {
				Vec3 c = tyrant.position();
				sound(c, SoundEvents.IRON_GOLEM_ATTACK, 3, 0.5f);
				shockwave(tyrant, c, 7, plated ? 9 : 6, 0.8, 1.1);
			});
		}
		// A boulder every 7 s at a player further away.
		if (ticks % 140 == 0) {
			for (ServerPlayer p : near(at, 30)) {
				if (p.distanceToSqr(tyrant) < 8 * 8) continue;
				boulder(p.position());
				break;
			}
		}
		if (!plated && share(tyrant) <= 0.5f) {
			plated = true;
			var armor = tyrant.getAttribute(Attributes.ARMOR);
			if (armor != null) armor.setBaseValue(14);
			var kb = tyrant.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
			if (kb != null) kb.setBaseValue(1);
			var speed = tyrant.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speed != null) speed.setBaseValue(speed.getBaseValue() * 0.75);
			var dmg = tyrant.getAttribute(Attributes.ATTACK_DAMAGE);
			if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * 1.4);
			sound(at, SoundEvents.ANVIL_LAND, 2, 0.5f);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 2, at.z, 60, 1.5, 2, 1.5, 0.2);
			say(at, 40, styled("The Iron Tyrant ", 0xA8A8A8, true, false).copy().append(plain("plates itself in iron.")));
		}
	}

	/** A ring of dust where it lands, then a boulder drops there: damage, and it's gone. */
	private void boulder(Vec3 target) {
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(target));
		for (int t = 0; t < 30; t += 5) later(t, () -> telegraph(spot, 1.6, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState())));
		Entity d = spawn("minecraft:block_display", spot.x, spot.y + 14, spot.z, BOULDER, false, true);
		sound(tyrant.position(), SoundEvents.IRON_GOLEM_ATTACK, 1.5f, 0.7f);
		later(30, () -> {
			if (d != null) d.teleportTo(spot.x, spot.y, spot.z);
		});
		later(44, () -> {
			if (d != null) d.discard();
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()), spot.x, spot.y + 0.5, spot.z, 40, 0.8, 0.3, 0.8, 0.1);
			sound(spot, SoundEvents.STONE_BREAK, 2, 0.6f);
			for (ServerPlayer p : near(spot, 2)) p.hurtServer(level, level.damageSources().mobAttack(tyrant), 10);
		});
	}
}
