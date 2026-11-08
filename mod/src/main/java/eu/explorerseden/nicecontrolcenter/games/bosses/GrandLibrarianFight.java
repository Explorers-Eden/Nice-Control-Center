package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Grand Librarian (NCC): a librarian floating among three enchanted books. While books circle him he takes only
 * 30 % damage; each book casts its own spell (levitation, slowness, a harming bolt). He hushes a spot (weakness and
 * blindness), raises bookshelf walls, and at half health his books come back once.
 */
final class GrandLibrarianFight extends BossFight {
	private static final int RGB = 0xC9A15A;
	private static final String[] SPELLS = { "Levitation", "Slowness", "Harming" };

	private final LivingEntity librarian;
	/** The books, by spell (null: broken). */
	private final Target[] books = new Target[3];
	private boolean rewritten;

	GrandLibrarianFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		librarian = (LivingEntity) spawn("minecraft:villager", x, y + 1.5, z, villager("The Grand Librarian", "#C9A15A", "librarian", 120, 2), true, false);
		if (librarian != null) librarian.setNoGravity(true);
		writeBooks();
		later(10, () -> {
			if (!dead(librarian) && rally(librarian.position(), 32) > 0)
				say(librarian.position(), 40, styled("The Grand Librarian ", RGB, true, false).copy().append(plain("closes his book: the village comes to his aid.")));
		});
	}

	private void writeBooks() {
		if (librarian == null) return;
		for (int i = 0; i < 3; i++) if (books[i] == null || books[i].broken()) books[i] = target("item:minecraft:enchanted_book", librarian.position(), 20, SPELLS[i] + " Book", true);
	}

	@Override
	String name() {
		return "The Grand Librarian";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return librarian;
	}

	@Override
	boolean alive() {
		return !dead(librarian);
	}

	private boolean shielded() {
		for (Target b : books) if (b != null && !b.broken()) return true;
		return false;
	}

	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == librarian && amount > 0 && shielded()) {
			level.sendParticles(ParticleTypes.ENCHANT, librarian.getX(), librarian.getY() + 2, librarian.getZ(), 20, 0.6, 0.8, 0.6, 0.5);
			hurtAs(librarian, amount * 0.3f, source.getEntity());
			return false;
		}
		return true;
	}

	@Override
	void tick() {
		Vec3 at = librarian.position();
		ServerPlayer p = nearest(at, 40);
		// Floating 2 blocks over the ground, keeping 6–10 blocks from the nearest player.
		Vec3 ground = Vec3.atBottomCenterOf(groundAt(at));
		double dy = ground.y + 2 - at.y;
		Vec3 move = new Vec3(0, Math.max(-0.1, Math.min(0.1, dy * 0.2)), 0);
		if (p != null) {
			face(librarian, p.position());
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			double dist = Math.sqrt(d.lengthSqr());
			if (dist > 10) move = move.add(d.normalize().scale(0.06));
			else if (dist < 6 && dist > 0.1) move = move.add(d.normalize().scale(-0.08));
		}
		librarian.setDeltaMovement(move);
		// The books circle him.
		double spin = ticks * 0.06;
		for (int i = 0; i < 3; i++) {
			Target b = books[i];
			if (b == null || b.broken()) continue;
			double a = spin + Math.PI * 2 * i / 3;
			b.moveTo(at.add(Math.cos(a) * 2.6, 1.6 + Math.sin(spin * 2 + i) * 0.3, Math.sin(a) * 2.6));
		}
		// Each book casts every 5 s, one after the other.
		if (ticks % 100 == 0 || ticks % 100 == 33 || ticks % 100 == 66) {
			int i = (int) (ticks % 100 / 33);
			Target b = books[Math.min(2, i)];
			List<ServerPlayer> ps = near(at, 30);
			if (b != null && !b.broken() && !ps.isEmpty()) cast(Math.min(2, i), b.hitbox.position(), ps.get(random.nextInt(ps.size())));
		}
		// Hush every 13 s: a quiet zone where a player stands.
		List<ServerPlayer> hush = ticks % 260 == 130 ? near(at, 30) : List.of();
		if (!hush.isEmpty()) {
			ServerPlayer t = hush.get(random.nextInt(hush.size()));
			Vec3 spot = t.position();
			for (int k = 0; k < 30; k += 5) later(k, () -> telegraph(spot, 3, ParticleTypes.SCULK_SOUL));
			sound(at, SoundEvents.VILLAGER_WORK_LIBRARIAN, 2, 0.6f);
			later(30, () -> {
				sound(spot, SoundEvents.SCULK_SHRIEKER_SHRIEK, 1, 1.6f);
				for (ServerPlayer q : near(spot, 3)) {
					effect(q, MobEffects.WEAKNESS, 60, 1);
					effect(q, MobEffects.BLINDNESS, 60, 0);
				}
			});
		}
		// A bookshelf wall every 18 s, across the way to the nearest player.
		if (ticks % 360 == 200 && p != null) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			if (d.lengthSqr() > 9) {
				Vec3 mid = at.add(d.scale(0.5));
				Vec3 side = new Vec3(-d.z, 0, d.x).normalize();
				BlockPos g = groundAt(mid);
				for (int s = -3; s <= 3; s++) {
					for (int h = 0; h < 2; h++) tempBlock(BlockPos.containing(g.getX() + 0.5 + side.x * s, g.getY() + h, g.getZ() + 0.5 + side.z * s), Blocks.BOOKSHELF.defaultBlockState(), 160);
				}
				sound(mid, SoundEvents.WOOD_PLACE, 2, 0.7f);
			}
		}
		if (!rewritten && share(librarian) <= 0.5f) {
			rewritten = true;
			writeBooks();
			sound(at, SoundEvents.ENCHANTMENT_TABLE_USE, 2, 0.8f);
			say(at, 40, styled("The Grand Librarian ", RGB, true, false).copy().append(plain("rewrites his books!")));
		}
	}

	/** A spell from a book: a line of particles, then it lands (unless a block is in the way). */
	private void cast(int spell, Vec3 from, ServerPlayer at) {
		Vec3 to = at.getEyePosition();
		for (double f = 0; f <= 1; f += 0.05) {
			Vec3 q = from.add(to.subtract(from).scale(f));
			level.sendParticles(spell == 2 ? ParticleTypes.CRIT : ParticleTypes.ENCHANT, q.x, q.y, q.z, 1, 0, 0, 0, 0);
		}
		sound(from, SoundEvents.EVOKER_CAST_SPELL, 1, 1.4f);
		boolean blocked = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, librarian)).getType() != HitResult.Type.MISS;
		if (blocked) return;
		switch (spell) {
			case 0 -> effect(at, MobEffects.LEVITATION, 30, 0);
			case 1 -> effect(at, MobEffects.SLOWNESS, 80, 1);
			default -> at.hurtServer(level, level.damageSources().indirectMagic(librarian, librarian), 4);
		}
	}
}
