package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.compat.CubeMobs;

/**
 * Sulfur Billiards (quick round "cubes", Minecraft 26.3): sulfur cubes holding a block roll around the play box. A cube
 * holding a block takes no damage from punches, only knockback (vanilla), so it plays like a ball; the block picks its
 * physics (planks bounce, ice slides, soul sand is heavy, TNT explodes). Whoever punched a cube last when it drops into
 * a hole (marked in game) scores the hole's points times the cube's; bad cubes cost. Players punching each other only
 * shove.
 */
public final class Cubes {
	/** Every cube of a round has this tag (and the round's own). */
	static final String CUBE = "ncc_cube";
	/** The cube's points: ncc_cubepts_<n>. */
	private static final String POINTS = "ncc_cubepts_";
	/** A TNT cube: primed by the first punch. */
	private static final String TNT = "ncc_cube_tnt";
	private static final String TYPE = "minecraft:sulfur_cube";
	/** A cube nobody touched for this long rolls back out somewhere else. */
	private static final long IDLE_MS = 20_000;
	private static final Pattern WEIGHT = Pattern.compile("\\s+w(\\d+)\\s*$");

	/** A cube: the block it holds, what it's worth, how often it comes. */
	record Kind(Item item, int points, int weight) {
	}

	private Cubes() {
	}

