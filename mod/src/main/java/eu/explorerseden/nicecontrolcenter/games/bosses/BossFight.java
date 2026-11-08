package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
	/** Its fireballs in flight and where they were last (the fire they light is cleaned up). */
	private final java.util.Map<Entity, Vec3> fireballs = new java.util.HashMap<>();

	/** A fireball of the fight: whatever it sets alight is put out again. */
	Entity fireball(Entity f) {
		if (f != null) fireballs.put(f, f.position());
		return f;
	}

	/** Things to do later (the second half of a warned attack): tick → what. */
	private final List<Object[]> later = new ArrayList<>();
	private ServerBossEvent bar;
	/** Set by the registry just before a fight is made: its box and health factor (read in the constructor). */
	static AABB pendingBounds;
	static float pendingScale = 1;
	/** The arena or dungeon box: its bodies stay inside (flying ones would leave small arenas). Null: anywhere. */
	final AABB bounds;
	/** Health factor (a wave arena's "% more health per wave"): every main body gets this much more health. */
	final float healthScale;
	/** Its main bodies (the boss itself, its shards or forms). */
	final List<LivingEntity> mains = new ArrayList<>();
	/** Who sees its bar: the game's players (null: anyone within 48 blocks). */
	java.util.function.Predicate<ServerPlayer> viewers;
	long ticks;
	boolean over;

	BossFight(ServerLevel level, List<String> tags, String bossTag, boolean showBar) {
		this.level = level;
		this.tags = tags;
		this.bossTag = bossTag;
		this.random = level.getRandom();
		this.bounds = pendingBounds;
		this.healthScale = pendingScale;
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

	/**
	 * Before one of its pieces takes damage: false cancels it. A fight can apply its own amount instead with
	 * {@link #hurtAs} (a fire shield taking a quarter, a returned fireball capped).
	 */
	boolean allowDamage(LivingEntity piece, net.minecraft.world.damagesource.DamageSource source, float amount) {
		return true;
	}

	/** Set while the fight applies its own damage (so allowDamage lets it through). */
	boolean applying;

	/** Damage the fight decides itself (after cancelling the original). */
	void hurtAs(LivingEntity piece, float amount, Entity by) {
		if (amount <= 0 || dead(piece)) return;
		applying = true;
		try {
			piece.hurtServer(level, by instanceof ServerPlayer p ? level.damageSources().playerAttack(p) : level.damageSources().generic(), amount);
		} finally {
			applying = false;
		}
	}

	/** One of its pieces took {@code amount} damage (calls {@link #hurt} too). */
	void damaged(LivingEntity piece, net.minecraft.world.damagesource.DamageSource source, float amount) {
		hurt(piece, source);
	}

	/** When the fight is over (beaten or taken away): give back what it still holds. */
	void ended() {
	}

	/** One of its pieces hurt a player. */
	void hit(ServerPlayer victim, Entity attacker) {
	}

	final void step() {
		ticks++;
		checkTargets();
		tick();
		confine();
		// Delayed parts of attacks.
		for (Object[] l : List.copyOf(later)) {
			if ((long) l[0] > ticks) continue;
			later.remove(l);
			((Runnable) l[1]).run();
		}
		// Fireballs that landed: the fire they lit goes out again soon (it isn't the arena's).
		if (!fireballs.isEmpty()) {
			for (java.util.Map.Entry<Entity, Vec3> e : List.copyOf(fireballs.entrySet())) {
				Entity f = e.getKey();
				if (!f.isRemoved()) {
					fireballs.put(f, f.position());
					continue;
				}
				fireballs.remove(f);
				BlockPos c = BlockPos.containing(e.getValue());
				for (BlockPos b : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) {
					if (!level.getBlockState(b).is(Blocks.FIRE) || temp.containsKey(b)) continue;
					temp.put(b.immutable(), new Object[] { ticks + 40, Blocks.AIR.defaultBlockState(), Blocks.FIRE });
				}
			}
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

	/**
	 * Keeps its bodies inside the box: flying ones every tick (a phantom or ghast would circle out of a small arena),
	 * walkers now and then. Pushed back in and turned round, never teleported to the far side.
	 */
	private static final java.util.Set<String> FLYERS = java.util.Set.of("phantom", "ghast", "happy_ghast", "bee", "blaze", "vex", "allay", "breeze", "parrot", "bat");

	private void confine() {
		if (bounds == null) return;
		for (Entity e : parts) {
			if (!(e instanceof LivingEntity l) || dead(l) || l.isPassenger() || l.entityTags().contains(ASSET)) continue;
			boolean flying = l.isNoGravity() || FLYERS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(l.getType()).getPath());
			if (!flying && ticks % 10 != 0) continue;
			double w = Math.min(1.5, l.getBbWidth() / 2 + 0.3), h = l.getBbHeight() + 0.3;
			double x0 = bounds.minX + w, x1 = bounds.maxX - w, z0 = bounds.minZ + w, z1 = bounds.maxZ - w;
			double y0 = bounds.minY, y1 = bounds.maxY - h;
			if (x0 > x1 || z0 > z1 || y0 > y1) continue;
			double x = l.getX(), y = l.getY(), z = l.getZ();
			double cx = Math.max(x0, Math.min(x1, x)), cy = Math.max(y0, Math.min(y1, y)), cz = Math.max(z0, Math.min(z1, z));
			if (cx == x && cy == y && cz == z) continue;
			Vec3 v = l.getDeltaMovement();
			l.teleportTo(cx, cy, cz);
			// Turned round where it hit the edge.
			l.setDeltaMovement(cx != x ? -v.x * 0.5 : v.x, cy != y ? Math.abs(v.y) * 0.5 * Math.signum(cy - y) : v.y, cz != z ? -v.z * 0.5 : v.z);
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
		try {
			ended();
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Games: boss {} didn't wrap up: {}", name(), e.toString());
		}
		if (!rallied.isEmpty()) {
			int n = rallied.size();
			calm();
			NiceControlCenter.LOGGER.info("Games: boss {} let go of {} villagers and golems", name(), n);
		}
		NiceControlCenter.LOGGER.info("Games: boss {} gone", name());
	}

	// ---------------------------------------------------------------- the village on its side

	/** On residents a fight rallied (the arena's own villagers and golems). */
	public static final String RALLIED = "ncc_rallied";
	private static final Identifier TAMED = Identifier.fromNamespaceAndPath("nicecontrolcenter", "rallied");
	private static final java.util.Set<String> VILLAGE = java.util.Set.of("villager", "iron_golem", "copper_golem", "snow_golem");
	/** Residents fighting for it: mob → whether the fight turned it (false: it fought players already, a wave mob). */
	final java.util.Map<net.minecraft.world.entity.Mob, Boolean> rallied = new java.util.LinkedHashMap<>();
	/** Residents knocked out (they never die: the arena keeps its villagers). */
	final java.util.Set<net.minecraft.world.entity.Mob> knocked = new java.util.HashSet<>();

	/**
	 * The arena's villagers and golems fight for the boss: inside its box (without one, within {@code range}, and
	 * not iron golems a player built). They're knocked out instead of killed, and calm down again when it's over.
	 * Returns how many joined now.
	 */
	int rally(Vec3 at, double range) {
		AABB box = bounds != null ? bounds : new AABB(at, at).inflate(range);
		int n = 0;
		for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box, x -> x.isAlive()
				&& VILLAGE.contains(BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).getPath()) && !KatterBosses.owned(x) && !x.entityTags().contains(TAG))) {
			if (bounds == null && m instanceof net.minecraft.world.entity.animal.golem.IronGolem g && g.isPlayerCreated()) continue;
			boolean turned = !eu.explorerseden.nicecontrolcenter.games.Aggro.made(m);
			if (turned) eu.explorerseden.nicecontrolcenter.games.Aggro.make(m, 3f);
			m.addTag(RALLIED);
			// Iron golems hit for half to one and a half times their attack: vanilla's 15 two-shots anyone, so 40 %.
			var dmg = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
			if (dmg != null && dmg.getValue() > 8 && !dmg.hasModifier(TAMED)) dmg.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(TAMED, -0.6,
					net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			rallied.put(m, turned);
			KatterBosses.own(m, this);
			n++;
		}
		List<ServerPlayer> ps = players(at);
		for (net.minecraft.world.entity.Mob m : rallied.keySet()) {
			if (knocked.contains(m) || ps.isEmpty()) continue;
			ServerPlayer p = ps.get(random.nextInt(ps.size()));
			m.setTarget(p);
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, m.getX(), m.getY() + m.getBbHeight() + 0.3, m.getZ(), 2, 0.3, 0.2, 0.3, 0);
		}
		return n;
	}

	/** Its rallied residents still standing. */
	List<net.minecraft.world.entity.Mob> rallied() {
		List<net.minecraft.world.entity.Mob> out = new ArrayList<>();
		for (net.minecraft.world.entity.Mob m : rallied.keySet()) if (!dead(m) && !knocked.contains(m)) out.add(m);
		return out;
	}

	void buffRallied(net.minecraft.core.Holder<MobEffect> what, int ticks, int amplifier) {
		for (net.minecraft.world.entity.Mob m : rallied()) effect(m, what, ticks, amplifier);
	}

	/** Damage to a rallied resident: a blow that would kill knocks it out instead; knocked out, it takes none. */
	final boolean ralliedDamage(net.minecraft.world.entity.Mob m, net.minecraft.world.damagesource.DamageSource source, float amount) {
		if (knocked.contains(m)) return false;
		if (amount < m.getHealth()) return true;
		knocked.add(m);
		m.setHealth(1);
		eu.explorerseden.nicecontrolcenter.games.Aggro.unmake(m);
		effect(m, net.minecraft.world.effect.MobEffects.SLOWNESS, -1, 4);
		effect(m, net.minecraft.world.effect.MobEffects.WEAKNESS, -1, 4);
		level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.5, m.getZ(), 12, 0.4, 0.4, 0.4, 0.02);
		sound(m.position(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1, 0.8f);
		return false;
	}

	/** Every resident calms down and is healed (and a wave mob it took over fights on as before). */
	private void calm() {
		for (java.util.Map.Entry<net.minecraft.world.entity.Mob, Boolean> e : rallied.entrySet()) {
			net.minecraft.world.entity.Mob m = e.getKey();
			if (m.isRemoved()) continue;
			m.removeTag(RALLIED);
			var dmg = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
			if (dmg != null) dmg.removeModifier(TAMED);
			boolean wasKnocked = knocked.contains(m);
			if (wasKnocked) {
				m.removeEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
				m.removeEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
			}
			if (e.getValue() || wasKnocked) {
				eu.explorerseden.nicecontrolcenter.games.Aggro.unmake(m);
				m.setHealth(m.getMaxHealth());
			}
		}
		rallied.clear();
		knocked.clear();
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
		return adopt(e, main, asset) ? e : null;
	}

	/** An entity made elsewhere (a shulker bullet built in code): tagged like the fight's own and added to the world. */
	boolean adopt(Entity e, boolean main, boolean asset) {
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
			if (p instanceof LivingEntity l) {
				l.skipDropExperience();
				if (main) {
					mains.add(l);
					if (healthScale != 1) {
						var max = l.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
						if (max != null) {
							max.setBaseValue(max.getBaseValue() * healthScale);
							l.setHealth(l.getMaxHealth());
						}
					}
				}
			}
			parts.add(p);
			KatterBosses.own(p, this);
		}
		return level.tryAddFreshEntityWithPassengers(e);
	}

	// ---------------------------------------------------------------- breakable things

	/** Something in the arena to break (a heart, a totem, a lightning rod): a block to see, a hidden slime to hit. */
	static final class Target {
		Entity display;
		LivingEntity hitbox;
		/** Hits left for fights that count hits instead of health (fire shields). */
		int hits;

		boolean broken() {
			return dead(hitbox);
		}

		/** Moves it (both parts). */
		void moveTo(Vec3 at) {
			if (display != null && !display.isRemoved()) display.teleportTo(at.x, at.y, at.z);
			if (hitbox != null && !hitbox.isRemoved()) hitbox.teleportTo(at.x, at.y, at.z);
		}
	}

	final List<Target> targets = new ArrayList<>();

	/**
	 * A breakable thing at the spot: {@code block} as a display ("minecraft:creaking_heart"), {@code health} on an
	 * invisible slime the size of a block. It isn't a wave mob (doesn't hold a wave open); broken, the display goes.
	 */
	Target target(String block, Vec3 at, float health, String name, boolean glow) {
		Target t = new Target();
		// "item:minecraft:totem_of_undying" shows an item (floating, bigger), anything else a block.
		if (block.startsWith("item:")) {
			t.display = spawn("minecraft:item_display", at.x, at.y + 0.5, at.z, "{" + (glow ? "Glowing:1b," : "") + "billboard:\"vertical\",item:{id:\"" + block.substring(5) + "\",count:1},"
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[1.6f,1.6f,1.6f]}}", false, true);
		} else {
			t.display = spawn("minecraft:block_display", at.x, at.y, at.z, "{" + (glow ? "Glowing:1b," : "") + "block_state:" + blockState(block, "") + ","
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-0.5f,0f,-0.5f],scale:[1f,1f,1f]}}", false, true);
		}
		t.hitbox = (LivingEntity) spawn("minecraft:slime", at.x, at.y, at.z, "{Size:0,NoAI:1b,Silent:1b,PersistenceRequired:1b,DeathLootTable:\"minecraft:empty\","
				+ "Health:" + health + "f,CustomName:\"" + name + "\",active_effects:[{id:\"minecraft:invisibility\",amplifier:0,duration:-1,show_particles:0b}],"
				+ "attributes:[{id:\"minecraft:max_health\",base:" + health + "},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:1}]}", false, true);
		if (t.hitbox != null) t.hitbox.setNoGravity(true);
		targets.add(t);
		return t;
	}

	/** Broken targets: the display goes with a burst of the block's particles. */
	private void checkTargets() {
		for (Target t : List.copyOf(targets)) {
			if (!t.broken()) continue;
			if (t.display != null && !t.display.isRemoved()) {
				Vec3 at = t.display.position();
				level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
				sound(at, net.minecraft.sounds.SoundEvents.SHIELD_BREAK.value(), 1.5f, 0.8f);
				t.display.discard();
			}
			targets.remove(t);
		}
	}

	/** Gear a fight's mechanic needs (snowballs against fire shields): a game item, gone again after the game. */
	void give(ServerPlayer p, net.minecraft.world.item.ItemStack st) {
		eu.explorerseden.nicecontrolcenter.claims.GameClaims.giveKitItem(p, st, "boss");
	}

	/** The fight's players (its viewers), else players within 40 blocks of the spot. */
	List<ServerPlayer> players(Vec3 at) {
		if (viewers == null) return near(at, 40);
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) if (viewers.test(p) && p.isAlive() && !p.isSpectator()) out.add(p);
		return out;
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
		return tempBlock(pos, state, ticks, false);
	}

	/**
	 * The same; {@code quiet}: no shape updates, so a block that needs special ground (bamboo) stays put instead of
	 * popping off as an item.
	 */
	boolean tempBlock(BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int ticks, boolean quiet) {
		int flags = quiet ? net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE : 3;
		BlockPos p = pos.immutable();
		net.minecraft.world.level.block.state.BlockState was = level.getBlockState(p);
		if (temp.containsKey(p)) {
			temp.get(p)[0] = this.ticks + ticks;
			level.setBlock(p, state, flags);
			return true;
		}
		if (!was.isAir() && !was.canBeReplaced()) return false;
		level.setBlock(p, state, flags);
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

	/**
	 * A boss villager's data: the profession's look, no trades (no trading window, it only shakes its head), no AI
	 * of its own (the fight moves it, so it never panics, sleeps or wanders off to work).
	 */
	static String villager(String name, String rgb, String profession, int health, double scale) {
		return "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,NoAI:1b,Xp:1,Offers:{Recipes:[]},VillagerData:{profession:\"minecraft:" + profession + "\",type:\"minecraft:plains\",level:5},"
				+ "Health:" + health + "f,CustomName:{text:\"" + name + "\",color:\"" + rgb + "\",bold:true},attributes:[{id:\"minecraft:max_health\",base:" + health + "},{id:\"minecraft:scale\",base:" + scale + "},"
				+ "{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	}

	/** 26.3 writes block states as {id, properties}, 26.1 and 26.2 as {Name, Properties}: tried once. */
	private static final boolean NEW_STATES = net.minecraft.world.level.block.state.BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, parseOr("{id:\"minecraft:stone\"}")).result().isPresent();

	private static CompoundTag parseOr(String snbt) {
		try {
			return TagParser.parseCompoundFully(snbt);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			return new CompoundTag();
		}
	}

	/** A block state for summon data in this version's shape; {@code props}: "facing:\"up\"" or empty. */
	static String blockState(String block, String props) {
		String p = props == null || props.isEmpty() ? "" : (NEW_STATES ? ",properties:{" : ",Properties:{") + props + "}";
		return (NEW_STATES ? "{id:\"" : "{Name:\"") + block + "\"" + p + "}";
	}

	/** Turns a body to look at the spot. */
	static void face(LivingEntity body, Vec3 to) {
		Vec3 d = to.subtract(body.position());
		float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
		body.setYRot(yaw);
		body.setYHeadRot(yaw);
		body.setYBodyRot(yaw);
	}

	/**
	 * A body without AI of its own (a boss villager: no panic, no sleep, no trading) walks at the player and swings
	 * when it's in reach (once a second). Returns the squared distance to them.
	 */
	double stride(LivingEntity body, ServerPlayer p, double speed, double reach, float damage) {
		Vec3 d = p.position().subtract(body.position()).multiply(1, 0, 1);
		face(body, p.position());
		double reachAt = reach + body.getBbWidth() / 2;
		if (d.lengthSqr() > reachAt * reachAt * 0.8) body.setDeltaMovement(d.normalize().scale(speed).add(0, body.getDeltaMovement().y, 0));
		if (ticks % 20 == 0 && d.lengthSqr() <= reachAt * reachAt && damage > 0) {
			p.hurtServer(level, level.damageSources().mobAttack(body), damage);
			push(p, (d.lengthSqr() < 0.01 ? body.getLookAngle() : d.normalize()).scale(0.6).add(0, 0.3, 0));
		}
		return d.lengthSqr();
	}

	/**
	 * A block falling where a ring warned ({@code warn} ticks): players within {@code radius} take {@code damage}
	 * and {@code then} (null: nothing more); the block is only a display, so it breaks nothing.
	 */
	void dropBlock(Vec3 target, String block, int warn, LivingEntity from, double radius, float damage, java.util.function.Consumer<ServerPlayer> then) {
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(target));
		net.minecraft.world.level.block.state.BlockState st = BuiltInRegistries.BLOCK.getValue(Identifier.parse(block)).defaultBlockState();
		for (int t = 0; t < warn; t += 5) later(t, () -> telegraph(spot, radius, new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, st)));
		Entity d = spawn("minecraft:block_display", spot.x, spot.y + 14, spot.z, "{teleport_duration:14,block_state:" + blockState(block, "") + ",transformation:{left_rotation:[0f,0f,0f,1f],"
				+ "right_rotation:[0f,0f,0f,1f],translation:[-0.5f,0f,-0.5f],scale:[1f,1f,1f]}}", false, true);
		later(warn, () -> {
			if (d != null) d.teleportTo(spot.x, spot.y, spot.z);
		});
		later(warn + 14, () -> {
			if (d != null) d.discard();
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, st), spot.x, spot.y + 0.5, spot.z, 30, 0.6, 0.3, 0.6, 0.1);
			sound(spot, st.getSoundType().getBreakSound(), 2, 0.7f);
			for (ServerPlayer p : near(spot, radius)) {
				if (damage > 0) p.hurtServer(level, from == null ? level.damageSources().generic() : level.damageSources().mobAttack(from), damage);
				if (then != null) then.accept(p);
			}
		});
	}

	/** A block the fight's helpers left (a snow golem's trail): taken away again after {@code ticks}. */
	void litter(BlockPos pos, int ticks) {
		BlockPos p = pos.immutable();
		if (temp.containsKey(p) || level.getBlockState(p).isAir()) return;
		temp.put(p, new Object[] { this.ticks + ticks, Blocks.AIR.defaultBlockState(), level.getBlockState(p).getBlock() });
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
