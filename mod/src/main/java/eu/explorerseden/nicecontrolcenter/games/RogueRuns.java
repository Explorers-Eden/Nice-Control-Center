package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Dungeons.Dungeon;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.Run;
import eu.explorerseden.nicecontrolcenter.games.bosses.BossFight;
import eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses;

/**
 * Rogue runs: a dungeon entry in "rogue" mode has no box of its own. Its run is a chain of rooms (saved like dungeon
 * rooms, picked from the group set for each room type), one at a time in the run's slot: the room is pasted at one
 * of two spots (the next one at the other, so they never overlap), its mobs spawn harder the deeper the run is, and
 * once they're all down its door markers open, each showing the type of room it leads to. The party leader stands
 * on one to go on; the old room is wiped behind them. Out is out (no lives), the run ends when everyone is.
 */
final class RogueRuns {
	/** The second spot sits this far along +Z from the first (rooms are at most ROOM_MAX on each side). */
	static final int SPOT_GAP = DungeonTemplates.ROOM_MAX + 16;
	/** Ticks the picker stands on a door before the run goes through it. */
	private static final int DOOR_TICKS = 40;
	private static final String SCALED = "ncc_rogue_scaled";
	private static final String DOOR_TAG = "ncc_rogue_door";
	private static final Identifier TOUGHER = Identifier.fromNamespaceAndPath("nicecontrolcenter", "rogue_toughness");
	private static final Random random = new Random();

	/** A door of the room: where it is, the room type behind it, its floating label. */
	static final class Door {
		final BlockPos pos;
		String type;
		UUID label;

		Door(BlockPos pos) {
			this.pos = pos;
		}
	}

	/** The rogue part of a run. */
	static final class State {
		/** The room's number in the run (a start room is 0, the first fight 1). */
		int depth;
		String type;
		Dungeons.Room room;
		int spot;
		boolean open;
		long roomAt;
		/** % more health and damage for this room's mobs (0.3 = +30 %). */
		double more;
		final List<Door> doors = new ArrayList<>();
		int doorTicks;
		Door chosen;
		Dungeons.Room nextRoom;
		CompletableFuture<StructureTemplate> next;
		final Map<String, Integer> used = new HashMap<>();
		String last;
		/** Rooms cleared (the score), bosses beaten. */
		int cleared;
		int bossesDown;
		/** Run coins, boon stacks (boon id → count), boons on offer, rerolls left: per player. */
		final Map<UUID, Integer> coins = new HashMap<>();
		final Map<UUID, Map<String, Integer>> stacks = new HashMap<>();
		final Map<UUID, List<String>> offers = new HashMap<>();
		final Map<UUID, Integer> rerolls = new HashMap<>();
		/** Max health from room rewards (health points) per player. */
		final Map<UUID, Integer> bonusHealth = new HashMap<>();
		final Map<UUID, Integer> bonusArmor = new HashMap<>();
		int offerToken;
		long offersUntil;
		/** Shop rooms: where the shop is, and who stands there (opened once per step on). */
		final List<BlockPos> shops = new ArrayList<>();
		final java.util.Set<UUID> atShop = new java.util.HashSet<>();
		final ArrayDeque<int[]> toWipe = new ArrayDeque<>();
		BlockPos wipeMin;
		BlockPos wipeMax;
	}

	private RogueRuns() {
	}

	static String tag(Run r) {
		return "ncc_rogue_" + r.slot;
	}

	static BlockPos origin(Run r, int spot) {
		return r.min.offset(0, 0, spot * SPOT_GAP);
	}

	/** Both spots: where the run's box ends. */
	static BlockPos max(BlockPos min) {
		return min.offset(DungeonTemplates.ROOM_MAX - 1, DungeonTemplates.ROOM_MAX - 1, SPOT_GAP + DungeonTemplates.ROOM_MAX - 1);
	}

	// ---------------------------------------------------------------- picking rooms