	/** "minecraft:dirt=1 w4" lines (points 1 and weight 1 when left out; 0 points or weight is skipped). */
	static List<Kind> kinds(Arena a) {
		List<Kind> out = new ArrayList<>();
		for (String raw : a.cubeBlocks) {
			String line = raw.strip();
			int weight = 1;
			Matcher w = WEIGHT.matcher(line);
			if (w.find()) {
				weight = Integer.parseInt(w.group(1));
				line = line.substring(0, w.start()).strip();
			}
			int points = 1;
			int eq = line.lastIndexOf('=');
			if (eq >= 0) {
				try {
					points = Integer.parseInt(line.substring(eq + 1).strip());
				} catch (NumberFormatException e) {
					continue;
				}
				line = line.substring(0, eq).strip();
			}
			Identifier id = Identifier.tryParse(line.contains(":") ? line : "minecraft:" + line);
			Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || item == Items.AIR || points == 0 || weight <= 0) continue;
			out.add(new Kind(item, points, weight));
		}
		return out;
	}

	/** Sulfur cubes are there (Minecraft 26.3 on). */
	static boolean available() {
		return BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(TYPE));
	}

	/** Why it can't run yet (null when it can). */
	static String check(Arena a) {
		if (!available()) return "Sulfur Billiards needs Minecraft 26.3 (there are no sulfur cubes before it).";
		if (a.cubeHoles.isEmpty()) return a.name + " has no holes yet (/game edit " + a.name + " hole add <points>, standing in the pit).";
		if (kinds(a).isEmpty()) return a.name + " has no cube blocks (panel).";
		return null;
	}

	/** The name tag's colour: white 1, green 2, gold 3 and up, red for bad cubes. */
	static ChatFormatting color(int points) {
		return points < 0 ? ChatFormatting.RED : points >= 3 ? ChatFormatting.GOLD : points == 2 ? ChatFormatting.GREEN : ChatFormatting.WHITE;
	}

	private static String label(int points) {
		return (points > 0 ? "+" : "−") + Math.abs(points);
	}

	private static int points(Entity e) {
		for (String t : e.entityTags()) {
			if (!t.startsWith(POINTS)) continue;
			try {
				return Integer.parseInt(t.substring(POINTS.length()));
			} catch (NumberFormatException ex) {
				return 1;
			}
		}
		return 1;
	}

	static boolean isCube(Entity e) {
		return e.entityTags().contains(CUBE);
	}

	// ---------------------------------------------------------------- events

	public static void register() {
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer p) || level.isClientSide()) return InteractionResult.PASS;
			QuickRuntime.Round r = QuickRuntime.roundOf(p);
			if (r == null || !r.arena.type.equals("cubes")) return InteractionResult.PASS;
			if (isCube(entity) && entity.entityTags().contains(r.tag())) {
				r.cubeHitter.put(entity.getUUID(), p.getUUID());
				r.cubeTouched.put(entity.getUUID(), System.currentTimeMillis());
				if (entity.entityTags().contains(TNT)) CubeMobs.prime(entity);
				// Vanilla's punch sends a cube flying far (a bouncy one 40 blocks): scaled once the hit is done.
				double power = r.arena.cubePower / 100.0;
				if (power != 1) p.level().getServer().execute(() -> {
					if (!entity.isAlive()) return;
					entity.setDeltaMovement(entity.getDeltaMovement().multiply(power, Math.min(1, power), power));
					entity.needsSync = true;
				});
				return InteractionResult.PASS;
			}
			// Players of the same round: a shove instead of a hit (or nothing when shoving is off).
			if (entity instanceof ServerPlayer other && r.in(other.getUUID())) {
				if (r.arena.cubeShove) {
					// Away from the one punching, a little up (sent to them, or they wouldn't feel it).
					net.minecraft.world.phys.Vec3 away = new net.minecraft.world.phys.Vec3(other.getX() - p.getX(), 0, other.getZ() - p.getZ());
					away = away.lengthSqr() < 1.0E-4 ? p.getLookAngle().multiply(1, 0, 1).normalize() : away.normalize();
					other.setDeltaMovement(other.getDeltaMovement().add(away.x * 0.7, 0.3, away.z * 0.7));
					other.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(other));
					level.playSound(null, other.getX(), other.getY(), other.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.8f, 1.1f);
				}
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
		// Cubes can't be fed, sheared or scooped up.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> isCube(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
		// A TNT cube going off knocks players about but doesn't hurt them.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
			if (!(victim instanceof ServerPlayer p) || !source.is(DamageTypeTags.IS_EXPLOSION)) return true;
			QuickRuntime.Round r = QuickRuntime.roundOf(p);
			return r == null || !r.arena.type.equals("cubes");
		});
	}

	// ---------------------------------------------------------------- the round

	static void tick(MinecraftServer server, ServerLevel level, QuickRuntime.Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		long now = System.currentTimeMillis();
		AABB box = QuickRuntime.box(a);
		List<Mob> cubes = level.getEntitiesOfClass(Mob.class, box.inflate(16), e -> e.isAlive() && isCube(e) && e.entityTags().contains(r.tag()));
		int live = 0;
		for (Mob c : cubes) {
			// Lost its block (went off, or something took it), rolled out of the box, or left alone too long: back out elsewhere.
			if (c.getItemBySlot(EquipmentSlot.BODY).isEmpty() || !box.inflate(1).contains(c.position())
					|| now - r.cubeTouched.getOrDefault(c.getUUID(), now) > IDLE_MS) {
				gone(level, r, c);
				continue;
			}
			Games.Hole h = holeAt(a, c);
			if (h != null) {
				sink(server, level, r, c, h);
				continue;
			}
			live++;
			if (c.entityTags().contains(TNT) && CubeMobs.primed(c) && server.getTickCount() % 5 == 0) {
				boolean on = server.getTickCount() % 10 == 0;
				c.setCustomName(Words.lit("⚠ " + label(points(c))).withStyle(on ? ChatFormatting.RED : ChatFormatting.YELLOW, ChatFormatting.BOLD));
			}
		}
		// Forget cubes that are gone.
		java.util.Set<UUID> here = new java.util.HashSet<>();
		for (Mob c : cubes) if (c.isAlive()) here.add(c.getUUID());
		r.cubeHitter.keySet().retainAll(here);
		r.cubeTouched.keySet().retainAll(here);
		if (live >= a.maxAlive || now < r.nextCube) return;
		r.nextCube = now + 600;
		spawn(level, r);
	}

	/** The hole a cube dropped into: inside its square, down in the pit (below where the player stood + half a block). */
	private static Games.Hole holeAt(Arena a, Mob c) {
		String dim = c.level().dimension().identifier().toString();
		for (Games.Hole h : a.cubeHoles) {
			if (!h.pos.dim.equals(dim)) continue;
			double bx = Math.floor(h.pos.x), by = Math.floor(h.pos.y), bz = Math.floor(h.pos.z);
			if (c.getX() < bx - h.radius || c.getX() > bx + h.radius + 1 || c.getZ() < bz - h.radius || c.getZ() > bz + h.radius + 1) continue;
			if (c.getY() < by + 0.5 && c.getY() > by - 2) return h;
		}
		return null;
	}

	private static void sink(MinecraftServer server, ServerLevel level, QuickRuntime.Round r, Mob c, Games.Hole h) {
		UUID by = r.cubeHitter.get(c.getUUID());
		ServerPlayer p = by == null ? null : server.getPlayerList().getPlayer(by);
		int worth = points(c) * h.points;
		double x = c.getX(), y = c.getY() + 0.3, z = c.getZ();
		c.discard();
		r.nextCube = Math.max(r.nextCube, System.currentTimeMillis() + 800);
		if (p == null || !r.in(by)) {
			// Rolled in on its own (or its player is gone): no points.
			level.sendParticles(ParticleTypes.POOF, x, y, z, 8, 0.2, 0.2, 0.2, 0.02);
			level.playSound(null, x, y, z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.8f, 1f);
			return;
		}
		QuickRuntime.add(r, p, worth, "hole");
		if (worth > 0) {
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 14, 0.4, 0.3, 0.4, 0.1);
			level.sendParticles(ParticleTypes.FIREWORK, x, y + 0.4, z, 10, 0.2, 0.2, 0.2, 0.08);
			level.playSound(null, x, y, z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.6f);
			QuickRuntime.ding(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.0f + Math.min(1, worth * 0.1f));
		} else {
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, x, y, z, 5, 0.3, 0.2, 0.3, 0);
			QuickRuntime.ding(p, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
		}
	}

	private static void gone(ServerLevel level, QuickRuntime.Round r, Mob c) {
		level.sendParticles(ParticleTypes.POOF, c.getX(), c.getY() + 0.3, c.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
		r.cubeHitter.remove(c.getUUID());
		r.cubeTouched.remove(c.getUUID());
		c.discard();
	}

	/** A new cube at one of the cube spawns (or the player spawn), holding a block picked by weight. */
	private static void spawn(ServerLevel level, QuickRuntime.Round r) {
		Arena a = r.arena;
		List<Kind> kinds = kinds(a);
		if (kinds.isEmpty()) return;
		Random random = QuickRuntime.random();
		int total = 0;
		for (Kind k : kinds) total += k.weight();
		int roll = random.nextInt(total);
		Kind kind = kinds.get(0);
		for (Kind k : kinds) {
			roll -= k.weight();
			if (roll < 0) {
				kind = k;
				break;
			}
		}
		String dim = level.dimension().identifier().toString();
		List<Games.Pos> spots = a.mobSpawns.stream().filter(s -> dim.equals(s.dim)).toList();
		Games.Pos at = spots.isEmpty() ? a.spawn : spots.get(random.nextInt(spots.size()));
		if (at == null) return;
		Mob c;
		try {
			CompoundTag tag = TagParser.parseCompoundFully("{Size:" + (a.cubeSize - 1) + ",PersistenceRequired:1b}");
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(TYPE));
			if (!(EntityType.loadEntityRecursive(type, tag, level, EntitySpawnReason.COMMAND, EntityProcessor.NOP) instanceof Mob m)) return;
			c = m;
		} catch (Exception e) {
			NiceControlCenter.LOGGER.warn("Games: a sulfur cube could not be made: {}", e.toString());
			return;
		}
		double x = at.x + (random.nextDouble() - 0.5) * 0.6, z = at.z + (random.nextDouble() - 0.5) * 0.6;
		c.snapTo(x, at.y + 0.2, z, random.nextFloat() * 360, 0);
		c.setDropChance(EquipmentSlot.BODY, 0);
		c.addTag(CUBE);
		c.addTag(r.tag());
		c.addTag(MobLines.NMM_EXCLUDE);
		c.addTag(POINTS + kind.points());
		if (kind.item() == Items.TNT) c.addTag(TNT);
		c.setCustomName(Words.lit(label(kind.points())).withStyle(color(kind.points()), ChatFormatting.BOLD));
		c.setCustomNameVisible(true);
		level.addFreshEntity(c);
		// The block after it's in the world: the cube notices the change and takes the block's physics (and, holding a
		// block, it sits still until it's hit). A block it was made with would leave it rolling like a plain cube.
		c.setItemSlot(EquipmentSlot.BODY, new ItemStack(kind.item()));
		r.cubeTouched.put(c.getUUID(), System.currentTimeMillis());
		level.sendParticles(ParticleTypes.POOF, x, at.y + 0.4, z, 6, 0.2, 0.2, 0.2, 0.02);
		level.playSound(null, x, at.y, z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.7f, 1.3f);
	}
}
