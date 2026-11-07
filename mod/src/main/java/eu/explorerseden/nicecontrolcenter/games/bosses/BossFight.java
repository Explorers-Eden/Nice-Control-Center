package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * One boss fight from Katters Structures (bosses designed by Katter, Explorer's Eden), rebuilt here without the
 * datapack's functions: it summons its pieces, ticks their abilities and ends when its boss is gone. Everything it
 * makes carries the caller's tags (the game's, so the game's cleanup takes it) and {@link #TAG}; the main bodies the
 * boss tag. Nothing drops and no XP.
 */
public abstract class BossFight {
	/** On everything a fight made. */
	public static final String TAG = "ncc_katter_boss";
	/** Pieces that are no enemy (displays, the dolphins, seats): not counted, never moved by the games. */
	public static final String ASSET = "ncc_katter_asset";

	final ServerLevel level;
	final List<String> tags;
	final String bossTag;
	final RandomSource random;
	final List<Entity> parts = new ArrayList<>();
	/** Cobwebs its attacks spun: gone again when the fight ends. */
	final Set<BlockPos> webs = new HashSet<>();
	/** Blocks its attacks put down for a while (powder snow, honey, fire, walls): when they go, and what was there. */
	private final java.util.Map<BlockPos, Object[]> temp = new java.util.HashMap<>();
	/** Things to do later (the second half of a warned attack): tick → what. */
	private final List<Object[]> later = new ArrayList<>();
	private ServerBossEvent bar;
	/** Who sees its bar: the game's players (null: anyone within 48 blocks). */
	java.util.function.Predicate<ServerPlayer> viewers;
	long ticks;
	boolean over;

	BossFight(ServerLevel level, List<String> tags, String bossTag, boolean showBar) {
		this.level = level;
		this.tags = tags;
		this.bossTag = bossTag;
		this.random = level.getRandom();
		if (showBar) bar = new ServerBossEvent(java.util.UUID.randomUUID(), Component.literal(name()), color(), BossEvent.BossBarOverlay.NOTCHED_10);
	}

	/** The boss's name (for its bar). */
	abstract String name();

	abstract BossEvent.BossBarColor color();

	/** Every tick while it's on. */
	abstract void tick();

	/** Still fighting (false: it's beaten or was taken away). */
	abstract boolean alive();

	/** The body the bar follows (null: none right now). */
	abstract LivingEntity body();

	/** One of its pieces was hurt (by anything). */
	void hurt(LivingEntity piece, net.minecraft.world.damagesource.DamageSource source) {
	}

	/** One of its pieces took {@code amount} damage (calls {@link #hurt} too). */
	void damaged(LivingEntity piece, net.minecraft.world.damagesource.DamageSource source, float amount) {
		hurt(piece, source);
	}

	/** One of its pieces hurt a player. */
	void hit(ServerPlayer victim, Entity attacker) {
	}

	final void step() {
		ticks++;
		tick();
		// Delayed parts of attacks.
		for (Object[] l : List.copyOf(later)) {
			if ((long) l[0] > ticks) continue;
			later.remove(l);
			((Runnable) l[1]).run();
		}
		// Temporary blocks whose time is up.
		if (!temp.isEmpty() && ticks % 5 == 0) {
			for (java.util.Map.Entry<BlockPos, Object[]> e : List.copyOf(temp.entrySet())) {
				if ((long) e.getValue()[0] > ticks) continue;
				untemp(e.getKey(), e.getValue());
				temp.remove(e.getKey());
			}
		}
		if (bar != null && ticks % 10 == 0) {
			LivingEntity b = body();
			if (b != null && b.isAlive()) {
				bar.setName(b.getDisplayName());
				bar.setProgress(Math.max(0f, Math.min(1f, b.getHealth() / b.getMaxHealth())));
				for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
					boolean sees = p.level() == level && (viewers != null ? viewers.test(p) : p.distanceToSqr(b) <= 48 * 48);
					if (sees) bar.addPlayer(p);
					else bar.removePlayer(p);
				}
			}
		}
	}

	/** Its pieces that aren't enemies go, its cobwebs too, and the bar. */
	final void finish() {
		if (over) return;
		over = true;
		if (bar != null) bar.removeAllPlayers();
		for (Entity e : parts) if (!e.isRemoved() && e.entityTags().contains(ASSET)) e.discard();
		for (BlockPos b : webs) if (level.getBlockState(b).is(Blocks.COBWEB)) level.removeBlock(b, false);
		for (java.util.Map.Entry<BlockPos, Object[]> e : temp.entrySet()) untemp(e.getKey(), e.getValue());
		temp.clear();
		later.clear();
		NiceControlCenter.LOGGER.info("Games: boss {} gone", name());
	}

	// ---------------------------------------------------------------- making things

	/**
	 * An entity (with its passengers) from summon data, like /summon would: the caller's tags on all of it, the boss
	 * tag too when {@code main}. Null when the data doesn't work.
	 */
	Entity spawn(String id, double x, double y, double z, String snbt, boolean main, boolean asset) {
		CompoundTag tag;
		try {
			tag = TagParser.parseCompoundFully(snbt == null || snbt.isBlank() ? "{}" : snbt);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			NiceControlCenter.LOGGER.warn("Games: boss {} piece {} has bad data: {}", name(), id, e.getMessage());
			return null;
		}
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
		Entity e = EntityType.loadEntityRecursive(type, tag, level, EntitySpawnReason.COMMAND, made -> {
			made.snapTo(x, y, z, made.getYRot(), made.getXRot());
			return made;
		});
		if (e == null) return null;
		List<Entity> all = new ArrayList<>();
		all.add(e);
		for (Entity p : e.getIndirectPassengers()) all.add(p);
		for (Entity p : all) {
			p.addTag(TAG);
			// Nice Mob Manager leaves them alone (no new size, speed or looks).
			p.addTag(eu.explorerseden.nicecontrolcenter.games.MobLines.NMM_EXCLUDE);
			for (String t : tags) p.addTag(t);
			if (main && bossTag != null) p.addTag(bossTag);
			if (asset) p.addTag(ASSET);
			if (p instanceof LivingEntity l) l.skipDropExperience();
			parts.add(p);
			KatterBosses.own(p, this);
		}
		if (!level.tryAddFreshEntityWithPassengers(e)) return null;
		return e;
	}

	/** Something short-lived that dies at once: its effect goes off where it dies (wind burst, cobwebs). */
	void burst(Vec3 at, String effect, int amplifier) {
		Set<BlockPos> before = new HashSet<>();
		BlockPos c = BlockPos.containing(at);
		for (BlockPos b : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) if (level.getBlockState(b).is(Blocks.COBWEB)) before.add(b.immutable());
		Entity e = spawn("minecraft:armor_stand", at.x, at.y, at.z, "{NoGravity:1b,Invulnerable:1b,Invisible:1b,Silent:1b,active_effects:[{id:\"" + effect + "\",amplifier:" + amplifier
				+ ",duration:-1,show_particles:0b}]}", false, true);
		if (e instanceof LivingEntity l) {
			eu.explorerseden.nicecontrolcenter.compat.Invulnerable.set(l, false);
			l.kill(level);
		}
		for (BlockPos b : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) if (level.getBlockState(b).is(Blocks.COBWEB) && !before.contains(b)) webs.add(b.immutable());
	}

	void windBurst(Vec3 at) {
		burst(at, "minecraft:wind_charged", 0);
	}

	/** An item (a potion, a trident) dropped onto the spot from 3.5 blocks up after {@code ticks}. */
	void dropFromAbove(Vec3 at, String item, int ticks) {
		spawn("minecraft:ominous_item_spawner", at.x, at.y + 3.5, at.z, "{NoGravity:1b,spawn_item_after_ticks:" + ticks + ",item:" + item + "}", false, true);
	}

	static String splash(String potion) {
		return "{id:\"minecraft:splash_potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"" + potion + "\"}}}";
	}

	void lightning(Vec3 at) {
		spawn("minecraft:lightning_bolt", at.x, at.y, at.z, "{}", false, true);
	}

	/** To the game's players (in a game), else to players within {@code range} of the spot. */
	void say(Vec3 at, double range, Component line) {
		if (viewers != null) {
			for (ServerPlayer p : level.players()) if (viewers.test(p)) p.sendSystemMessage(line);
			return;
		}
		for (ServerPlayer p : near(at, range)) p.sendSystemMessage(line);
	}

	// ---------------------------------------------------------------- attack helpers

	/** Runs {@code what} in {@code ticks} ticks (while the fight is on). */
	void later(int ticks, Runnable what) {
		later.add(new Object[] { this.ticks + ticks, what });
	}

	/**
	 * A block put down for {@code ticks} (only where there's air or something replaceable, like grass): taken away
	 * again after that, or when the fight ends.
	 */
	boolean tempBlock(BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int ticks) {
		BlockPos p = pos.immutable();
		net.minecraft.world.level.block.state.BlockState was = level.getBlockState(p);
		if (temp.containsKey(p)) {
			temp.get(p)[0] = this.ticks + ticks;
			level.setBlock(p, state, 3);
			return true;
		}
		if (!was.isAir() && !was.canBeReplaced()) return false;
		level.setBlock(p, state, 3);
		temp.put(p, new Object[] { this.ticks + ticks, was, state.getBlock() });
		return true;
	}

	private void untemp(BlockPos p, Object[] t) {
		// Only when it's still ours (a player or the fight may have changed it since).
		if (level.getBlockState(p).is((net.minecraft.world.level.block.Block) t[2]) || level.getBlockState(p).is(Blocks.FIRE))
			level.setBlock(p, (net.minecraft.world.level.block.state.BlockState) t[1], net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS);
	}

	/** The ground under a spot (the first solid block at or below it, up to 12 down), one above it. */
	BlockPos groundAt(Vec3 at) {
		BlockPos b = BlockPos.containing(at);
		for (int i = 0; i < 12 && level.getBlockState(b.below()).getCollisionShape(level, b.below()).isEmpty(); i++) b = b.below();
		return b;
	}

	/** A ring of particles on the ground: where something is about to land. */
	void telegraph(Vec3 at, double radius, net.minecraft.core.particles.ParticleOptions particle) {
		int n = Math.max(8, (int) (radius * 8));
		for (int i = 0; i < n; i++) {
			double a = Math.PI * 2 * i / n;
			level.sendParticles(particle, at.x + Math.cos(a) * radius, at.y + 0.1, at.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
		}
	}

	/** Players within {@code radius} take {@code damage} from the boss and are thrown away from the middle (and up). */
	void shockwave(LivingEntity from, Vec3 at, double radius, float damage, double strength, double up) {
		for (int r = 1; r <= (int) radius; r++) telegraph(at, r, net.minecraft.core.particles.ParticleTypes.CLOUD);
		for (ServerPlayer p : near(at, radius)) {
			if (damage > 0) p.hurtServer(level, level.damageSources().mobAttack(from), damage);
			Vec3 away = p.position().subtract(at).multiply(1, 0, 1);
			away = away.lengthSqr() < 0.01 ? new Vec3(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize() : away.normalize();
			push(p, new Vec3(away.x * strength, up, away.z * strength));
		}
	}

	/** An explosion that hurts but breaks nothing. */
	void blast(Entity from, Vec3 at, float power) {
		level.explode(from, at.x, at.y, at.z, power, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
	}

	/** Health share of a body (0–1). */
	static float share(LivingEntity e) {
		return e == null ? 0 : e.getHealth() / e.getMaxHealth();
	}

	void sound(Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, pitch);
	}

	/** Players (not spectators or creative) within range. */
	List<ServerPlayer> near(Vec3 at, double range) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
			if (p.position().distanceToSqr(at) <= range * range) out.add(p);
		}
		return out;
	}

	ServerPlayer nearest(Vec3 at, double range) {
		ServerPlayer best = null;
		for (ServerPlayer p : near(at, range)) if (best == null || p.position().distanceToSqr(at) < best.position().distanceToSqr(at)) best = p;
		return best;
	}

	static void effect(LivingEntity e, net.minecraft.core.Holder<MobEffect> effect, int ticks, int amplifier) {
		e.addEffect(new MobEffectInstance(effect, ticks, amplifier, true, false));
	}

	/** Players' arrows and the like close to the body fall straight down (the boss's "defense"). */
	void defend(Entity body, double range) {
		for (Projectile pr : level.getEntitiesOfClass(Projectile.class, body.getBoundingBox().inflate(range), x -> x.getOwner() instanceof ServerPlayer && x.getDeltaMovement().lengthSqr() > 0.04)) {
			pr.setDeltaMovement(0, -1, 0);
		}
	}

	/** A push for a player (sent to them, or they wouldn't feel it). */
	static void push(ServerPlayer p, Vec3 motion) {
		p.setDeltaMovement(motion);
		p.connection.send(new ClientboundSetEntityMotionPacket(p));
	}

	static boolean dead(Entity e) {
		return e == null || e.isRemoved() || !e.isAlive();
	}

	/** Killed (not just taken away by a cleanup or an unloading chunk). */
	static boolean killed(Entity e) {
		return e != null && (e.getRemovalReason() == Entity.RemovalReason.KILLED || e instanceof LivingEntity l && l.isDeadOrDying());
	}

	static Component styled(String text, int rgb, boolean bold, boolean italic) {
		return Component.literal(text).withStyle(s -> s.withColor(rgb).withBold(bold).withItalic(italic));
	}

	static Component plain(String text) {
		return Component.literal(text).withStyle(ChatFormatting.WHITE);
	}

	AABB around(Entity e, double r) {
		return e.getBoundingBox().inflate(r);
	}
}
