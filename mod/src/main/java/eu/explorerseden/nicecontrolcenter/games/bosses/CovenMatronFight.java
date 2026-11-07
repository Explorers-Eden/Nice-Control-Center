package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Coven Matron (NCC): a witch who brews. She throws extra potions, puts lingering clouds at players' feet, and at
 * two thirds and one third of her health three copies of her appear: a hit makes a copy vanish in smoke, and the
 * real one glows for a moment when she's hit.
 */
final class CovenMatronFight extends BossFight {
	private static String body(String name, boolean decoy) {
		return "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:" + (decoy ? 20 : 180) + "f,CustomName:{text:\"" + name + "\",color:\"#B05BD6\",bold:true},"
				+ "attributes:[{id:\"minecraft:max_health\",base:" + (decoy ? 20 : 180) + "},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:0.6},"
				+ "{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	}

	private static final String[] POTIONS = { "minecraft:poison", "minecraft:slowness", "minecraft:weakness", "minecraft:harming" };
	private static final int[] COLORS = { 0x5E7C16, 0x9D9D97, 0x1D1D21, 0xB02E26 };

	private final LivingEntity matron;
	private final List<Entity> copies = new ArrayList<>();
	private int covens;

	CovenMatronFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		matron = (LivingEntity) spawn("minecraft:witch", x, y, z, body("The Coven Matron", false), true, false);
	}

	@Override
	String name() {
		return "The Coven Matron";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.PURPLE;
	}

	@Override
	LivingEntity body() {
		return matron;
	}

	@Override
	boolean alive() {
		return !dead(matron);
	}

	/** A copy that's hit vanishes; the real one glows briefly. */
	@Override
	void damaged(LivingEntity piece, DamageSource source, float amount) {
		if (copies.contains(piece)) {
			Vec3 at = piece.position();
			level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.5, at.z, 30, 0.5, 1, 0.5, 0.02);
			sound(at, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.5f, 1);
			piece.discard();
			copies.remove(piece);
		} else if (piece == matron && !copies.isEmpty()) {
			effect(matron, MobEffects.GLOWING, 40, 0);
		}
	}

	@Override
	void tick() {
		copies.removeIf(BossFight::dead);
		Vec3 at = matron.position();
		// A potion at a player every 4 s (copies throw too).
		if (ticks % 80 == 0) {
			List<LivingEntity> throwers = new ArrayList<>(List.of(matron));
			for (Entity c : copies) if (c instanceof LivingEntity l) throwers.add(l);
			for (LivingEntity w : throwers) {
				ServerPlayer p = nearest(w.position(), 24);
				if (p != null) potion(w, p);
			}
		}
		// A lingering cloud at a player's feet every 7 s.
		if (ticks % 140 == 70) {
			ServerPlayer p = nearest(at, 24);
			if (p != null) {
				int k = random.nextInt(POTIONS.length);
				spawn("minecraft:area_effect_cloud", p.getX(), p.getY(), p.getZ(), "{custom_particle:{type:\"entity_effect\",color:" + COLORS[k] + "},ReapplicationDelay:10,Radius:2.5f,"
						+ "Duration:120,potion_contents:{potion:\"" + POTIONS[k] + "\"}}", false, true);
				sound(p.position(), SoundEvents.BREWING_STAND_BREW, 1.5f, 0.8f);
			}
		}
		if (covens == 0 && share(matron) <= 0.66f || covens == 1 && share(matron) <= 0.33f) {
			covens++;
			coven(at);
		}
	}

	/** Three copies around her; she moves to a random one of the four spots. */
	private void coven(Vec3 at) {
		say(at, 40, styled("The Coven Matron: ", 0xB05BD6, true, false).copy().append(plain("Which of us is real, dearie?")));
		sound(at, SoundEvents.ILLUSIONER_PREPARE_MIRROR, 2, 1);
		List<Vec3> spots = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 2 * i;
			spots.add(at.add(Math.cos(a) * 4, 0, Math.sin(a) * 4));
		}
		java.util.Collections.shuffle(spots, new java.util.Random(random.nextLong()));
		Vec3 real = spots.remove(0);
		matron.teleportTo(real.x, real.y, real.z);
		for (Vec3 s : spots) {
			Entity c = spawn("minecraft:witch", s.x, s.y, s.z, body("The Coven Matron", true), false, true);
			if (c != null) copies.add(c);
		}
		for (Vec3 s : new ArrayList<>(List.of(real))) level.sendParticles(ParticleTypes.LARGE_SMOKE, s.x, s.y + 1.5, s.z, 20, 0.5, 1, 0.5, 0.02);
	}

	/** A splash potion from the witch at the player. */
	private void potion(LivingEntity from, ServerPlayer p) {
		Vec3 eye = from.getEyePosition();
		Vec3 to = p.position().add(p.getDeltaMovement().scale(8)).add(0, 1, 0).subtract(eye);
		double h = Math.sqrt(to.x * to.x + to.z * to.z);
		Vec3 m = new Vec3(to.x, to.y + h * 0.2, to.z).normalize().scale(0.75);
		String potion = POTIONS[random.nextInt(POTIONS.length)];
		spawn("minecraft:splash_potion", eye.x, eye.y, eye.z, "{Motion:[" + m.x + "d," + m.y + "d," + m.z + "d],Item:{id:\"minecraft:splash_potion\",count:1,components:"
				+ "{\"minecraft:potion_contents\":{potion:\"" + potion + "\"}}}}", false, true);
		sound(eye, SoundEvents.WITCH_THROW, 1, 1);
	}
}