	/** Rooms of the group that can come at this depth (on, saved, with a start marker, under their per-run limit). */
	private static List<Dungeons.Room> candidates(Dungeons.Settings s, State st, String group, int depth) {
		if (group == null || group.isEmpty() || s.group(group, true).minDepth > depth) return List.of();
		List<Dungeons.Room> out = new ArrayList<>();
		for (Dungeons.Room room : s.roomsIn(group, true)) {
			if (!room.enabled || !room.saved() || room.markers.stream().noneMatch(m -> "start".equals(m.type))) continue;
			if (room.maxPerCopy > 0 && st != null && st.used.getOrDefault(room.name, 0) >= room.maxPerCopy) continue;
			out.add(room);
		}
		return out;
	}

	/** A weighted pick (each room considered by its chance; not the room just played while others are left). */
	private static Dungeons.Room pick(Dungeons.Settings s, State st, String group, int depth) {
		List<Dungeons.Room> all = candidates(s, st, group, depth);
		if (all.isEmpty()) return null;
		List<Dungeons.Room> some = new ArrayList<>(all.stream().filter(x -> random.nextInt(100) < x.chance).toList());
		if (some.isEmpty()) some.addAll(all);
		if (some.size() > 1 && st != null && st.last != null) some.removeIf(x -> x.name.equals(st.last));
		int total = some.stream().mapToInt(x -> x.weight).sum();
		int roll = random.nextInt(Math.max(1, total));
		for (Dungeons.Room x : some) {
			roll -= x.weight;
			if (roll < 0) return x;
		}
		return some.get(0);
	}

	private static String groupOf(Dungeon d, String type) {
		return d.roomGroups.getOrDefault(type, "");
	}

	/** A rogue entry players can start: a first room exists. */
	static boolean ready(Dungeons.Settings s, Dungeon d) {
		return !candidates(s, null, d.startGroup, 0).isEmpty() || !candidates(s, null, groupOf(d, "fight"), 1).isEmpty();
	}

	/** Why a rogue entry can't start yet (for the panel and /rogue list), or null. */
	static String problem(Dungeons.Settings s, Dungeon d) {
		if (ready(s, d)) return null;
		String g = groupOf(d, "fight");
		return g.isEmpty() ? "no group set for fight rooms" : "no saved room with a start marker in the group " + g;
	}

	/** The first room of a new run: picked and read on the worker (into r.roomLoading); returns why not, or null. */
	static String prepare(MinecraftServer server, Dungeons.Settings s, Run r) {
		Dungeon d = r.dungeon;
		State st = new State();
		Dungeons.Room first = pick(s, st, d.startGroup, 0);
		if (first != null) {
			st.type = "start";
			st.depth = 0;
		} else {
			first = pick(s, st, groupOf(d, "fight"), 1);
			st.type = "fight";
			st.depth = 1;
		}
		if (first == null) return d.name + " has no room to start with yet (" + problem(s, d) + ").";
		st.room = first;
		r.rogue = st;
		String name = first.name;
		r.roomLoading = DungeonTemplates.onWorker(() -> Map.of(name, DungeonTemplates.readRoom(server, name)));
		return null;
	}

	// ---------------------------------------------------------------- rooms

	/** The first room, when the run begins (players are brought to r.start afterwards). */
	static void enterFirst(MinecraftServer server, ServerLevel level, Run r) {
		State st = r.rogue;
		place(server, level, r, r.roomBlocks.get(st.room.name));
	}

