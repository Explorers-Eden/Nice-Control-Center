package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Frost Warden (NCC): a giant stray. A freezing aura slows those close by, icicles fall where a ring of frost
 * warns of them, powder snow opens under players' feet; at half health four strays come and a blizzard blinds
 * everyone for a while. Arrows close to it lose their speed.
 */
final class FrostWardenFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:220f,CustomName:{text:\"The Frost Warden\",color:\"aqua\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:bow\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:power\":2}}},head:{id:\"minecraft:diamond_helmet\",count:1}},"
			+ "drop_chances:{mainhand:0.0,head:0.0},attributes:[{id:\"minecraft:max_health\",base:220},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:knockback_resistance\",base:0.8},"
			+ "{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String STRAY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Frost Guard\",equipment:{mainhand:{id:\"minecraft:bow\",count:1}},drop_chances:{mainhand:0.0}}";
	private static final String ICICLE = "{teleport_duration:12,block_state:{Name:\"minecraft:pointed_dripstone\",Properties:{thickness:\"tip\",vertical_direction:\"down\"}},"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-0.5f,0f,-0.5f],scale:[1f,1.5f,1f]}}";

	private final LivingEntity warden;
	private boolean guards;
	private long blizzardUntil = -1;

	FrostWardenFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		warden = (LivingEntity) spawn("minecraft:stray", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Frost Warden";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return warden;
	}

	@Override
	boolean alive() {
		return !dead(warden);
	}

	@Override
	void tick() {
		defend(warden, 8);
		Vec3 at = warden.position();
		if (ticks <= blizzardUntil && ticks % 4 == 0) {
			for (ServerPlayer p : near(at, 40)) level.sendParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 2, p.getZ(), 30, 4, 2, 4, 0.05);
		}
		if (ticks % 40 == 0) {
			for (ServerPlayer p : near(at, 12)) effect(p, MobEffects.SLOWNESS, 60, 0);
			level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 1, at.z, 40, 6, 1, 6, 0.02);
		}
		// Icicles every 6 s, powder snow every 9 s.
		if (ticks % 120 == 0) for (ServerPlayer p : near(at, 30)) icicle(p.position());
		if (ticks % 180 == 90) {
			ServerPlayer p = nearest(at, 24);
			if (p != null) powderSnow(p.blockPosition());
		}
		if (!guards && share(warden) <= 0.5f) {
			guards = true;
			say(at, 40, styled("The Frost Warden: ", 0x55FFFF, true, false).copy().append(plain("Let the cold take you!")));
			sound(at, SoundEvents.STRAY_AMBIENT, 2, 0.5f);
			for (int i = 0; i < 4; i++) {
				double a = Math.PI / 2 * i;
				spawn("minecraft:stray", at.x + Math.cos(a) * 4, at.y, at.z + Math.sin(a) * 4, STRAY, false, false);
			}
			blizzardUntil = ticks + 120;
			for (ServerPlayer p : near(at, 40)) {
				effect(p, MobEffects.BLINDNESS, 120, 0);
				effect(p, MobEffects.SLOWNESS, 120, 1);
			}
		}
	}

	/** A ring of frost where it will land, then an icicle falls from above. */
	private void icicle(Vec3 target) {
		BlockPos ground = groundAt(target);
		Vec3 spot = Vec3.atBottomCenterOf(ground);
		for (int i = 0; i < 30; i += 5) later(i, () -> telegraph(spot, 1.2, ParticleTypes.SNOWFLAKE));
		Entity d = spawn("minecraft:block_display", spot.x, spot.y + 12, spot.z, ICICLE, false, true);
		later(30, () -> {
			if (d != null) d.teleportTo(spot.x, spot.y, spot.z);
		});
		later(42, () -> {
			if (d != null) d.discard();
			level.sendParticles(ParticleTypes.ITEM_SNOWBALL, spot.x, spot.y + 0.5, spot.z, 25, 0.6, 0.3, 0.6, 0.1);
			sound(spot, SoundEvents.GLASS_BREAK, 1, 1.4f);
			for (ServerPlayer p : near(spot, 1.6)) {
				p.hurtServer(level, level.damageSources().mobAttack(warden), 7);
				effect(p, MobEffects.SLOWNESS, 60, 1);
			}
		});
	}

	/** Powder snow under the player's feet for 8 seconds. */
	private void powderSnow(BlockPos at) {
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) tempBlock(at.offset(dx, 0, dz), Blocks.POWDER_SNOW.defaultBlockState(), 160);
		sound(Vec3.atCenterOf(at), SoundEvents.POWDER_SNOW_PLACE, 1, 0.8f);
	}
}
