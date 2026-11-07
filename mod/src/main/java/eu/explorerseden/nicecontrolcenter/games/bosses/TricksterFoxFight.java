package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Trickster Fox (NCC): a big fox with copies of itself (they vanish when hit; the real one leaves a trail of
 * sparks when it's hit), short blinks with a bite, a pounce on a marked spot, and a pickpocket trick: it snatches what
 * a player holds for five seconds, then gives it back.
 */
final class TricksterFoxFight extends BossFight {
	private static String body(boolean copy) {
		return "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Type:\"snow\",Health:" + (copy ? 10 : 120) + "f,CustomName:{text:\"The Trickster Fox\",color:\"#F28C28\",bold:true},"
				+ "attributes:[{id:\"minecraft:max_health\",base:" + (copy ? 10 : 120) + "},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:attack_damage\",base:5},"
				+ "{id:\"minecraft:knockback_resistance\",base:0.5},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	}

	private final LivingEntity fox;
	private final List<Entity> copies = new ArrayList<>();
	/** What it took and from whom: back after 5 s, or when the fight ends. */
	private final Map<ServerPlayer, ItemStack> stolen = new HashMap<>();
	private Vec3 pounce;
	private long pounceFrom = -1;

	TricksterFoxFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		fox = (LivingEntity) spawn("minecraft:fox", x, y, z, body(false), true, false);
	}

	@Override
	String name() {
		return "The Trickster Fox";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return fox;
	}

	@Override
	boolean alive() {
		return !dead(fox);
	}

	@Override
	void damaged(LivingEntity piece, DamageSource source, float amount) {
		if (copies.contains(piece)) {
			Vec3 at = piece.position();
			level.sendParticles(ParticleTypes.POOF, at.x, at.y + 1, at.z, 20, 0.5, 0.5, 0.5, 0.02);
			sound(at, SoundEvents.FOX_SCREECH, 1, 1.4f);
			piece.discard();
			copies.remove(piece);
		} else if (piece == fox) {
			level.sendParticles(ParticleTypes.CRIT, fox.getX(), fox.getY() + 1, fox.getZ(), 15, 0.6, 0.6, 0.6, 0.1);
		}
	}

	@Override
	void ended() {
		for (Map.Entry<ServerPlayer, ItemStack> e : stolen.entrySet()) giveBack(e.getKey(), e.getValue());
		stolen.clear();
	}

	/** Items for players who weren't online when they were due: handed over when they join (see KatterBosses). */
	static final Map<java.util.UUID, List<ItemStack>> owed = new HashMap<>();

	private void giveBack(ServerPlayer old, ItemStack st) {
		if (st.isEmpty()) return;
		ServerPlayer p = level.getServer().getPlayerList().getPlayer(old.getUUID());
		if (p == null) {
			// Gone meanwhile: into their inbox (/inbox), which is saved.
			eu.explorerseden.nicecontrolcenter.claims.GameClaims.inbox(old, List.of(st));
			return;
		}
		handBack(p, st);
	}

	/** On joining: what the fox still owed them. */
	static void joined(ServerPlayer p) {
		List<ItemStack> back = owed.remove(p.getUUID());
		if (back == null) return;
		for (ItemStack st : back) handBack(p, st);
	}

	private static void handBack(ServerPlayer p, ItemStack st) {
		if (p.getMainHandItem().isEmpty()) p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, st);
		else if (!p.getInventory().add(st)) p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
		p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("You got it back.").withStyle(net.minecraft.ChatFormatting.GOLD));
	}

	@Override
	void tick() {
		copies.removeIf(BossFight::dead);
		Vec3 at = fox.position();
		if (ticks % 20 == 0 && fox instanceof net.minecraft.world.entity.Mob m) m.setTarget(nearest(at, 40));
		// The pounce: in the air until it lands, then whoever is at the spot.
		if (pounce != null) {
			if (ticks >= pounceFrom && (fox.onGround() || ticks > pounceFrom + 40)) {
				for (ServerPlayer p : near(fox.position(), 2.5)) p.hurtServer(level, level.damageSources().mobAttack(fox), 6);
				level.sendParticles(ParticleTypes.SNOWFLAKE, fox.getX(), fox.getY() + 0.3, fox.getZ(), 30, 1.2, 0.2, 1.2, 0.05);
				pounce = null;
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		// A blink and a bite every 5 s.
		if (ticks % 100 == 20 && p.distanceToSqr(fox) > 4 * 4) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 to = at.add(d.normalize().scale(Math.max(0, Math.sqrt(d.lengthSqr()) - 1.5)));
			level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.5, at.z, 10, 0.3, 0.3, 0.3, 0.02);
			fox.teleportTo(to.x, groundAt(to.add(0, 2, 0)).getY(), to.z);
			later(4, () -> {
				if (!dead(fox) && fox.distanceToSqr(p) < 9) p.hurtServer(level, level.damageSources().mobAttack(fox), 5);
			});
			sound(to, SoundEvents.FOX_BITE, 1.5f, 1);
		}
		// Copies every 18 s (three at a time, up to three).
		if (ticks % 360 == 120 && copies.isEmpty()) {
			for (int i = 0; i < 3; i++) {
				double a = Math.PI * 2 * i / 3;
				Entity c = spawn("minecraft:fox", at.x + Math.cos(a) * 3, at.y, at.z + Math.sin(a) * 3, body(true), false, true);
				if (c != null) copies.add(c);
			}
			level.sendParticles(ParticleTypes.POOF, at.x, at.y + 1, at.z, 30, 2, 0.5, 2, 0.02);
			sound(at, SoundEvents.FOX_AMBIENT, 2, 0.6f);
		}
		// A pounce every 11 s.
		if (ticks % 220 == 160) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position()));
			pounce = spot;
			pounceFrom = Long.MAX_VALUE;
			for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(spot, 2, ParticleTypes.SNOWFLAKE));
			later(20, () -> {
				if (dead(fox)) {
					pounce = null;
					return;
				}
				Vec3 d = spot.subtract(fox.position());
				fox.setDeltaMovement(d.x / 16, 1.0, d.z / 16);
				pounceFrom = ticks + 5;
				sound(fox.position(), SoundEvents.FOX_AGGRO, 1.5f, 1);
			});
		}
		// The pickpocket every 15 s: what the nearest player holds, for 5 s.
		if (ticks % 300 == 240 && !stolen.containsKey(p) && !p.getMainHandItem().isEmpty() && p.distanceToSqr(fox) < 12 * 12) {
			ItemStack st = p.getMainHandItem().copy();
			p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			stolen.put(p, st);
			sound(p.position(), SoundEvents.FOX_SPIT, 1.5f, 1.2f);
			p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("The Trickster Fox snatched what you were holding!").withStyle(net.minecraft.ChatFormatting.RED));
			later(100, () -> {
				ItemStack back = stolen.remove(p);
				if (back != null) giveBack(p, back);
			});
		}
	}
}