	/** Pastes st.room at st.spot, turns its markers into what they say and spawns its mobs. */
	private static void place(MinecraftServer server, ServerLevel level, Run r, StructureTemplate t) {
		State st = r.rogue;
		Dungeon d = r.dungeon;
		BlockPos at0 = origin(r, st.spot);
		DungeonTemplates.pasteRoom(level, at0, Rotation.NONE, t, level.getRandom());
		st.used.merge(st.room.name, 1, Integer::sum);
		st.last = st.room.name;
		st.open = false;
		st.roomAt = System.currentTimeMillis();
		st.doors.clear();
		st.shops.clear();
		st.atShop.clear();
		st.doorTicks = 0;
		boolean elite = "elite".equals(st.type);
		st.more = d.toughness / 100.0 * Math.max(0, st.depth - 1) + (elite ? d.eliteToughness / 100.0 : 0);
		r.start = null;
		r.checkpoint = null;
		List<BlockPos> spawns = new ArrayList<>();
		boolean bossMarker = false;
		boolean fights = "fight".equals(st.type) || elite || "boss".equals(st.type);
		for (Dungeons.Marker m : st.room.markers) {
			BlockPos at = at0.offset(m.x, m.y, m.z);
			level.setBlock(at, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS);
			switch (m.type) {
				case "start" -> {
					if (r.start == null) r.start = at;
				}
				case "door", "exit" -> st.doors.add(new Door(at));
				case "spawn" -> spawns.add(at);
				case "shop" -> st.shops.add(at);
				case "mob" -> {
					if (fights) spawnLine(server, r, at, m.arg, false);
				}
				case "boss" -> {
					if ("boss".equals(st.type)) {
						spawnLine(server, r, at, m.arg, true);
						bossMarker = true;
					}
				}
				default -> {
				}
			}
		}
		if (r.start == null) r.start = at0.offset(st.room.sizeX / 2, 1, st.room.sizeZ / 2);
		// Jigsaw blocks in the room (made for dungeons) turn into what they'd become without a room behind them.
		var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		for (Dungeons.Connector c : st.room.connectors) {
			net.minecraft.world.level.block.state.BlockState state;
			try {
				state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(blocks, c.finalState, false).blockState();
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				state = Blocks.AIR.defaultBlockState();
			}
			level.setBlock(at0.offset(c.x, c.y, c.z), state, 2);
		}
		if (spawns.isEmpty()) spawns.add(r.start);
		for (BlockPos s : st.shops) RogueBoons.shopNpc(server, r, s, tag(r) + "_label");
		if ("boss".equals(st.type) && !bossMarker) {
			String line = MobLines.pickLine(d.bosses.stream().filter(x -> MobLines.parse(x) != null).toList(), random);
			if (line != null) {
				BlockPos far = farthest(spawns, r.start);
				spawnLine(server, r, far, line, true);
				bossMarker = true;
			}
		}
		if (bossMarker) {
			r.bosses = 1;
			r.bossHealth = 0;
			r.bossTotal = 0;
			r.bossesDown = 0;
		}
		// Generated mobs at the spawn markers: more each room and per extra player; a boss room only gets its boss.
		if (("fight".equals(st.type) || elite || "boss".equals(st.type) && !bossMarker) && !d.mobs.isEmpty()) {
			int size = Math.max(1, r.players.size() - r.out.size());
			int want = (int) Math.round((d.firstMobs + d.perRoom * Math.max(0, st.depth - 1)) * (1 + d.perPlayer / 100.0 * (size - 1)));
			want = Math.min(40, want);
			List<MobLines.Entry> pool = d.mobs.stream().map(MobLines::parse).filter(e -> e != null && e.weight() > 0).toList();
			int made = 0, i = 0;
			while (made < want && !pool.isEmpty()) {
				MobLines.Entry e = MobLines.pick(pool, random);
				for (int n = 0; n < Math.max(1, e.count()) && made < want; n++, made++) {
					BlockPos at = spawns.get(i++ % spawns.size());
					spawnOne(server, r, at, new MobLines.Entry(e.id(), 1, e.nbt()), false);
				}
			}
		}
		NiceControlCenter.LOGGER.info("Dungeons: rogue run {} in slot {}: room {} ({}, {}) at {}, mobs +{}%", d.name, r.slot, st.depth, st.room.name, st.type, at0.toShortString(), Math.round(st.more * 100));
	}

	private static BlockPos farthest(List<BlockPos> spots, BlockPos from) {
		BlockPos best = spots.get(0);
		for (BlockPos b : spots) if (b.distSqr(from) > best.distSqr(from)) best = b;
		return best;
	}

