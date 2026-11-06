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
	private ServerBossEvent bar;
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

	/** One of its pieces hurt a player. */
	void hit(ServerPlayer victim, Entity attacker) {
	}

	final void step() {
		ticks++;
		tick();
		if (bar != null && ticks % 10 == 0) {
			LivingEntity b = body();
			if (b != null && b.isAlive()) {
				bar.setName(b.getDisplayName());
				bar.setProgress(Math.max(0f, Math.min(1f, b.getHealth() / b.getMaxHealth())));
				for (ServerPlayer p : level.players()) {
					if (p.distanceToSqr(b) <= 48 * 48) bar.addPlayer(p);
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

	/** To players within {@code range} of the spot. */
	void say(Vec3 at, double range, Component line) {
		for (ServerPlayer p : near(at, range)) p.sendSystemMessage(line);
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