	/** A marker's mob line ("zombie x3", "ncc:frost_warden"): every one of the count, tagged for the room. */
	private static void spawnLine(MinecraftServer server, Run r, BlockPos at, String line, boolean boss) {
		MobLines.Entry e = MobLines.parse(line);
		if (e == null) return;
		int count = boss || KatterBosses.is(e.id()) ? 1 : Math.max(1, e.count());
		for (int i = 0; i < count; i++) spawnOne(server, r, at, new MobLines.Entry(e.id(), 1, e.nbt()), boss);
	}

	private static void spawnOne(MinecraftServer server, Run r, BlockPos at, MobLines.Entry e, boolean boss) {
		double ox = (random.nextDouble() - 0.5) * 1.5, oz = (random.nextDouble() - 0.5) * 1.5;
		if (KatterBosses.is(e.id())) {
			BlockPos o = origin(r, r.rogue.spot);
			AABB bounds = new AABB(o.getX(), o.getY(), o.getZ(), o.getX() + r.rogue.room.sizeX, o.getY() + r.rogue.room.sizeY, o.getZ() + r.rogue.room.sizeZ);
			KatterBosses.spawn(server, e.id(), Dungeons.ROGUE_DIMENSION, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, List.of(tag(r)), boss ? r.bossTag() : null, false,
					p -> r.players.contains(p.getUUID()), bounds, (float) (1 + r.rogue.more));
			return;
		}
		String tags = "Tags:[\"" + tag(r) + "\"" + (boss ? ",\"" + r.bossTag() + "\"" : "") + "]";
		MobLines.summon(server, Dungeons.ROGUE_DIMENSION, at.getX() + 0.5 + ox, at.getY(), at.getZ() + 0.5 + oz, e, tags);
	}

	/** More health and damage (the room's share), once per mob. */
	private static void toughen(LivingEntity m, double more) {
		m.addTag(SCALED);
		if (more <= 0) return;
		AttributeInstance hp = m.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.addOrReplacePermanentModifier(new AttributeModifier(TOUGHER, more, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			m.setHealth(m.getMaxHealth());
		}
		AttributeInstance dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
		if (dmg != null) dmg.addOrReplacePermanentModifier(new AttributeModifier(TOUGHER, more, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
	}

	// ---------------------------------------------------------------- the loop

	/** Every 5 ticks while the run is on (after DungeonRuntime's own checks). */
	static void tick(MinecraftServer server, ServerLevel level, Run r) {
		State st = r.rogue;
		for (int i = 0; i < DungeonRuntime.settings().piecesPerTick * 2 && !st.toWipe.isEmpty(); i++) {
			int[] at = st.toWipe.poll();
			DungeonTemplates.wipe(level, st.wipeMin, st.wipeMax, at[0], at[1]);
		}
		BlockPos o = origin(r, st.spot);
		AABB room = new AABB(o.getX() - 8, o.getY() - 16, o.getZ() - 8, o.getX() + st.room.sizeX + 8, o.getY() + st.room.sizeY + 16, o.getZ() + st.room.sizeZ + 8);
		int alive = 0;
		for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, room, e -> e.isAlive() && e.entityTags().contains(tag(r)))) {
			if (m.entityTags().contains(BossFight.ASSET)) continue;
			if (!m.entityTags().contains(SCALED) && !m.entityTags().contains(BossFight.TAG)) toughen(m, st.more);
			alive++;
		}
		if (server.getTickCount() % 100 == 0) for (ServerPlayer p : inside(server, r)) RogueBoons.keep(r, p);
		RogueBoons.timeUp(server, r);
		if (!st.open) {
			if (alive == 0 && System.currentTimeMillis() - st.roomAt > 1500) opened(server, level, r);
			else if (server.getTickCount() % 20 == 0 && alive > 0) {
				for (ServerPlayer p : inside(server, r)) p.sendOverlayMessage(Words.lit(alive + (alive == 1 ? " foe left" : " foes left")).withStyle(ChatFormatting.RED));
			}
			return;
		}
		if (st.next != null) {
			if (st.next.isDone()) move(server, level, r);
			return;
		}
		if (server.getTickCount() % 10 == 0) doorParticles(level, st);
		ServerPlayer picker = picker(server, r);
		Door on = null;
		if (picker != null) for (Door door : st.doors) if (onDoor(picker, door.pos)) on = door;
		if (on == null) {
			st.doorTicks = 0;
			// Someone else on a door: who picks.
			if (picker != null && server.getTickCount() % 40 == 0) {
				for (ServerPlayer p : inside(server, r)) {
					if (p == picker) continue;
					for (Door door : st.doors) if (onDoor(p, door.pos)) p.sendOverlayMessage(Words.lit(picker.getGameProfile().name() + " picks the door").withStyle(ChatFormatting.GRAY));
				}
			}
			return;
		}
		if (RogueBoons.waiting(r)) {
			st.doorTicks = 0;
			if (server.getTickCount() % 20 == 0) picker.sendOverlayMessage(Words.lit("Waiting for everyone to choose a boon… (" + Math.max(0, (st.offersUntil - System.currentTimeMillis()) / 1000) + " s)").withStyle(ChatFormatting.AQUA));
			return;
		}
		st.doorTicks += 5;
		if (st.doorTicks < DOOR_TICKS) {
			if (st.doorTicks % 20 == 5) {
				picker.sendOverlayMessage(Words.lit("→ " + label(on.type) + " room in " + ((DOOR_TICKS - st.doorTicks) / 20 + 1) + "… step off to pick another").withStyle(color(on.type)));
				level.playSound(null, on.pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
			}
			return;
		}
		choose(server, r, on, picker.getGameProfile().name());
	}

	/** The players of the run still in it, alive, in the dungeon dimension. */
	static List<ServerPlayer> inside(MinecraftServer server, Run r) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && !r.out.contains(id) && p.isAlive() && DungeonRuntime.inDungeonDimension(p)) out.add(p);
		}
		return out;
	}

	/** Who picks the door: the party leader while in, else the first player still in. */
	private static ServerPlayer picker(MinecraftServer server, Run r) {
		List<ServerPlayer> in = inside(server, r);
		for (ServerPlayer p : in) if (Parties.inParty(p.getUUID()) && Parties.isLeader(p.getUUID())) return p;
		return in.isEmpty() ? null : in.get(0);
	}

	private static boolean onDoor(ServerPlayer p, BlockPos door) {
		double dx = p.getX() - (door.getX() + 0.5), dz = p.getZ() - (door.getZ() + 0.5), dy = p.getY() - door.getY();
		return dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5;
	}

	/** Every foe down: the room counts, its doors open and show where they lead. */
	private static void opened(MinecraftServer server, ServerLevel level, Run r) {
		State st = r.rogue;
		Dungeon d = r.dungeon;
		st.open = true;
		boolean fight = "fight".equals(st.type) || "elite".equals(st.type) || "boss".equals(st.type);
		if (fight) {
			st.cleared++;
			DungeonRuntime.rewardInside(server, r, "rogueRoom", st.depth, false);
			if ("boss".equals(st.type)) {
				st.bossesDown++;
				DungeonRuntime.rewardInside(server, r, "rogueBoss", st.bossesDown, false);
			}
		}
		if (d.maxDepth > 0 && st.depth >= d.maxDepth) {
			DungeonRuntime.clear(server, r);
			return;
		}
		if (st.doors.isEmpty()) st.doors.add(new Door(r.start));
		if (!doorTypes(r)) {
			// Nothing left to go to (no rooms for any type at this depth): the run is won.
			DungeonRuntime.clear(server, r);
			return;
		}
		for (Door door : st.doors) door.label = label(level, door, r);
		if (fight) RogueBoons.roomReward(server, r);
		else if ("treasure".equals(st.type)) RogueBoons.offerAll(server, r);
		if (fight) {
			DungeonRuntime.tellRun(server, r, Words.lit("✔ Room " + st.depth + " cleared! Step on a door to go on.").withStyle(ChatFormatting.GREEN));
			level.playSound(null, r.start, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
		}
	}

	/** Gives every door a room type for the next room (all boss rooms when one is due); false when there's none. */
	private static boolean doorTypes(Run r) {
		State st = r.rogue;
		Dungeon d = r.dungeon;
		Dungeons.Settings s = DungeonRuntime.settings();
		int next = st.depth + 1;
		if (d.bossEvery > 0 && next % d.bossEvery == 0 && !candidates(s, st, groupOf(d, "boss"), next).isEmpty()) {
			for (Door door : st.doors) door.type = "boss";
			return true;
		}
		Map<String, Integer> weights = new java.util.LinkedHashMap<>();
		for (String t : Dungeons.ROGUE_TYPES) {
			int w = d.typeWeights.getOrDefault(t, 0);
			if (w > 0 && !candidates(s, st, groupOf(d, t), next).isEmpty()) weights.put(t, w);
		}
		if (weights.isEmpty()) {
			if (candidates(s, st, groupOf(d, "fight"), next).isEmpty()) return false;
			weights.put("fight", 1);
		}
		// Different types on the doors while there are different ones to give.
		Map<String, Integer> left = new java.util.LinkedHashMap<>(weights);
		for (Door door : st.doors) {
			if (left.isEmpty()) left.putAll(weights);
			int total = left.values().stream().mapToInt(Integer::intValue).sum(), roll = random.nextInt(total);
			String type = null;
			for (Map.Entry<String, Integer> e : left.entrySet()) {
				roll -= e.getValue();
				if (roll < 0) {
					type = e.getKey();
					break;
				}
			}
			door.type = type;
			left.remove(type);
		}
		return true;
	}

	static String label(String type) {
		return switch (type) {
			case "elite" -> "☠ Elite";
			case "boss" -> "♛ Boss";
			case "treasure" -> "✦ Treasure";
			case "shop" -> "🛒 Shop";
			case "rest" -> "❤ Rest";
			case "start" -> "Start";
			default -> "⚔ Fight";
		};
	}

	private static ChatFormatting color(String type) {
		return switch (type) {
			case "elite" -> ChatFormatting.LIGHT_PURPLE;
			case "boss" -> ChatFormatting.GOLD;
			case "treasure" -> ChatFormatting.AQUA;
			case "shop" -> ChatFormatting.YELLOW;
			case "rest" -> ChatFormatting.GREEN;
			default -> ChatFormatting.RED;
		};
	}

	private static int rgb(String type) {
		return switch (type) {
			case "elite" -> 0xC060FF;
			case "boss" -> 0xFFB000;
			case "treasure" -> 0x40E0FF;
			case "shop" -> 0xFFFF55;
			case "rest" -> 0x55FF55;
			default -> 0xFF4040;
		};
	}

	/** A floating label above the door (gone when the run moves on, or with the room). */
	private static UUID label(ServerLevel level, Door door, Run r) {
		return floating(level, door.pos, label(door.type), switch (door.type) {
			case "elite" -> "light_purple";
			case "boss" -> "gold";
			case "treasure" -> "aqua";
			case "shop" -> "yellow";
			case "rest" -> "green";
			default -> "red";
		}, r);
	}

	/** A text above the spot (wiped with the room). */
	private static UUID floating(ServerLevel level, BlockPos at, String text0, String color, Run r) {
		String text = text0.replace("\"", "");
		String snbt = "{billboard:\"center\",see_through:1b,text:{text:\"" + text + "\",color:\"" + color + "\",bold:1b},Tags:[\""
				+ DOOR_TAG + "\",\"" + tag(r) + "_label\",\"" + MobLines.NMM_EXCLUDE + "\"]}";
		try {
			CompoundTag tag = TagParser.parseCompoundFully(snbt);
			Entity e = EntityType.loadEntityRecursive(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:text_display")), tag, level, EntitySpawnReason.COMMAND, made -> {
				made.snapTo(at.getX() + 0.5, at.getY() + 2.3, at.getZ() + 0.5, 0, 0);
				return made;
			});
			if (e == null || !level.addFreshEntity(e)) return null;
			return e.getUUID();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Dungeons: rogue door label failed: {}", e.getMessage());
			return null;
		}
	}

	private static void doorParticles(ServerLevel level, State st) {
		for (Door door : st.doors) {
			DustParticleOptions dust = new DustParticleOptions(rgb(door.type), 1.3f);
			for (int i = 0; i < 6; i++) {
				double a = i * Math.PI / 3 + level.getGameTime() / 10.0;
				level.sendParticles(dust, door.pos.getX() + 0.5 + Math.cos(a) * 0.7, door.pos.getY() + 0.15, door.pos.getZ() + 0.5 + Math.sin(a) * 0.7, 1, 0, 0, 0, 0);
			}
		}
	}

	/** Through a door: the next room of its type is picked and read on the worker; the move happens once it's there. */
	static void choose(MinecraftServer server, Run r, Door door, String by) {
		State st = r.rogue;
		Dungeons.Settings s = DungeonRuntime.settings();
		int next = st.depth + 1;
		Dungeons.Room room = pick(s, st, groupOf(r.dungeon, door.type), next);
		if (room == null) {
			room = pick(s, st, groupOf(r.dungeon, "fight"), next);
			door.type = "fight";
		}
		if (room == null) {
			DungeonRuntime.clear(server, r);
			return;
		}
		st.chosen = door;
		st.nextRoom = room;
		String name = room.name;
		st.next = DungeonTemplates.onWorker(() -> DungeonTemplates.readRoom(server, name));
		DungeonRuntime.tellRun(server, r, Words.lit("→ " + (by == null ? "" : by + " chose ") + label(door.type) + " room").withStyle(color(door.type)));
	}

	/** By number (1 = the first door): for /rogue pick from the console. */
	static String chooseNumber(MinecraftServer server, Run r, int n) {
		State st = r.rogue;
		if (st == null) return "That run is no rogue run.";
		if (!st.open) return "The room isn't cleared yet.";
		if (st.next != null) return "The next room is on its way already.";
		if (n < 1 || n > st.doors.size()) return "The room has " + st.doors.size() + (st.doors.size() == 1 ? " door." : " doors.");
		choose(server, r, st.doors.get(n - 1), "An admin");
		return null;
	}

	/** The next room is read: pasted at the other spot, everyone brought there, the old room wiped behind them. */
	private static void move(MinecraftServer server, ServerLevel level, Run r) {
		State st = r.rogue;
		StructureTemplate t;
		try {
			t = st.next.join();
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Dungeons: rogue run {} could not read the room {}: {}", r.dungeon.name, st.nextRoom.name, e.getMessage());
			st.next = null;
			st.chosen = null;
			DungeonRuntime.tellRun(server, r, Words.lit("That room couldn't be loaded: pick a door again.").withStyle(ChatFormatting.RED));
			return;
		}
		for (Door door : st.doors) {
			Entity label = door.label == null ? null : level.getEntity(door.label);
			if (label != null) label.discard();
		}
		// What's left to wipe of the room before last (at the spot we're going to) goes now.
		while (!st.toWipe.isEmpty()) {
			int[] at = st.toWipe.poll();
			DungeonTemplates.wipe(level, st.wipeMin, st.wipeMax, at[0], at[1]);
		}
		BlockPos oldMin = origin(r, st.spot);
		BlockPos oldMax = oldMin.offset(st.room.sizeX - 1, st.room.sizeY - 1, st.room.sizeZ - 1);
		st.spot = 1 - st.spot;
		st.depth++;
		st.type = st.chosen.type;
		st.room = st.nextRoom;
		st.chosen = null;
		st.nextRoom = null;
		st.next = null;
		place(server, level, r, t);
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || !DungeonRuntime.inDungeonDimension(p) || !p.isAlive()) continue;
			p.teleportTo(level, r.start.getX() + 0.5, r.start.getY(), r.start.getZ() + 0.5, Set.of(), p.getYRot(), 0, true);
			p.resetFallDistance();
			p.connection.send(new ClientboundSetTitleTextPacket(Words.lit("Room " + st.depth).withStyle(color(st.type))));
			p.connection.send(new ClientboundSetSubtitleTextPacket(Words.lit(label(st.type)).withStyle(ChatFormatting.GRAY)));
		}
		level.playSound(null, r.start, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7f, 0.9f);
		arrive(server, r);
		// The old room goes a few pieces at a time (entities in it too: drops, arrows, labels).
		st.wipeMin = oldMin;
		st.wipeMax = oldMax;
		for (int dx = 0; dx <= oldMax.getX() - oldMin.getX(); dx += Dungeons.PIECE) {
			for (int dz = 0; dz <= oldMax.getZ() - oldMin.getZ(); dz += Dungeons.PIECE) st.toWipe.add(new int[] {dx, dz});
		}
	}

	/** Everyone is in the new room: rest rooms heal, shops open. */
	private static void arrive(MinecraftServer server, Run r) {
		State st = r.rogue;
		for (ServerPlayer p : inside(server, r)) {
			if ("rest".equals(st.type)) {
				Dungeons.Rogue g = DungeonRuntime.settings().rogue;
				float heal = p.getMaxHealth() * g.restHeal / 100f;
				if (heal > 0) p.heal(heal);
				var food = p.getFoodData();
				if (g.restFood > 0) food.setFoodLevel(Math.min(20, food.getFoodLevel() + g.restFood));
				if (g.restSaturation > 0) food.setSaturation(Math.min(food.getFoodLevel(), food.getSaturationLevel() + g.restSaturation));
				if (g.restCleanse) {
					for (var e : List.copyOf(p.getActiveEffects())) {
						if (e.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) p.removeEffect(e.getEffect());
					}
				}
				for (String line : g.restEffects) {
					String[] w = line.strip().split("\\s+");
					try {
						var eff = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(w[0])).orElse(null);
						int secs = w.length > 1 ? Integer.parseInt(w[1]) : 10, lv = w.length > 2 ? Integer.parseInt(w[2]) : 1;
						if (eff != null) p.addEffect(new net.minecraft.world.effect.MobEffectInstance(eff, Math.max(1, Math.min(3600, secs)) * 20, Math.max(0, Math.min(9, lv - 1))));
					} catch (RuntimeException e) {
						NiceControlCenter.LOGGER.warn("Dungeons: rest room effect \"{}\" doesn't work: {}", line, e.getMessage());
					}
				}
				for (String c : g.restCommands) {
					String cmd = c.strip().replaceFirst("^/", "").replace("{player}", p.getGameProfile().name());
					if (!cmd.isEmpty()) eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Rogue");
				}
				p.sendSystemMessage(Words.lit(g.restMessage.isBlank() ? "❤ You rest a moment and catch your breath." : g.restMessage.replace("{player}", p.getGameProfile().name())).withStyle(ChatFormatting.GREEN));
				((ServerLevel) p.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, p.getX(), p.getY() + 1.2, p.getZ(), 6, 0.4, 0.4, 0.4, 0);
			} else if ("shop".equals(st.type) && st.shops.isEmpty()) {
				RogueBoons.shop(r, p, null);
			}
		}
	}

	/** For the HUD: " · room 4 ✔ · 12 ⛁". */
	static String hud(Run r, ServerPlayer p) {
		State st = r.rogue;
		return " · room " + st.depth + (st.open ? " ✔" : "") + " · " + st.coins.getOrDefault(p.getUUID(), 0) + " ⛁";
	}
}
