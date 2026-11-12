package eu.explorerseden.nicecontrolcenter.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Dungeons.Dungeon;
import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.core.AsyncFiles;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Runs the dungeons: the leader of a party (or a player on their own) standing on a pad gets a random dungeon from
 * the pad's pool, copied into a free slot of the dungeon dimension (slots sit slotSpacing blocks apart along +X).
 * The copy is pasted a few pieces per tick behind a boss bar, its markers spawn mobs and bosses, and the party is
 * brought to its start. Checkpoints set where they come back after a death (while lives last); out players watch
 * as spectators or go back. Reaching an exit (or every boss dead) clears it; afterwards the slot is wiped (also on
 * start-up, so no copy outlives a restart) and freed.
 */
public final class DungeonRuntime {
	public static final String PERMISSION = GameRuntime.PERMISSION;
	/** Where copies stand (their lowest corner's Y). */
	private static final int BASE_Y = 64;
	/** Ticks the leader stands on a pad before the run starts. */
	private static final int PAD_TICKS = 40;
	/** Party members this close to the pad come along. */
	private static final double PARTY_RANGE = 16;

	enum State { LOADING, PASTING, RUNNING, ENDING, WIPING }

	/** One copy of a dungeon and the players in it. */
	static final class Run {
		final int slot;
		final Dungeon dungeon;
		final Set<UUID> players = new LinkedHashSet<>();
		/** The kit each player drew (the same one again after a death). */
		final Map<UUID, List<String>> kits = new HashMap<>();
		final Set<UUID> out = new LinkedHashSet<>();
		final Map<UUID, String> names = new HashMap<>();
		/** Deaths left before a player is out. */
		final Map<UUID, Integer> lives = new HashMap<>();
		/** Game modes of players who watch as spectators (given back afterwards). */
		final Map<UUID, GameType> watching = new HashMap<>();
		/** Players who died and come back inside (checkpoint) or watch: handled when they respawn. */
		final Set<UUID> respawnInside = new LinkedHashSet<>();
		State state = State.LOADING;
		CompletableFuture<List<DungeonTemplates.Piece>> loading;
		ArrayDeque<DungeonTemplates.Piece> toPaste;
		/** The rooms picked for this copy, their parsed blocks (read on the worker) and the ones still to paste. */
		DungeonRooms.Layout layout;
		CompletableFuture<Map<String, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>> roomLoading;
		Map<String, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> roomBlocks;
		ArrayDeque<DungeonRooms.Placed> roomsToPaste;
		int total;
		/** Wiping: the pieces still to clear (dx, dz). */
		ArrayDeque<int[]> toWipe;
		BlockPos min;
		BlockPos max;
		BlockPos start;
		BlockPos checkpoint;
		final List<BlockPos> exits = new ArrayList<>();
		final List<BlockPos> checkpoints = new ArrayList<>();
		int bosses;
		/** Bosses alive when they were first counted, and how many are down since (for the boss rewards). */
		int bossTotal;
		int bossesDown;
		/** Checkpoints reached at least once this run (going back to one doesn't count again). */
		final Set<BlockPos> reached = new java.util.HashSet<>();
		float bossHealth;
		int deaths;
		long startedAt;
		long endAt;
		boolean cleared;
		ServerBossEvent bar;
		/** Rogue runs: the room chain (null for a dungeon). */
		RogueRuns.State rogue;

		Run(int slot, Dungeon d) {
			this.slot = slot;
			this.dungeon = d;
		}

		long millis() {
			return startedAt == 0 ? 0 : (endAt > 0 && state != State.RUNNING ? endAt - (cleared ? 8000 : 3000) : System.currentTimeMillis()) - startedAt;
		}

		String bossTag() {
			return "ncc_dungeon_boss_" + slot;
		}

		boolean kit() {
			return "kit".equals(dungeon.gear);
		}
	}

	private static Dungeons.Settings settings = new Dungeons.Settings().fill();
	private static long modified = -1;
	private static final Map<Integer, Run> slots = new LinkedHashMap<>();
	/** player → the run they're in (also while they watch as spectators). */
	private static final Map<UUID, Run> playing = new HashMap<>();
	/** player → ticks the leader stood on a pad. */
	private static final Map<UUID, Integer> onPad = new HashMap<>();
	private static final Map<UUID, Long> cooldown = new HashMap<>();
	/** player → the dungeon they played last (so the next pick is another when the pool has more). */
	private static final Map<UUID, String> lastPlayed = new HashMap<>();
	/** player → until when no grave is made (they died in a run). */
	private static final Map<UUID, Long> noGraveUntil = new HashMap<>();
	/** player → what to do once they've respawned after a run ended for them (give things back, send back). */
	private static final Set<UUID> afterRespawn = new LinkedHashSet<>();
	private static final Map<UUID, Boolean> afterRespawnRemove = new HashMap<>();
	/** Admins making a dungeon: name and the two corners. */
	private static final Map<UUID, Selection> selections = new HashMap<>();
	private static Map<String, Map<String, Dungeons.Best>> scores = new LinkedHashMap<>();
	private static Path scoresFile;
	private static Path slotsFile;
	private static final Random random = new Random();

	private record Selection(String name, String dim, BlockPos a, BlockPos b, long until, boolean room, boolean rogue) {
	}

	/** /dungeon create <name> [pos]: the first corner (given, looked at, or at their feet); the box is as high as its corners. */
	private static int corner1(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		BlockPos a = Corners.pick(ctx, p);
		selections.put(p.getUUID(), new Selection(name, p.level().dimension().identifier().toString(), a, null, System.currentTimeMillis() + 30 * 60_000L, false, false));
		Corners.show(p, a, null);
		ctx.getSource().sendSuccess(() -> Words.lit("Corner 1 of " + name + " is at " + a.toShortString() + ". Set the opposite corner (height included) with /dungeon corner2 [x y z]"
				+ " (or look at it), then /dungeon save " + name
				+ ". Inside, place data structure blocks as markers: start (required), exit, checkpoint, mob:<id> [xN] [{data}], boss:<id> [{data}].").withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int corner2(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Selection s = selections.get(p.getUUID());
		if (s == null) return fail(ctx, "Start with /dungeon create <name> (or /dungeon room create, /rogue room create) at the first corner.");
		if (!s.dim.equals(p.level().dimension().identifier().toString())) return fail(ctx, "Both corners have to be in the same dimension.");
		BlockPos b = Corners.pick(ctx, p);
		selections.put(p.getUUID(), new Selection(s.name, s.dim, s.a, b, System.currentTimeMillis() + 30 * 60_000L, s.room, s.rogue));
		Corners.show(p, s.a, b);
		ctx.getSource().sendSuccess(() -> Words.lit("Box: " + Corners.size(s.a, b) + " (corner 2 at " + b.toShortString() + "). " + (s.rogue ? "/rogue room save " : s.room ? "/dungeon room save " : "/dungeon save ") + s.name + " keeps it.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	/** /dungeon (or /rogue) room create <name> [pos]: like a dungeon's first corner, for a room. */
	private static int roomCorner1(CommandContext<CommandSourceStack> ctx, boolean rogue) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		Dungeons.Room other = settings.room(name);
		if (other != null && other.rogue != rogue) return fail(ctx, "There's a " + (other.rogue ? "rogue room" : "dungeon room") + " called " + name + " already (room names are shared by both).");
		BlockPos a = Corners.pick(ctx, p);
		selections.put(p.getUUID(), new Selection(name, p.level().dimension().identifier().toString(), a, null, System.currentTimeMillis() + 30 * 60_000L, true, rogue));
		Corners.show(p, a, null);
		String cmd = rogue ? "/rogue" : "/dungeon";
		ctx.getSource().sendSuccess(() -> Words.lit("Corner 1 of the room " + name + " is at " + a.toShortString() + ". Set the opposite corner with " + cmd + " corner2 [x y z] (or look at it), then " + cmd + " room save " + name + " [group]"
				+ (rogue ? ". Inside: markers (/rogue tool marker …) start, door (where players pick the next room), spawn (where its mobs come), shop (shop rooms)."
						: ". Its doors are jigsaw blocks (/dungeon tool door <name>); spots for rooms inside it too (/dungeon tool spot <group> <door>).")).withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	/** /dungeon (or /rogue) room save <name> [group] (or with from/to corners). */
	private static int saveRoom(CommandContext<CommandSourceStack> ctx, boolean rogue) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayer();
		MinecraftServer server = ctx.getSource().getServer();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		String group = ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("group")) ? Dungeons.pool(StringArgumentType.getString(ctx, "group")) : null;
		Selection s = p == null ? null : selections.get(p.getUUID());
		Dungeons.Room room = settings.room(name);
		if (room != null && room.rogue != rogue) return fail(ctx, "There's a " + (room.rogue ? "rogue room" : "dungeon room") + " called " + name + " already (room names are shared by both).");
		ServerLevel level;
		BlockPos a, b;
		if (ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("to"))) {
			level = ctx.getSource().getLevel();
			a = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "from");
			b = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "to");
		} else if (s != null && s.room && s.rogue == rogue && s.name.equalsIgnoreCase(name) && s.b != null) {
			level = GameRuntime.levelOf(server, s.dim);
			a = s.a;
			b = s.b;
		} else if (room != null && room.from != null && room.to != null && room.sourceDim != null) {
			level = GameRuntime.levelOf(server, room.sourceDim);
			a = new BlockPos(room.from[0], room.from[1], room.from[2]);
			b = new BlockPos(room.to[0], room.to[1], room.to[2]);
		} else {
			String cmd = rogue ? "/rogue" : "/dungeon";
			return fail(ctx, "Mark the room first: " + cmd + " room create " + name + " at one corner, " + cmd + " corner2 at the other.");
		}
		String err = saveRoom(server, level, a, b, name, group, rogue, p == null ? "console" : p.getGameProfile().name(), msg -> ctx.getSource().sendSuccess(() -> msg, true),
				msg -> ctx.getSource().sendFailure(msg));
		if (err != null) return fail(ctx, err);
		if (p != null) selections.remove(p.getUUID());
		return 1;
	}

	/** Saves a room from its box (commands and the panel); returns why not, or null. Server thread. */
	private static String saveRoom(MinecraftServer server, ServerLevel level, BlockPos a, BlockPos b, String name, String group, boolean rogue, String by,
			java.util.function.Consumer<Component> done, java.util.function.Consumer<Component> failed) {
		if (level == null) return "That dimension isn't loaded.";
		int max = DungeonTemplates.ROOM_MAX;
		if (Math.abs(a.getX() - b.getX()) >= max || Math.abs(a.getY() - b.getY()) >= max || Math.abs(a.getZ() - b.getZ()) >= max) {
			return "A room is at most " + max + " × " + max + " × " + max + " blocks (" + Corners.size(a, b) + " here).";
		}
		Dungeons.Room existing = settings.room(name);
		if (existing != null && existing.rogue != rogue) return "There's a " + (existing.rogue ? "rogue room" : "dungeon room") + " called " + name + " already (room names are shared by both).";
		boolean fresh = existing == null;
		Dungeons.Room target = fresh ? new Dungeons.Room() : existing;
		if (fresh) {
			target.name = name;
			target.rogue = rogue;
		}
		if (group != null && !target.groups.contains(group)) target.groups.add(group);
		target.savedBy = by;
		DungeonTemplates.saveRoom(server, level, a, b, target).whenComplete((ok, err) -> server.execute(() -> {
			if (err != null) {
				failed.accept(Words.lit("Could not save the room " + name + ": " + err.getMessage()));
				return;
			}
			if (fresh) settings.rooms.add(target);
			settings.fill();
			saveSettings();
			long doors = target.connectors.stream().filter(c -> !c.spot()).count();
			String spots = spots(target.connectors);
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (Dungeons.Marker m : target.markers) counts.merge(m.type, 1, Integer::sum);
			String markers = counts.isEmpty() ? "" : ", " + counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).reduce((x, y) -> x + ", " + y).orElse("");
			String names = target.connectors.stream().filter(c -> !c.spot()).map(c -> c.name).distinct().reduce((x, y) -> x + ", " + y).orElse("");
			boolean rogueRoom = rogue || target.markers.stream().anyMatch(m -> "door".equals(m.type) || "spawn".equals(m.type));
			done.accept(Words.lit("Saved the room " + name + " (" + target.sizeX + " × " + target.sizeY + " × " + target.sizeZ + (doors == 0 && rogueRoom ? "" : ", " + doors + (doors == 1 ? " jigsaw door" : " jigsaw doors"))
					+ (names.isEmpty() ? "" : ": " + names) + (spots.isEmpty() ? "" : ", " + spots) + markers + ")"
					+ (target.groups.isEmpty() ? ". Put it in a group (panel, or " + (rogue ? "/rogue" : "/dungeon") + " room group " + name + " <group>) so " + (rogue ? "rogue runs" : "spots") + " can get it." : " in " + String.join(", ", target.groups) + ".")
					+ (target.connectors.isEmpty() && !rogueRoom ? " It has no jigsaw blocks, so it can't join a spot." : "")).withStyle(target.connectors.isEmpty() && !rogueRoom ? ChatFormatting.GOLD : ChatFormatting.GREEN));
		}));
		return null;
	}

	/** "2 room spots: hall ×2" (empty without spots). */
	private static String spots(List<Dungeons.Connector> connectors) {
		Map<String, Integer> by = new LinkedHashMap<>();
		for (Dungeons.Connector c : connectors) if (c.spot()) by.merge(c.pool, 1, Integer::sum);
		if (by.isEmpty()) return "";
		int n = by.values().stream().mapToInt(Integer::intValue).sum();
		return n + (n == 1 ? " room spot: " : " room spots: ") + by.entrySet().stream().map(e -> e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : "")).reduce((x, y) -> x + ", " + y).orElse("");
	}

	/** From the panel: saves a dungeon or a room again from the box it was built in. Server thread. */
	public static String resave(MinecraftServer server, String kind, String name, String by) {
		if ("room".equals(kind)) {
			Dungeons.Room room = settings.room(name);
			if (room == null) return "There's no such room.";
			if (room.from == null || room.to == null || room.sourceDim == null) return "The room has no box to save from yet.";
			return saveRoom(server, GameRuntime.levelOf(server, room.sourceDim), new BlockPos(room.from[0], room.from[1], room.from[2]), new BlockPos(room.to[0], room.to[1], room.to[2]),
					room.name, null, room.rogue, by, msg -> NiceControlCenter.LOGGER.info(msg.getString()), msg -> NiceControlCenter.LOGGER.warn(msg.getString()));
		}
		Dungeon d = settings.dungeon(name);
		if (d == null) return "There's no such dungeon.";
		if (d.from == null || d.to == null || d.sourceDim == null) return "The dungeon has no box to save from yet.";
		ServerLevel level = GameRuntime.levelOf(server, d.sourceDim);
		if (level == null) return "That dimension isn't loaded.";
		d.savedBy = by;
		DungeonTemplates.save(server, level, new BlockPos(d.from[0], d.from[1], d.from[2]), new BlockPos(d.to[0], d.to[1], d.to[2]), d).whenComplete((ok, err) -> server.execute(() -> {
			if (err != null) {
				NiceControlCenter.LOGGER.warn("Dungeons: could not save the dungeon {}: {}", d.name, err.getMessage());
				return;
			}
			settings.fill();
			saveSettings();
			NiceControlCenter.LOGGER.info("Dungeons: saved the dungeon {} again from its build ({} pieces{})", d.name, d.pieces, spots(d.connectors).isEmpty() ? "" : ", " + spots(d.connectors));
		}));
		return null;
	}

	/** /dungeon tool …: a vanilla block with its fields filled in (an op places it in creative and the data comes along). */
	private static int tool(CommandContext<CommandSourceStack> ctx, String item, String data, String title, String lore) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		// Built here and handed over (a /give through the console failed without a word).
		net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(item);
		net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id));
		net.minecraft.world.level.block.entity.BlockEntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id);
		net.minecraft.nbt.CompoundTag tag;
		try {
			tag = net.minecraft.nbt.TagParser.parseCompoundFully("{" + data + "}");
		} catch (CommandSyntaxException e) {
			return fail(ctx, "That text doesn't work in a block: " + e.getMessage());
		}
		tag.remove("id");
		if (st.isEmpty() || type == null) return fail(ctx, "No such block: " + item);
		st.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.TypedEntityData.of(type, tag));
		st.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.LIGHT_PURPLE));
		st.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(Component.literal(lore).withStyle(ChatFormatting.GRAY))));
		if (!p.getInventory().add(st)) p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
		ctx.getSource().sendSuccess(() -> Words.lit("Here's " + title + ". Place it in creative (as an op) and its settings come along.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	/** /dungeon tool spot <group> <door> [turns_into]: a jigsaw that gets a room of the group, joined at its door. */
	private static int spotTool(CommandContext<CommandSourceStack> ctx, String turnsInto) throws CommandSyntaxException {
		String group = Dungeons.pool(StringArgumentType.getString(ctx, "group"));
		String door = jigsawId(StringArgumentType.getString(ctx, "door"));
		try {
			turnsInto = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(net.minecraft.commands.arguments.blocks.BlockStateParser
					.parseForBlock(ctx.getSource().getServer().registryAccess().lookupOrThrow(Registries.BLOCK), turnsInto, false).blockState());
		} catch (CommandSyntaxException e) {
			return fail(ctx, "Not a block: " + turnsInto);
		}
		return tool(ctx, "minecraft:jigsaw", "id:\"minecraft:jigsaw\",name:" + snbt(door) + ",target:" + snbt(door) + ",pool:" + snbt("minecraft:" + group) + ",final_state:" + snbt(turnsInto)
				+ ",joint:\"rollable\"", "Room spot: " + group + " (door " + door.replace("minecraft:", "") + ")", "Gets a random " + group + " room; face it out through the doorway. Empty: " + turnsInto);
	}

	private static String snbt(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** A jigsaw id ("door" → "minecraft:door"). */
	private static String jigsawId(String word) {
		String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./:-]", "");
		return w.contains(":") ? w : "minecraft:" + w;
	}

	private DungeonRuntime() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Dungeons.FILE);
	}

	/** The rogue runs' own file (their runs, rooms, groups, pads and settings). */
	public static Path rogueFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(Dungeons.ROGUE_FILE);
	}

	public static Dungeons.Settings settings() {
		return settings;
	}

	public static void register() {
		Parties.register();
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			slotsFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-dungeon-slots.json");
			scoresFile = server.getWorldPath(LevelResource.ROOT).resolve(Dungeons.SCORES);
			scores = Dungeons.readScores(scoresFile);
			RogueBoons.load(server);
			wipeLeftovers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Run r : List.copyOf(slots.values())) {
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null && playing.get(id) == r) leaveRun(server, r, p);
				}
				if (r.bar != null) r.bar.removeAllPlayers();
			}
			// What's in use stays in the slots file and is wiped on the next start.
		});
		ServerTickEvents.END_SERVER_TICK.register(DungeonRuntime::tick);
		// Before death drops: what they carry is put aside (own gear) or dealt with (kit), so nothing lies in the copy.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p && playing.containsKey(p.getUUID())) died(p.level().getServer(), playing.get(p.getUUID()), p);
			return true;
		});
		// Rogue runs: the shop NPC opens the shop (only for players of that run, in its shop room).
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, lvl, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer p) || !entity.entityTags().contains(RogueBoons.SHOP_TAG)) return net.minecraft.world.InteractionResult.PASS;
			if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return net.minecraft.world.InteractionResult.SUCCESS;
			Run r = playing.get(p.getUUID());
			if (r != null && r.rogue != null && "shop".equals(r.rogue.type) && !r.out.contains(p.getUUID())) RogueBoons.shop(r, p, null);
			return net.minecraft.world.InteractionResult.SUCCESS;
		});
		// Rogue runs: a foe of the room down pays coins to everyone still in.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer || !DungeonRuntime.dungeonWorld(entity.level())) return;
			for (String t : entity.entityTags()) {
				if (!t.startsWith("ncc_rogue_") || entity.entityTags().contains(eu.explorerseden.nicecontrolcenter.games.bosses.BossFight.ASSET)) continue;
				try {
					Run r = slots.get(Integer.parseInt(t.substring("ncc_rogue_".length())));
					if (r != null && r.rogue != null && r.state == State.RUNNING) RogueBoons.killed(entity.level().getServer(), r, entity.entityTags().contains(r.bossTag()));
					return;
				} catch (NumberFormatException e) {
					// Another rogue tag (scaled, labels): look on.
				}
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> {
			if (!alive) respawned(now.level().getServer(), now);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.player;
			Run r = playing.get(p.getUUID());
			if (r != null) out(server, r, p, null, true);
			else if (inDungeonDimension(p)) sendBack(server, p);
			onPad.remove(p.getUUID());
		});
		// Logged in inside the dungeon dimension without a run (a crash, a removed run): out at once.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
			ServerPlayer p = handler.player;
			if (inDungeonDimension(p) && !playing.containsKey(p.getUUID())) sendBack(server, p);
		}));
	}

	/** Reads the file again right now (not up to 5 s late): commands change what the panel saved last, not an older copy. */
	public static void reloadNow() {
		eu.explorerseden.nicecontrolcenter.core.FileWatch.now(file());
		eu.explorerseden.nicecontrolcenter.core.FileWatch.now(rogueFile());
		reload();
	}

	/** Both files' times together: a change to either reads both again. */
	private static long filesModified() throws IOException {
		long rogue = java.nio.file.Files.exists(rogueFile()) ? eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(rogueFile()) : 0;
		return eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file()) * 31 + rogue;
	}

	public static void reload() {
		long now;
		try {
			now = filesModified();
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		boolean split = !Dungeons.hasRogueFile(rogueFile());
		settings = Dungeons.readAll(file(), rogueFile());
		// Before 1.110 rogue runs were kept with the dungeons: they get their own file now.
		if (split && (java.nio.file.Files.exists(file()) || !settings.dungeons(true).isEmpty())) {
			saveSettings();
			NiceControlCenter.LOGGER.info("Dungeons: rogue runs have their own file now ({} run(s), {} room(s), {} pad(s) moved to {})", settings.dungeons(true).size(),
					settings.rooms(true).size(), settings.pads(true).size(), Dungeons.ROGUE_FILE);
			for (Dungeon d : settings.dungeons(true)) {
				List<String> groups = new ArrayList<>(d.roomGroups.values());
				groups.add(d.startGroup);
				for (String g : groups) {
					if (g.isEmpty() || !settings.roomsIn(g, true).isEmpty() || settings.roomsIn(g, false).isEmpty()) continue;
					NiceControlCenter.LOGGER.warn("Dungeons: the rogue run {} uses the room group {}, whose rooms dungeons use too: they stay dungeon rooms; save rogue copies with /rogue room save", d.name, g);
				}
			}
		}
	}

	private static void saveSettings() {
		try {
			Dungeons.writeAll(file(), rogueFile(), settings);
			modified = filesModified();
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Dungeons: could not save the dungeons: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- for others

	/** In a dungeon run (a game area: no market, pets or inbox; the hub spawn takes over). */
	public static boolean inRun(ServerPlayer p) {
		return playing.containsKey(p.getUUID());
	}

	/** The run the player is in (also while watching), or null. */
	static Run runOf(ServerPlayer p) {
		return playing.get(p.getUUID());
	}

	/** Died in a run a moment ago or still in one: no grave. */
	public static boolean noGrave(ServerPlayer p) {
		return inRun(p) || noGraveUntil.getOrDefault(p.getUUID(), 0L) > System.currentTimeMillis();
	}

	/** Dying now brings them back inside (lives left, or they'll watch): the hub spawn stays out of it. */
	public static boolean respawnsInside(ServerPlayer p) {
		Run r = playing.get(p.getUUID());
		if (r == null || r.state != State.RUNNING || r.out.contains(p.getUUID())) return false;
		return r.lives.getOrDefault(p.getUUID(), 0) > 0 || (r.dungeon.outSpectate || r.rogue != null) && r.players.size() - r.out.size() > 1;
	}

	/**
	 * The dungeon dimension: only generated copies, so no tweak or tuning setting applies there (and the panel
	 * doesn't list it); it runs with its own light setup instead (PerfControl.dim).
	 */
	public static boolean dungeonWorld(net.minecraft.world.level.Level level) {
		return level != null && Dungeons.runDimension(level.dimension().identifier().toString());
	}

	/** In the dungeon dimension or the Rogue Runs one. */
	public static boolean inDungeonDimension(ServerPlayer p) {
		return Dungeons.runDimension(p.level().dimension().identifier().toString());
	}

	/** The dungeons' dimension, or the Rogue Runs' one. */
	static ServerLevel level(MinecraftServer server, boolean rogue) {
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(Dungeons.dimension(rogue))));
	}

	/** For the panel: the runs going on and the best clears. */
	public static Map<String, Object> view() {
		List<Map<String, Object>> runs = new ArrayList<>();
		for (Run r : slots.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("slot", r.slot);
			m.put("dungeon", r.dungeon.name);
			m.put("state", r.state.name().toLowerCase(Locale.ROOT));
			List<Map<String, Object>> who = new ArrayList<>();
			for (UUID id : r.players) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("name", r.names.getOrDefault(id, "?"));
				p.put("lives", r.lives.getOrDefault(id, 0));
				p.put("out", r.out.contains(id));
				who.add(p);
			}
			m.put("players", who);
			m.put("millis", r.millis());
			if (r.rogue != null) m.put("depth", r.rogue.depth);
			runs.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runs", runs);
		out.put("scores", scores);
		return out;
	}

	/** From the panel: ends a run (everyone goes back). Returns an error or null. Server thread. */
	public static String close(MinecraftServer server, int slot) {
		Run r = slots.get(slot);
		if (r == null) return "That run is over already.";
		if (r.state == State.RUNNING) fail(server, r, "An admin closed this dungeon.");
		else if (r.state == State.LOADING || r.state == State.PASTING) fail(server, r, "An admin closed this dungeon.");
		return null;
	}

	// ---------------------------------------------------------------- the loop

	private static void tick(MinecraftServer server) {
		int t = server.getTickCount();
		if (t % 100 == 0) reload();
		for (Run r : List.copyOf(slots.values())) {
			try {
				step(server, r);
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.error("Dungeons: dungeon run in slot {} ({}) failed", r.slot, r.dungeon.name, e);
				fail(server, r, "Something went wrong with this dungeon; you're sent back.");
			}
		}
		if (t % 2 == 0 && (settings.enabled || settings.rogueEnabled)) pads(server);
		if (t % 20 == 0) queues(server);
		if (t % 20 == 5) strays(server);
		if (t % 20 == 0) padParticles(server);
		if (t % 10 == 0) outlines(server);
		if (t % 1200 == 0) noGraveUntil.values().removeIf(until -> until < System.currentTimeMillis());
	}

	private static void step(MinecraftServer server, Run r) {
		ServerLevel level = level(server, r.dungeon.rogue());
		if (level == null) return;
		switch (r.state) {
			case LOADING -> {
				if (!r.loading.isDone() || !r.roomLoading.isDone()) return;
				List<DungeonTemplates.Piece> pieces;
				try {
					pieces = r.loading.join();
					r.roomBlocks = r.roomLoading.join();
				} catch (RuntimeException e) {
					fail(server, r, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
					return;
				}
				r.toPaste = new ArrayDeque<>(pieces);
				r.roomsToPaste = new ArrayDeque<>(r.layout.rooms());
				r.total = pieces.size() + r.roomsToPaste.size();
				forceChunks(level, r, true);
				r.state = State.PASTING;
			}
			case PASTING -> {
				RandomSource rs = level.getRandom();
				// The dungeon's own pieces first, then its rooms on top (each room is one step).
				for (int i = 0; i < settings.piecesPerTick && !(r.toPaste.isEmpty() && r.roomsToPaste.isEmpty()); i++) {
					if (!r.toPaste.isEmpty()) {
						DungeonTemplates.paste(level, r.min, r.toPaste.poll(), rs);
					} else {
						DungeonRooms.Placed room = r.roomsToPaste.poll();
						DungeonTemplates.pasteRoom(level, r.min.offset(room.pos()), room.rotation(), r.roomBlocks.get(room.room().name), rs);
					}
				}
				int left = r.toPaste.size() + r.roomsToPaste.size();
				if (r.bar != null) r.bar.setProgress(r.total == 0 ? 1f : 1f - left / (float) r.total);
				if (left == 0) begin(server, level, r);
			}
			case RUNNING -> running(server, level, r);
			case ENDING -> {
				if (System.currentTimeMillis() < r.endAt) return;
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null && playing.get(id) == r) leaveRun(server, r, p);
					playing.remove(id);
				}
				r.players.clear();
				startWipe(r);
			}
			case WIPING -> {
				for (int i = 0; i < settings.piecesPerTick && !r.toWipe.isEmpty(); i++) {
					int[] at = r.toWipe.poll();
					DungeonTemplates.wipe(level, r.min, r.max, at[0], at[1]);
				}
				if (r.toWipe.isEmpty()) {
					forceChunks(level, r, false);
					slots.remove(r.slot);
					saveSlots();
				}
			}
		}
	}

	private static void running(MinecraftServer server, ServerLevel level, Run r) {
		int t = server.getTickCount();
		if (t % 5 != 0) return;
		for (UUID id : List.copyOf(r.players)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || r.out.contains(id) || !p.isAlive()) continue;
			if (p.level() != level || !inBox(r, p)) {
				out(server, r, p, "You left the dungeon: you're out of this run.", false);
				continue;
			}
			// A checkpoint someone reaches is where everyone comes back.
			for (BlockPos c : r.checkpoints) {
				if (!c.equals(r.checkpoint) && p.distanceToSqr(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5) < 4) {
					r.checkpoint = c;
					tellRun(server, r, Words.lit("✦ Checkpoint reached by " + p.getGameProfile().name()).withStyle(ChatFormatting.GOLD));
					level.playSound(null, c, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7f, 1.4f);
					if (r.reached.add(c)) rewardInside(server, r, "dungeonCheckpoint", r.reached.size(), false);
				}
			}
			if ("exit".equals(r.dungeon.clearOn)) {
				for (BlockPos e : r.exits) {
					if (p.distanceToSqr(e.getX() + 0.5, e.getY() + 0.5, e.getZ() + 0.5) < 2.25) {
						clear(server, r);
						return;
					}
				}
			}
			if (t % 10 == 0) hud(p, r);
		}
		watchers(server, r);
		if (r.bosses > 0 && t % 10 == 0) bosses(server, level, r);
		// Peaceful and neutral mobs in the copy fight too.
		if (t % 20 == 0) {
			AABB box = new AABB(r.min.getX(), r.min.getY(), r.min.getZ(), r.max.getX() + 1, r.max.getY() + 1, r.max.getZ() + 1);
			for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box, e -> e.isAlive())) {
				if (r.dungeon.peacefulFight || Aggro.hostile(m)) Aggro.make(m, 3f);
			}
		}
		if (r.state == State.RUNNING && r.rogue != null) RogueRuns.tick(server, level, r);
		if (r.state != State.RUNNING) return;
		if (r.players.isEmpty()) {
			if (r.millis() > 180_000) fail(server, r, null);
			return;
		}
		if (r.dungeon.timeLimit > 0) {
			List<ServerPlayer> in = new ArrayList<>();
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && !r.out.contains(id)) in.add(p);
			}
			Countdown.tick("dungeon:" + r.slot, r.dungeon.timeLimit * 60_000L - r.millis(), in);
		}
		if (r.dungeon.timeLimit > 0 && r.millis() > r.dungeon.timeLimit * 60_000L) {
			fail(server, r, "Time's up.");
			return;
		}
		if (r.players.stream().allMatch(r.out::contains)) fail(server, r, null);
	}

	/** Out players watch through a teammate who's still in (no flying around): kept on one, the next when they're out. */
	private static void watchers(MinecraftServer server, Run r) {
		List<ServerPlayer> in = new ArrayList<>();
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && !r.out.contains(id) && p.isAlive()) in.add(p);
		}
		Spectating.follow(server, r.watching.keySet(), in);
	}

	/** The boss bar follows the bosses' health; every boss dead clears a "boss" dungeon. */
	private static void bosses(MinecraftServer server, ServerLevel level, Run r) {
		AABB box = new AABB(r.min.getX() - 8, r.min.getY() - 32, r.min.getZ() - 8, r.max.getX() + 9, r.max.getY() + 33, r.max.getZ() + 9);
		List<LivingEntity> alive = level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e.entityTags().contains(r.bossTag()));
		if (r.bossHealth <= 0) {
			for (LivingEntity e : alive) r.bossHealth += e.getMaxHealth();
			r.bossTotal = alive.size();
		}
		// Each boss that went down since the last look (rewards "dungeonBoss", counted from the first).
		while (r.bossesDown < r.bossTotal - alive.size()) {
			r.bossesDown++;
			rewardInside(server, r, "dungeonBoss", r.bossesDown, false);
		}
		float now = 0;
		for (LivingEntity e : alive) now += e.getHealth();
		if (r.bar == null && !alive.isEmpty()) {
			r.bar = new ServerBossEvent(UUID.randomUUID(), alive.get(0).getDisplayName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
		}
		if (r.bar != null) {
			r.bar.setProgress(r.bossHealth <= 0 ? 0 : Math.max(0, Math.min(1, now / r.bossHealth)));
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && p.level() == level) r.bar.addPlayer(p);
			}
		}
		if (alive.isEmpty() && r.bossHealth > 0) {
			r.bosses = 0;
			if (r.bar != null) r.bar.removeAllPlayers();
			r.bar = null;
			rewardInside(server, r, "dungeonBosses", System.currentTimeMillis() - r.startedAt, true);
			if ("boss".equals(r.dungeon.clearOn)) clear(server, r);
			else tellRun(server, r, Words.lit("The boss is down!").withStyle(ChatFormatting.GOLD));
		}
	}

	/** A Rewards trigger for everyone of the run who's still in (not out, still online). */
	static void rewardInside(MinecraftServer server, Run r, String trigger, long score, boolean timed) {
		for (UUID id : List.copyOf(r.players)) {
			if (r.out.contains(id)) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) RewardsControl.fireGame(server, trigger, p, r.dungeon.name, score, timed);
		}
	}

	private static boolean inBox(Run r, ServerPlayer p) {
		return p.getX() >= r.min.getX() - 3 && p.getX() <= r.max.getX() + 4 && p.getZ() >= r.min.getZ() - 3 && p.getZ() <= r.max.getZ() + 4
				&& p.getY() >= r.min.getY() - 24 && p.getY() <= r.max.getY() + 24;
	}

	private static void hud(ServerPlayer p, Run r) {
		long left = r.players.size() - r.out.size();
		String limit = r.dungeon.timeLimit > 0 ? " / " + GameRuntime.clock(r.dungeon.timeLimit * 60_000L) : "";
		int lives = r.lives.getOrDefault(p.getUUID(), 0);
		p.sendOverlayMessage(Words.lit(r.dungeon.name + (r.rogue != null ? RogueRuns.hud(r, p) : "") + " · " + GameRuntime.clock(r.millis()) + limit
				+ (r.dungeon.lives > 0 ? " · " + "❤".repeat(Math.min(lives, 10)) + (lives == 0 ? "last life" : "") : "")
				+ (r.players.size() > 1 ? " · " + left + " in" : "")).withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	static void tellRun(MinecraftServer server, Run r, Component text) {
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) p.sendSystemMessage(text);
		}
	}

	// ---------------------------------------------------------------- pads and starting

	private static void pads(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Dungeons.Pad pad = padUnder(p);
			if (pad != null && !(pad.rogue ? settings.rogueEnabled : settings.enabled)) pad = null;
			if (pad == null || playing.containsKey(p.getUUID()) || queued(p.getUUID()) || p.isSpectator() || cooldown.getOrDefault(p.getUUID(), 0L) > now || Portals.ignored(p, true)) {
				onPad.remove(p.getUUID());
				continue;
			}
			if (!Parties.isLeader(p.getUUID())) {
				if (onPad.merge(p.getUUID(), 2, Integer::sum) % 40 == 2) p.sendOverlayMessage(Words.lit("Your party leader starts the " + (pad.rogue ? "rogue run" : "dungeon") + " from the pad").withStyle(ChatFormatting.GRAY));
				continue;
			}
			List<ServerPlayer> party = partyNear(server, p, pad);
			int ticks = onPad.merge(p.getUUID(), 2, Integer::sum);
			if (ticks < PAD_TICKS) {
				if (ticks % 20 == 2) {
					String who = party.size() == 1 ? "" : " with " + String.join(", ", party.subList(1, party.size()).stream().map(x -> x.getGameProfile().name()).toList());
					p.sendOverlayMessage(Words.lit((pad.rogue ? "Rogue Run (" : "Dungeon (") + pad.pool + ")" + who + " in " + ((PAD_TICKS - ticks) / 20 + 1) + "… step off to cancel").withStyle(ChatFormatting.LIGHT_PURPLE));
					p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
				}
				continue;
			}
			onPad.remove(p.getUUID());
			cooldown.put(p.getUUID(), now + GameRuntime.WAIT_AFTER);
			List<UUID> missing = new ArrayList<>(Parties.members(p.getUUID()));
			party.forEach(x -> missing.remove(x.getUUID()));
			// More than any dungeon of the pool takes: the party splits into groups, each in a copy of its own.
			int most = poolMax(pad.pool, pad.rogue);
			String key = queueKey(pad);
			if (most > 0 && party.size() > most) {
				List<List<ServerPlayer>> groups = new ArrayList<>();
				for (int i = 0; i < party.size(); i += most) groups.add(new ArrayList<>(party.subList(i, Math.min(party.size(), i + most))));
				for (ServerPlayer m : party) {
					m.sendSystemMessage(Words.lit("[Party] " + (pad.rogue ? "These rogue runs" : "These dungeons") + " take " + most + " at most: you go in " + groups.size() + " groups ("
							+ String.join(" / ", groups.stream().map(g -> String.join(", ", g.stream().map(x -> x.getGameProfile().name()).toList())).toList()) + ").").withStyle(ChatFormatting.LIGHT_PURPLE));
				}
				Games.Pos wait = settings.waiting(pad.rogue).get(pad.pool);
				for (List<ServerPlayer> g : groups) {
					if (wait != null && (freeSlot() < 0 || !queues.getOrDefault(key, new java.util.ArrayDeque<>()).isEmpty())) {
						queue(server, key, wait, g);
						continue;
					}
					String err = start(server, g, pad.pool, pad.rogue);
					if (err != null) for (ServerPlayer m : g) m.sendSystemMessage(Words.lit(err + " Try again once a dungeon is free.").withStyle(ChatFormatting.RED));
				}
				continue;
			}
			// Every copy in use (or others waiting already) and the pool has a waiting area: the party queues there.
			Games.Pos area = settings.waiting(pad.rogue).get(pad.pool);
			if (area != null && (freeSlot() < 0 || !queues.getOrDefault(key, new java.util.ArrayDeque<>()).isEmpty())) {
				queue(server, key, area, party);
				continue;
			}
			String err = start(server, party, pad.pool, pad.rogue);
			if (err != null) {
				p.sendSystemMessage(Words.lit(err).withStyle(ChatFormatting.RED));
			} else if (!missing.isEmpty()) {
				for (UUID id : missing) {
					ServerPlayer m = server.getPlayerList().getPlayer(id);
					if (m != null) m.sendSystemMessage(Words.lit("Your party went into a " + (pad.rogue ? "rogue run" : "dungeon") + " without you (be within " + (int) PARTY_RANGE + " blocks of the pad).").withStyle(ChatFormatting.GRAY));
				}
			}
		}
	}

	// ---------------------------------------------------------------- the queue (pools with a waiting area)

	/** pool → parties waiting (leader first), oldest first; rogue run pools as "rogue:<pool>". */
	private static final Map<String, java.util.Deque<List<UUID>>> queues = new HashMap<>();

	private static String queueKey(Dungeons.Pad pad) {
		return (pad.rogue ? "rogue:" : "") + pad.pool;
	}

	private static int freeSlot() {
		for (int i = 0; i < settings.maxSlots; i++) if (!slots.containsKey(i)) return i;
		return -1;
	}

	private static boolean queued(UUID id) {
		for (var q : queues.values()) for (List<UUID> party : q) if (party.contains(id)) return true;
		return false;
	}

	private static void queue(MinecraftServer server, String pool, Games.Pos area, List<ServerPlayer> party) {
		ServerLevel level = GameRuntime.levelOf(server, area.dim);
		if (level == null) return;
		List<UUID> ids = new ArrayList<>();
		for (ServerPlayer m : party) {
			ids.add(m.getUUID());
			m.teleportTo(level, area.x, area.y, area.z, Set.of(), area.yaw, 0, true);
			m.resetFallDistance();
		}
		var q = queues.computeIfAbsent(pool, k -> new java.util.ArrayDeque<>());
		q.add(ids);
		for (ServerPlayer m : party) {
			m.sendSystemMessage(Words.lit("Every dungeon copy is in use: your party is " + ordinal(q.size()) + " in the queue. Wait here; walking away leaves it.").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		NiceControlCenter.LOGGER.info("Dungeons: {} queued for the {} pool ({} waiting)", String.join(", ", party.stream().map(x -> x.getGameProfile().name()).toList()), pool, q.size());
	}

	/** Every second: players who logged out drop out of the queue; the first party starts once a copy is free. */
	private static void queues(MinecraftServer server) {
		for (Map.Entry<String, java.util.Deque<List<UUID>>> e : queues.entrySet()) {
			boolean rogue = e.getKey().startsWith("rogue:");
			String pool = rogue ? e.getKey().substring(6) : e.getKey();
			Games.Pos area = settings.waiting(rogue).get(pool);
			for (List<UUID> party : e.getValue()) {
				party.removeIf(id -> {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					// Only logging out drops them (or the waiting area being removed); walking out of it doesn't (game areas are closed off).
					boolean gone = p == null || area == null;
					if (gone && p != null) p.sendSystemMessage(Words.lit("The waiting area is gone: out of the dungeon queue.").withStyle(ChatFormatting.GRAY));
					return gone;
				});
			}
			e.getValue().removeIf(List::isEmpty);
			int place = 1;
			for (List<UUID> party : e.getValue()) {
				for (UUID id : party) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null) p.sendOverlayMessage(Words.lit((rogue ? "Rogue Runs queue: " : "Dungeon queue: ") + ordinal(place) + " in line").withStyle(ChatFormatting.LIGHT_PURPLE));
				}
				place++;
			}
			if (e.getValue().isEmpty() || freeSlot() < 0 || !(rogue ? settings.rogueEnabled : settings.enabled)) continue;
			List<UUID> first = e.getValue().poll();
			List<ServerPlayer> party = new ArrayList<>();
			for (UUID id : first) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && !playing.containsKey(id)) party.add(p);
			}
			if (party.isEmpty()) continue;
			String err = start(server, party, pool, rogue);
			if (err != null) for (ServerPlayer p : party) p.sendSystemMessage(Words.lit(err).withStyle(ChatFormatting.RED));
		}
		queues.values().removeIf(java.util.Deque::isEmpty);
	}

	private static String ordinal(int n) {
		return n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; });
	}

	/** The leader and the party members close to the pad who can come along. */
	private static List<ServerPlayer> partyNear(MinecraftServer server, ServerPlayer leader, Dungeons.Pad pad) {
		List<ServerPlayer> out = new ArrayList<>();
		out.add(leader);
		long now = System.currentTimeMillis();
		for (UUID id : Parties.members(leader.getUUID())) {
			if (id.equals(leader.getUUID())) continue;
			ServerPlayer m = server.getPlayerList().getPlayer(id);
			if (m == null || m.level() != leader.level() || playing.containsKey(id) || m.isSpectator() || cooldown.getOrDefault(id, 0L) > now || Portals.ignored(m, false)) continue;
			if (m.distanceToSqr(pad.pos.x, pad.pos.y, pad.pos.z) <= PARTY_RANGE * PARTY_RANGE) out.add(m);
		}
		return out;
	}

	private static Dungeons.Pad padUnder(ServerPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		for (Dungeons.Pad pad : settings.pads) {
			if (!pad.pos.dim.equals(dim)) continue;
			double dx = p.getX() - pad.pos.x, dz = p.getZ() - pad.pos.z, dy = p.getY() - pad.pos.y;
			if (dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5) return pad;
		}
		return null;
	}

	private static void padParticles(MinecraftServer server) {
		for (Dungeons.Pad pad : settings.pads) {
			ServerLevel level = GameRuntime.levelOf(server, pad.pos.dim);
			if (level == null || level.getNearestPlayer(pad.pos.x, pad.pos.y, pad.pos.z, 24, false) == null) continue;
			for (int i = 0; i < 8; i++) {
				double a = i * Math.PI / 4 + server.getTickCount() / 20.0 * 0.4;
				level.sendParticles(ParticleTypes.WITCH, pad.pos.x + Math.cos(a) * 0.8, pad.pos.y + 0.15, pad.pos.z + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
			}
		}
	}

	/** The most players any ready dungeon (or rogue run) of the pool takes (0 = none ready). */
	private static int poolMax(String pool, boolean rogue) {
		int most = 0;
		for (Dungeon d : settings.dungeons) if (d.rogue() == rogue && d.enabled && ready(d) && d.pool.equals(pool)) most = Math.max(most, d.maxParty);
		return most;
	}

	/** Players can get it: a saved dungeon with a start marker, or a rogue entry with a first room. */
	static boolean ready(Dungeon d) {
		return d.rogue() ? RogueRuns.ready(settings, d) : d.saved() && d.hasMarker("start");
	}

	/** Starts a run for these players with a random dungeon (or rogue run) of the pool; returns why not, or null. */
	static String start(MinecraftServer server, List<ServerPlayer> party, String pool, boolean rogue) {
		if (!(rogue ? settings.rogueEnabled : settings.enabled)) return (rogue ? "Rogue Runs" : "Dungeons") + " are closed right now.";
		int size = party.size();
		List<Dungeon> fits = new ArrayList<>();
		for (Dungeon d : settings.dungeons) {
			if (d.rogue() == rogue && d.enabled && ready(d) && d.pool.equals(pool) && size >= d.minParty && size <= d.maxParty) fits.add(d);
		}
		if (fits.isEmpty()) return "No " + (rogue ? "rogue run" : "dungeon") + " in the " + pool + " pool is ready for " + (size == 1 ? "one player" : size + " players") + " yet.";
		String last = lastPlayed.get(party.get(0).getUUID());
		if (fits.size() > 1 && last != null) fits.removeIf(d -> d.id.equals(last));
		return start(server, party, fits.get(random.nextInt(fits.size())));
	}

	/** A run of this dungeon (no players: a copy that stands for a minute, for trying it from the console). */
	static String start(MinecraftServer server, List<ServerPlayer> party, Dungeon d) {
		ServerLevel level = level(server, d.rogue());
		if (level == null) return "The " + (d.rogue() ? "Rogue Runs" : "dungeon") + " dimension isn't loaded; it appears after the server restarts with this version.";
		if (!d.rogue() && !d.saved()) return d.name + " has no saved blocks yet: /dungeon save " + d.name;
		if (!d.rogue() && !d.hasMarker("start")) return d.name + " needs a start marker (a data structure block with the text start).";
		int slot = -1;
		for (int i = 0; i < settings.maxSlots; i++) {
			if (!slots.containsKey(i)) {
				slot = i;
				break;
			}
		}
		if (slot < 0) return "Every dungeon copy is in use right now; try again in a moment.";
		Run r = new Run(slot, d);
		int x = (slot + 1) * settings.slotSpacing;
		r.min = new BlockPos(x, BASE_Y, 0);
		if (d.rogue()) {
			// A room chain: two room spots in the slot, the first room read on the worker.
			r.max = RogueRuns.max(r.min);
			r.loading = CompletableFuture.completedFuture(List.of());
			r.layout = new DungeonRooms.Layout(List.of(), List.of(), List.of());
			String err = RogueRuns.prepare(server, settings, r);
			if (err != null) return err;
		} else {
			r.max = r.min.offset(d.sizeX - 1, d.sizeY - 1, d.sizeZ - 1);
			r.loading = DungeonTemplates.load(server, d);
			r.layout = DungeonRooms.plan(d, settings, random);
			Set<String> roomNames = new LinkedHashSet<>();
			for (DungeonRooms.Placed pl : r.layout.rooms()) roomNames.add(pl.room().name);
			r.roomLoading = DungeonTemplates.onWorker(() -> {
				Map<String, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> out = new HashMap<>();
				for (String n : roomNames) out.put(n, DungeonTemplates.readRoom(server, n));
				return out;
			});
		}
		r.bar = new ServerBossEvent(UUID.randomUUID(), Words.lit(fill(d.prepareMessage.isEmpty() ? "Preparing your dungeon…" : d.prepareMessage, r, null)),
				BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
		r.bar.setProgress(0);
		for (ServerPlayer p : party) {
			r.players.add(p.getUUID());
			r.names.put(p.getUUID(), p.getGameProfile().name());
			// (Rogue runs: out is out.)
			r.lives.put(p.getUUID(), d.rogue() ? 0 : d.lives);
			playing.put(p.getUUID(), r);
			lastPlayed.put(p.getUUID(), d.id);
			r.bar.addPlayer(p);
		}
		slots.put(slot, r);
		saveSlots();
		return null;
	}

	/** The copy is ready: markers become air (spawning what they say), everyone goes to the start. */
	private static void begin(MinecraftServer server, ServerLevel level, Run r) {
		if (r.rogue != null) RogueRuns.enterFirst(server, level, r);
		List<Dungeons.Marker> markers = new ArrayList<>(r.dungeon.markers);
		markers.addAll(r.layout.markers());
		for (Dungeons.Marker m : markers) {
			BlockPos at = r.min.offset(m.x, m.y, m.z);
			level.setBlock(at, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS);
			switch (m.type) {
				case "start" -> {
					if (r.start == null) r.start = at;
				}
				case "exit" -> r.exits.add(at);
				case "checkpoint" -> r.checkpoints.add(at);
				case "mob" -> spawn(server, r, at, m.arg, false);
				case "boss" -> {
					spawn(server, r, at, m.arg, true);
					r.bosses++;
				}
				default -> {
				}
			}
		}
		// Connectors (jigsaw blocks) become what they turn into: an open doorway, or a wall where no room came.
		var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		for (DungeonRooms.Joint j : r.layout.joints()) {
			net.minecraft.world.level.block.state.BlockState state;
			try {
				state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(blocks, j.finalState(), false).blockState();
			} catch (CommandSyntaxException e) {
				state = Blocks.AIR.defaultBlockState();
			}
			level.setBlock(r.min.offset(j.pos()), state, 2);
		}
		if (r.bar != null) r.bar.removeAllPlayers();
		r.bar = null;
		r.state = State.RUNNING;
		r.startedAt = System.currentTimeMillis();
		if (r.players.isEmpty() && r.rogue != null) {
			// A rogue run tried from the console: it goes on (/rogue pick <slot> <door>) for 3 minutes.
			NiceControlCenter.LOGGER.info("Dungeons: rogue run {} tried in slot {} (first room {} at {}); ends in 3 minutes", r.dungeon.name, r.slot, r.rogue.room.name, r.start.toShortString());
			return;
		}
		if (r.players.isEmpty()) {
			// Tried from the console: it stands for a minute, then it's wiped.
			NiceControlCenter.LOGGER.info("Dungeons: dungeon {} copied into slot {} at {} (start {}); wiped in a minute", r.dungeon.name, r.slot, r.min.toShortString(), r.start.toShortString());
			for (DungeonRooms.Placed pl : r.layout.rooms()) {
				NiceControlCenter.LOGGER.info("Dungeons:   room {} (level {}) at {}, turned {}", pl.room().name, pl.level(), r.min.offset(pl.pos()).toShortString(), pl.rotation().getSerializedName());
			}
			r.state = State.ENDING;
			r.endAt = System.currentTimeMillis() + 60_000;
			return;
		}
		for (UUID id : List.copyOf(r.players)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				r.out.add(id);
				playing.remove(id);
				continue;
			}
			if (r.kit()) {
				GameClaims.stashFor(p, "dungeon");
				// (Kept: after a death the same kit comes back, no rerolling by dying.)
				List<String> kit = GameClaims.pickKit(r.dungeon.kit, r.dungeon.moreKits);
				r.kits.put(p.getUUID(), kit);
				GameClaims.giveKit(p, kit, "dungeon");
			}
			p.teleportTo(level, r.start.getX() + 0.5, r.start.getY(), r.start.getZ() + 0.5, Set.of(), p.getYRot(), 0, true);
			p.resetFallDistance();
			Cleanup.refresh(p);
			if (r.rogue != null) RogueBoons.start(r, p);
			GameClaims.gameMode(p, false);
			p.sendSystemMessage(Words.lit(fill(r.dungeon.enterMessage.isEmpty() ? (r.rogue != null ? "▶ {dungeon}: clear room after room. One life, so go as deep as you can!" : "▶ {dungeon}: find the way out!") : r.dungeon.enterMessage, r, p)).withStyle(ChatFormatting.LIGHT_PURPLE));
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Words.lit(r.dungeon.name).withStyle(ChatFormatting.LIGHT_PURPLE)));
			commands(server, r.dungeon.enterCommands, r, p);
		}
		level.playSound(null, r.start, SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1f, 0.8f);
	}

	/**
	 * A mob or boss marker: "zombie", "minecraft:skeleton x3", "husk x2 {CustomName:'Guard'}". Summoned like the
	 * /summon command (so its data works the same everywhere), kept from despawning; bosses get the run's tag.
	 */
	private static void spawn(MinecraftServer server, Run r, BlockPos at, String arg, boolean boss) {
		MobLines.Entry e = MobLines.parse(arg);
		if (e == null) return;
		// A Katters boss (katters:theron…): the whole fight; its bodies get the run's boss tag.
		if (eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.is(e.id())) {
			eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.spawn(server, e.id(), Dungeons.dimension(r.dungeon.rogue()), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, List.of(), boss ? r.bossTag() : null, !boss, p -> r.players.contains(p.getUUID()),
					new AABB(r.min.getX(), r.min.getY(), r.min.getZ(), r.max.getX() + 1, r.max.getY() + 1, r.max.getZ() + 1), 1);
			return;
		}
		int count = boss ? 1 : e.count();
		for (int i = 0; i < count; i++) {
			double ox = count > 1 ? (random.nextDouble() - 0.5) * 2 : 0, oz = count > 1 ? (random.nextDouble() - 0.5) * 2 : 0;
			MobLines.summon(server, Dungeons.dimension(r.dungeon.rogue()), at.getX() + 0.5 + ox, at.getY(), at.getZ() + 0.5 + oz, e, boss ? "Tags:[\"" + r.bossTag() + "\"]" : "");
		}
	}

	// ---------------------------------------------------------------- deaths

	/** Before the death: what they carry is put aside (own gear) or dealt with (kit); lives and watching decided. */
	private static void died(MinecraftServer server, Run r, ServerPlayer p) {
		noGraveUntil.put(p.getUUID(), System.currentTimeMillis() + 60_000);
		// Found items are kept only when a life brings them back in (not when they watch or are out).
		boolean keep = r.state == State.RUNNING && !r.out.contains(p.getUUID()) && r.lives.getOrDefault(p.getUUID(), 0) > 0;
		if (r.kit()) GameClaims.clearCarried(p, keep);
		else GameClaims.stashFor(p, "dungeon-death");
		if (r.state != State.RUNNING || r.out.contains(p.getUUID())) return;
		r.deaths++;
		boolean inside = respawnsInside(p);
		int lives = r.lives.getOrDefault(p.getUUID(), 0);
		if (lives > 0) {
			r.lives.put(p.getUUID(), lives - 1);
			r.respawnInside.add(p.getUUID());
			tellRun(server, r, Words.lit(p.getGameProfile().name() + " died" + (lives - 1 == 0 ? " (last life left)" : " (" + (lives - 1) + " lives left)")).withStyle(ChatFormatting.RED));
			return;
		}
		// Out: they watch (when others are still in) or go back once they've respawned.
		r.out.add(p.getUUID());
		cooldown.put(p.getUUID(), System.currentTimeMillis() + GameRuntime.WAIT_AFTER);
		commands(server, r.dungeon.exitCommands, r, p);
		if (inside) {
			r.respawnInside.add(p.getUUID());
			tellRun(server, r, Words.lit(p.getGameProfile().name() + " is out and watches.").withStyle(ChatFormatting.GRAY));
		} else {
			playing.remove(p.getUUID());
			afterRespawn.add(p.getUUID());
			afterRespawnRemove.put(p.getUUID(), r.dungeon.removePickups);
			p.sendSystemMessage(Words.lit("You're out of this run.").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void respawned(MinecraftServer server, ServerPlayer p) {
		boolean ours = afterRespawn.contains(p.getUUID()) || playing.containsKey(p.getUUID());
		try {
			respawnedInside(server, p);
		} finally {
			// Unless they went back in (given back there), what they found is gone (only for dungeon players).
			if (ours) GameClaims.forgetKept(p);
		}
	}

	private static void respawnedInside(MinecraftServer server, ServerPlayer p) {
		if (afterRespawn.remove(p.getUUID())) {
			// Out of the run: things back, and out of the dungeon dimension (the hub spawn may have done that already).
			GameClaims.restoreFor(p, Boolean.TRUE.equals(afterRespawnRemove.remove(p.getUUID())));
			if (inDungeonDimension(p)) sendBack(server, p);
			return;
		}
		Run r = playing.get(p.getUUID());
		if (r == null || !r.respawnInside.remove(p.getUUID())) return;
		ServerLevel level = level(server, r.dungeon.rogue());
		if (level == null || r.state != State.RUNNING) {
			leaveRun(server, r, p);
			playing.remove(p.getUUID());
			return;
		}
		BlockPos to = r.checkpoint != null ? r.checkpoint : r.start;
		p.teleportTo(level, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, Set.of(), p.getYRot(), 0, true);
		if (r.out.contains(p.getUUID())) {
			r.watching.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
			p.setGameMode(GameType.SPECTATOR);
			p.sendSystemMessage(Words.lit("You're out: you watch your party until the run ends.").withStyle(ChatFormatting.GRAY));
			watchers(server, r);
			return;
		}
		// Back in: their own gear (own) or the kit again.
		if (r.kit()) {
			List<String> kit = r.kits.get(p.getUUID());
			GameClaims.giveKit(p, kit != null ? kit : GameClaims.pickKit(r.dungeon.kit, r.dungeon.moreKits), "dungeon");
			GameClaims.giveBackKept(p);
		} else {
			GameClaims.restoreFor(p, false);
		}
	}

	// ---------------------------------------------------------------- ending

	/** A player is out (left, logged out, /dungeon leave); the run carries on for the others. */
	private static void out(MinecraftServer server, Run r, ServerPlayer p, String why, boolean loggedOut) {
		if (r.state == State.LOADING || r.state == State.PASTING) {
			// Not in yet: they simply don't come along.
			r.players.remove(p.getUUID());
			playing.remove(p.getUUID());
			if (r.bar != null) r.bar.removePlayer(p);
			if (r.players.isEmpty()) startWipe(r);
			return;
		}
		boolean wasOut = r.out.contains(p.getUUID());
		r.out.add(p.getUUID());
		r.respawnInside.remove(p.getUUID());
		if (!wasOut) {
			cooldown.put(p.getUUID(), System.currentTimeMillis() + GameRuntime.WAIT_AFTER);
			commands(server, r.dungeon.exitCommands, r, p);
		}
		if (why != null) p.sendSystemMessage(Words.lit(why).withStyle(ChatFormatting.GRAY));
		if (p.isAlive()) {
			leaveRun(server, r, p);
		} else {
			// Dead: done once they respawn.
			afterRespawn.add(p.getUUID());
			afterRespawnRemove.put(p.getUUID(), r.dungeon.removePickups);
		}
		playing.remove(p.getUUID());
	}

	/** Out of the run for good: their game mode and things back, and out of the dungeon dimension. */
	private static void leaveRun(MinecraftServer server, Run r, ServerPlayer p) {
		if (r.rogue != null) RogueBoons.strip(r, p);
		GameType mode = r.watching.remove(p.getUUID());
		if (mode != null) {
			p.setCamera(p);
			p.setGameMode(mode);
		}
		GameClaims.restoreFor(p, r.dungeon.removePickups);
		if (r.bar != null) r.bar.removePlayer(p);
		if (inDungeonDimension(p)) sendBack(server, p);
	}

	static void clear(MinecraftServer server, Run r) {
		if (r.state != State.RUNNING) return;
		r.cleared = true;
		long time = System.currentTimeMillis() - r.startedAt;
		if (r.rogue != null) {
			boolean record = rogueScore(r, time);
			end(server, r, (r.dungeon.clearedMessage.isEmpty() ? "■ {dungeon} won: {depth} rooms cleared in {time}!" : r.dungeon.clearedMessage) + (record ? " A new best!" : ""), ChatFormatting.GOLD);
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p == null) continue;
				RewardsControl.fireGame(server, "dungeonCleared", p, r.dungeon.name, time, true);
				RewardsControl.fireGame(server, "rogueEnd", p, r.dungeon.name, r.rogue.cleared, false);
			}
			return;
		}
		int size = r.players.size();
		Map<String, Dungeons.Best> board = scores.computeIfAbsent(r.dungeon.id, k -> new LinkedHashMap<>());
		Dungeons.Best best = board.computeIfAbsent(String.valueOf(size), k -> new Dungeons.Best());
		best.clears++;
		boolean record = best.millis == 0 || time < best.millis;
		if (record) {
			best.millis = time;
			best.names = new ArrayList<>(r.names.values());
			best.at = System.currentTimeMillis();
		}
		if (scoresFile != null) AsyncFiles.write(scoresFile, Json.GSON.toJson(scores));
		end(server, r, (r.dungeon.clearedMessage.isEmpty() ? "■ {dungeon} cleared in {time}!" : r.dungeon.clearedMessage) + (record ? " A new best for " + (size == 1 ? "one player" : size + " players") + "!" : ""), ChatFormatting.GOLD);
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) RewardsControl.fireGame(server, "dungeonCleared", p, r.dungeon.name, time, true);
		}
	}

	static void fail(MinecraftServer server, Run r, String why) {
		if (r.state == State.LOADING || r.state == State.PASTING) {
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && why != null) p.sendSystemMessage(Words.lit(why).withStyle(ChatFormatting.RED));
				playing.remove(id);
			}
			r.players.clear();
			startWipe(r);
			return;
		}
		if (r.state != State.RUNNING) return;
		long time = System.currentTimeMillis() - r.startedAt;
		if (r.rogue != null) {
			boolean record = !r.players.isEmpty() && rogueScore(r, time);
			String line = r.dungeon.failedMessage.isEmpty() ? "■ {dungeon}: the run is over after {depth} rooms ({time})." : r.dungeon.failedMessage;
			end(server, r, (why == null ? line : why + " " + line) + (record ? " A new best!" : ""), record ? ChatFormatting.GOLD : ChatFormatting.RED);
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) RewardsControl.fireGame(server, "rogueEnd", p, r.dungeon.name, r.rogue.cleared, false);
			}
			return;
		}
		String line = r.dungeon.failedMessage.isEmpty() ? "■ {dungeon}: the run is over." : r.dungeon.failedMessage;
		end(server, r, why == null ? line : why + " " + line, ChatFormatting.RED);
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) RewardsControl.fireGame(server, "dungeonFailed", p, r.dungeon.name, time, true);
		}
	}

	/** A rogue run's result on the board (party size → most rooms, then the faster time); true for a new best. */
	private static boolean rogueScore(Run r, long time) {
		int depth = r.rogue.cleared;
		if (depth <= 0) return false;
		int size = r.players.size();
		Map<String, Dungeons.Best> board = scores.computeIfAbsent(r.dungeon.id, k -> new LinkedHashMap<>());
		Dungeons.Best best = board.computeIfAbsent(String.valueOf(size), k -> new Dungeons.Best());
		best.clears++;
		boolean record = depth > best.depth || depth == best.depth && (best.millis == 0 || time < best.millis);
		if (record) {
			best.depth = depth;
			best.millis = time;
			best.names = new ArrayList<>(r.names.values());
			best.at = System.currentTimeMillis();
		}
		if (scoresFile != null) AsyncFiles.write(scoresFile, Json.GSON.toJson(scores));
		NiceControlCenter.LOGGER.info("Dungeons: rogue run {} ended after {} rooms ({}) for {}{}", r.dungeon.name, depth, GameRuntime.clock(time), String.join(", ", r.names.values()), record ? ", a new best" : "");
		return record;
	}

	/** Tells everyone, runs the exit commands for those still in, and sends them back after a few seconds. */
	private static void end(MinecraftServer server, Run r, String message, ChatFormatting color) {
		r.endAt = System.currentTimeMillis() + (r.cleared ? 8000 : 3000);
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			p.sendSystemMessage(Words.lit(fill(message, r, p)).withStyle(color));
			if (!r.out.contains(id)) {
				commands(server, r.dungeon.exitCommands, r, p);
				cooldown.put(id, System.currentTimeMillis() + GameRuntime.WAIT_AFTER);
			}
			if (r.cleared) p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1f);
		}
		r.state = State.ENDING;
		if (r.bar != null) r.bar.removeAllPlayers();
		r.bar = null;
	}

	private static void startWipe(Run r) {
		if (r.bar != null) r.bar.removeAllPlayers();
		r.bar = null;
		r.state = State.WIPING;
		r.toWipe = new ArrayDeque<>();
		for (int dx = 0; dx <= r.max.getX() - r.min.getX(); dx += Dungeons.PIECE) {
			for (int dz = 0; dz <= r.max.getZ() - r.min.getZ(); dz += Dungeons.PIECE) r.toWipe.add(new int[] {dx, dz});
		}
	}

	/** Out of the dungeon dimension: the hub spawn, else where they'd respawn. */
	/**
	 * Nobody stays in the dungeon dimension outside a run (a datapack teleport, a portal horn, a /tp): sent back.
	 * Admins building or checking (creative, spectator, claim bypass) are left alone.
	 */
	private static void strays(MinecraftServer server) {
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!p.isAlive() || !inDungeonDimension(p) || playing.containsKey(p.getUUID()) || p.isCreative() || p.isSpectator()
					|| eu.explorerseden.nicecontrolcenter.claims.ClaimTool.bypassing(p)) continue;
			NiceControlCenter.LOGGER.info("Dungeons: {} was in the dungeon dimension outside a run: sent back", p.getGameProfile().name());
			p.sendSystemMessage(Words.lit("The dungeons are only for runs: you're sent back.").withStyle(ChatFormatting.GRAY));
			sendBack(server, p);
		}
	}

	static void sendBack(MinecraftServer server, ServerPlayer p) {
		if (GameRuntime.toHub(server, p)) return;
		var to = p.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
		p.teleportTo(to.newLevel(), to.position().x, to.position().y, to.position().z, Set.of(), to.yRot(), to.xRot(), true);
	}

	private static void forceChunks(ServerLevel level, Run r, boolean on) {
		for (int cx = r.min.getX() >> 4; cx <= r.max.getX() >> 4; cx++) {
			for (int cz = r.min.getZ() >> 4; cz <= r.max.getZ() >> 4; cz++) level.setChunkForced(cx, cz, on);
		}
	}

	private static String fill(String text, Run r, ServerPlayer p) {
		String party = String.join(", ", r.names.values());
		text = Games.math(text, Map.of("seconds", String.valueOf(r.millis() / 1000), "deaths", String.valueOf(r.deaths))).replace("{seconds}", String.valueOf(r.millis() / 1000));
		return text.replace("{dungeon}", r.dungeon.name).replace("{party}", party).replace("{time}", GameRuntime.clock(r.millis()))
				.replace("{deaths}", String.valueOf(r.deaths)).replace("{depth}", String.valueOf(r.rogue == null ? 0 : r.rogue.cleared))
				.replace("{player}", p == null ? party : p.getGameProfile().name());
	}

	private static void commands(MinecraftServer server, List<String> lines, Run r, ServerPlayer p) {
		for (String line : lines) {
			String cmd = fill(line.strip().replaceFirst("^/", ""), r, p);
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Dungeon");
		}
	}

	// ---------------------------------------------------------------- slots on disk

	/** The slots in use, so a crash or restart never leaves a copy standing. Written on the file worker. */
	private static void saveSlots() {
		if (slotsFile == null) return;
		List<int[]> boxes = new ArrayList<>();
		// (The 8th number: 1 = in the Rogue Runs dimension.)
		for (Run r : slots.values()) boxes.add(new int[] {r.slot, r.min.getX(), r.min.getY(), r.min.getZ(), r.max.getX(), r.max.getY(), r.max.getZ(), r.dungeon.rogue() ? 1 : 0});
		AsyncFiles.write(slotsFile, Json.GSON.toJson(boxes));
	}

	private static void wipeLeftovers(MinecraftServer server) {
		slots.clear();
		playing.clear();
		try {
			if (slotsFile == null || !Files.exists(slotsFile)) return;
			int[][] boxes = Json.GSON.fromJson(Files.readString(slotsFile), int[][].class);
			if (boxes == null) return;
			for (int[] b : boxes) {
				if (b.length < 7) continue;
				Dungeon placeholder = new Dungeon();
				placeholder.name = "leftover";
				if (b.length > 7 && b[7] == 1) placeholder.mode = "rogue";
				Run r = new Run(b[0], placeholder);
				r.min = new BlockPos(b[1], b[2], b[3]);
				r.max = new BlockPos(b[4], b[5], b[6]);
				startWipe(r);
				ServerLevel level = level(server, placeholder.rogue());
				if (level != null) forceChunks(level, r, true);
				slots.put(r.slot, r);
			}
			if (!slots.isEmpty()) NiceControlCenter.LOGGER.info("Dungeons: wiping {} dungeon cop{} left from before the restart", slots.size(), slots.size() == 1 ? "y" : "ies");
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Dungeons: could not read the dungeon slots: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- building (admins)

	private static void outlines(MinecraftServer server) {
		long now = System.currentTimeMillis();
		selections.entrySet().removeIf(e -> e.getValue().until < now);
		for (Map.Entry<UUID, Selection> e : selections.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Selection s = e.getValue();
			if (p == null || s.a == null || !p.level().dimension().identifier().toString().equals(s.dim)) continue;
			BlockPos b = s.b == null ? p.blockPosition() : s.b;
			int x0 = Math.min(s.a.getX(), b.getX()), y0 = Math.min(s.a.getY(), b.getY()), z0 = Math.min(s.a.getZ(), b.getZ());
			int x1 = Math.max(s.a.getX(), b.getX()) + 1, y1 = Math.max(s.a.getY(), b.getY()) + 1, z1 = Math.max(s.a.getZ(), b.getZ()) + 1;
			DustParticleOptions dust = new DustParticleOptions(0xB45CFF, 1.2f);
			ServerLevel level = (ServerLevel) p.level();
			// The twelve edges, a particle every couple of blocks (only the admin sees them).
			int step = Math.max(1, Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0)) / 40);
			for (int x = x0; x <= x1; x += step) for (int[] yz : new int[][] {{y0, z0}, {y0, z1}, {y1, z0}, {y1, z1}}) level.sendParticles(p, dust, true, false, x, yz[0], yz[1], 1, 0, 0, 0, 0);
			for (int y = y0; y <= y1; y += step) for (int[] xz : new int[][] {{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) level.sendParticles(p, dust, true, false, xz[0], y, xz[1], 1, 0, 0, 0, 0);
			for (int z = z0; z <= z1; z += step) for (int[] xy : new int[][] {{x0, y0}, {x0, y1}, {x1, y0}, {x1, y1}}) level.sendParticles(p, dust, true, false, xy[0], xy[1], z, 1, 0, 0, 0, 0);
		}
	}

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		tree(d, false);
		tree(d, true);
	}

	/** "dungeon" or "rogue run" for messages. */
	private static String what(boolean rogue) {
		return rogue ? "rogue run" : "dungeon";
	}

	/**
	 * /dungeon (built dungeons) and /rogue (rogue runs): the same build, room, pad and kit commands, each only seeing
	 * its own kind; dungeons add their box and room spots, rogue runs their boons, shop and unlocks.
	 */
	private static void tree(CommandDispatcher<CommandSourceStack> d, boolean rogue) {
		String cmd = rogue ? "/rogue" : "/dungeon";
		var admin = Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS);
		var dungeonArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("name", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.dungeons(rogue).stream().map(x -> x.name), b));
		var roomArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("name", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.rooms(rogue).stream().map(x -> x.name), b));
		var groupArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("group", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.groups(rogue).stream().map(g -> g.name), b));
		var blockPos = (java.util.function.Function<String, com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.coordinates.Coordinates>>) n -> Commands.argument(n, net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos());
		var poolArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("pool", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.pools(rogue), b));
		java.util.function.Predicate<CommandSourceStack> use = Everyone.unless(rogue ? "nicecontrolcenter.rogue.use" : "nicecontrolcenter.dungeons.use");
		var root = Commands.literal(rogue ? "rogue" : "dungeon").requires(src -> {
			reloadNow();
			return use.test(src);
		});
		root.then(Commands.literal("leave").executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayerOrException();
			Run r = playing.get(p.getUUID());
			if (r == null) return fail(ctx, "You're not in a " + what(rogue) + ".");
			out(ctx.getSource().getServer(), r, p, "You left the " + what(r.dungeon.rogue()) + ".", false);
			return 1;
		}));
		if (rogue) {
			root.then(Commands.literal("boon").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				Run r = playing.get(p.getUUID());
				if (r == null || r.rogue == null || !r.rogue.offers.containsKey(p.getUUID())) return fail(ctx, "You have no boon to choose right now.");
				RogueBoons.menu(r, p, null);
				return 1;
			}));
			root.then(Commands.literal("shop").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				Run r = playing.get(p.getUUID());
				if (r == null || r.rogue == null || !"shop".equals(r.rogue.type) || r.out.contains(p.getUUID())) return fail(ctx, "The shop is only in a rogue run's shop room.");
				RogueBoons.shop(r, p, null);
				return 1;
			}));
			root.then(Commands.literal("unlocks").executes(ctx -> {
				RogueBoons.unlocksMenu(ctx.getSource().getPlayerOrException(), null);
				return 1;
			}));
		}
		root.then(Commands.literal("list").executes(ctx -> {
			StringBuilder b = new StringBuilder();
			for (String pool : settings.pools(rogue)) {
				List<String> names = settings.dungeons(rogue).stream().filter(x -> x.pool.equals(pool)).map(x -> x.name + (x.enabled && ready(x) ? "" : " (off)")).toList();
				long pads = settings.pads(rogue).stream().filter(x -> x.pool.equals(pool)).count();
				b.append("\n").append(pool).append(": ").append(names.isEmpty() ? "no " + what(rogue) + "s" : String.join(", ", names)).append(" · ").append(pads).append(pads == 1 ? " pad" : " pads");
			}
			ctx.getSource().sendSuccess(() -> Words.lit((rogue ? "Rogue Runs pools:" : "Dungeon pools:") + (b.isEmpty() ? " none yet." : b)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
			return 1;
		}));
		root.then(Commands.literal("top").then(dungeonArg.get().executes(ctx -> {
			Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
			if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
			Map<String, Dungeons.Best> board = scores.getOrDefault(x.id, Map.of());
			StringBuilder b = new StringBuilder(x.name + (board.isEmpty() ? (rogue ? ": nobody got far yet." : ": not cleared yet.") : ":"));
			for (int n = 1; n <= 4; n++) {
				Dungeons.Best best = board.get(String.valueOf(n));
				if (best != null) b.append("\n").append(n == 1 ? "Solo" : n + " players").append(": ").append(rogue ? best.depth + " rooms in " : "").append(GameRuntime.clock(best.millis)).append(" · ").append(String.join(", ", best.names));
			}
			ctx.getSource().sendSuccess(() -> Words.lit(b.toString()).withStyle(ChatFormatting.GOLD), false);
			return 1;
		})));
		if (rogue) {
			root.then(Commands.literal("create").requires(admin).then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> rogueCreate(ctx, "default"))
					.then(poolArg.get().executes(ctx -> rogueCreate(ctx, StringArgumentType.getString(ctx, "pool"))))));
			root.then(Commands.literal("pick").requires(admin).then(Commands.argument("slot", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
					.then(Commands.argument("door", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(ctx -> {
						Run r = slots.get(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "slot"));
						if (r == null || r.state != State.RUNNING || r.rogue == null) return fail(ctx, "No rogue run going in that slot.");
						String err = RogueRuns.chooseNumber(ctx.getSource().getServer(), r, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "door"));
						if (err != null) return fail(ctx, err);
						ctx.getSource().sendSuccess(() -> Words.lit("Going through door " + com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "door") + "."), true);
						return 1;
					}))));
		} else {
			root.then(Commands.literal("create").requires(admin).then(Commands.argument("name", StringArgumentType.word()).executes(DungeonRuntime::corner1)
					.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::corner1))));
			root.then(Commands.literal("save").requires(admin).then(dungeonArg.get().executes(DungeonRuntime::save)
					.then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
							.then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::save)))));
		}
		root.then(Commands.literal("corner2").requires(admin).executes(DungeonRuntime::corner2)
				.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::corner2)));
		root.then(Commands.literal("room").requires(admin)
				.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> roomCorner1(ctx, rogue))
						.then(blockPos.apply("pos").executes(ctx -> roomCorner1(ctx, rogue)))))
				.then(Commands.literal("save").then(roomArg.get().executes(ctx -> saveRoom(ctx, rogue))
						.then(groupArg.get().executes(ctx -> saveRoom(ctx, rogue)))
						.then(blockPos.apply("from").then(blockPos.apply("to").executes(ctx -> saveRoom(ctx, rogue))
								.then(groupArg.get().executes(ctx -> saveRoom(ctx, rogue)))))))
				.then(Commands.literal("group").then(roomArg.get().then(groupArg.get().executes(ctx -> {
					Dungeons.Room x = settings.room(StringArgumentType.getString(ctx, "name"), rogue);
					if (x == null) return fail(ctx, "There's no such " + (rogue ? "rogue room" : "room") + ".");
					String g = Dungeons.pool(StringArgumentType.getString(ctx, "group"));
					boolean in = x.groups.remove(g);
					if (!in) x.groups.add(g);
					settings.fill();
					saveSettings();
					ctx.getSource().sendSuccess(() -> Words.lit(x.name + (in ? " left the group " : " is in the group ") + g + " (groups: " + (x.groups.isEmpty() ? "none" : String.join(", ", x.groups)) + ")."), true);
					return 1;
				}))))
				.then(Commands.literal("weight").then(roomArg.get().then(Commands.argument("weight", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100)).executes(ctx -> {
					Dungeons.Room x = settings.room(StringArgumentType.getString(ctx, "name"), rogue);
					if (x == null) return fail(ctx, "There's no such " + (rogue ? "rogue room" : "room") + ".");
					x.weight = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "weight");
					saveSettings();
					ctx.getSource().sendSuccess(() -> Words.lit(x.name + " has the weight " + x.weight + " now."), true);
					return 1;
				}))))
				.then(Commands.literal("delete").then(roomArg.get().executes(ctx -> {
					Dungeons.Room x = settings.room(StringArgumentType.getString(ctx, "name"), rogue);
					if (x == null) return fail(ctx, "There's no such " + (rogue ? "rogue room" : "room") + ".");
					settings.rooms.remove(x);
					settings.fill();
					saveSettings();
					DungeonTemplates.deleteRoom(ctx.getSource().getServer(), x.name);
					ctx.getSource().sendSuccess(() -> Words.lit("Removed the room " + x.name + "."), true);
					return 1;
				})))
				.then(Commands.literal("list").executes(ctx -> {
					StringBuilder b = new StringBuilder();
					for (Dungeons.Group g : settings.groups(rogue)) {
						List<String> names = settings.roomsIn(g.name, rogue).stream().map(x -> x.name + " (" + x.weight + (x.enabled ? "" : ", off") + ")").toList();
						b.append("\n").append(g.name).append(g.fillChance < 100 ? " " + g.fillChance + "%" : "").append(": ").append(names.isEmpty() ? "no rooms" : String.join(", ", names));
					}
					long loose = settings.rooms(rogue).stream().filter(x -> x.groups.isEmpty()).count();
					if (loose > 0) b.append("\n").append(loose).append(" room(s) in no group");
					ctx.getSource().sendSuccess(() -> Words.lit((rogue ? "Rogue room groups:" : "Room groups:") + (b.isEmpty() ? " none yet." : b)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
					return 1;
				})));
		var tool = Commands.literal("tool").requires(admin)
				.then(Commands.literal("marker").then(Commands.argument("text", StringArgumentType.greedyString())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(rogue ? List.of("start", "door", "spawn", "shop", "mob:zombie x3", "boss:") : List.of("start", "exit", "checkpoint", "mob:zombie x3", "boss:"), b)).executes(ctx -> {
							String text = StringArgumentType.getString(ctx, "text").strip();
							return tool(ctx, "minecraft:structure_block", "id:\"minecraft:structure_block\",mode:\"DATA\",metadata:" + snbt(text), "Marker: " + text,
									rogue ? "A rogue room marker (data structure block)" : "A dungeon marker (data structure block)");
						})));
		if (!rogue) {
			tool.then(Commands.literal("door").then(Commands.argument("door", StringArgumentType.word()).executes(ctx -> {
				String door = jigsawId(StringArgumentType.getString(ctx, "door"));
				return tool(ctx, "minecraft:jigsaw", "id:\"minecraft:jigsaw\",name:" + snbt(door) + ",target:" + snbt(door) + ",pool:\"minecraft:empty\",final_state:\"minecraft:air\",joint:\"rollable\"",
						"Room door: " + door.replace("minecraft:", ""), "Where this room joins a spot; face it out of the room");
			})))
					.then(Commands.literal("spot").then(groupArg.get().then(Commands.argument("door", StringArgumentType.word()).executes(ctx -> spotTool(ctx, "minecraft:air"))
							.then(Commands.argument("turns_into", StringArgumentType.greedyString()).executes(ctx -> spotTool(ctx, StringArgumentType.getString(ctx, "turns_into").strip()))))));
		}
		root.then(tool);
		root.then(Commands.literal("kit").requires(admin).then(dungeonArg.get().executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayerOrException();
			Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
			if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
			x.kit = GameClaims.kitFrom(p);
			x.moreKits = new ArrayList<>();
			saveSettings();
			ctx.getSource().sendSuccess(() -> Words.lit(x.name + "'s kit is what you carry now (" + x.kit.size() + " stacks). More kits to pick from at random: " + cmd + " kit " + x.name + " add."
					+ ("own".equals(x.gear) ? " It's handed out only when the " + what(rogue) + " puts things aside (gear: kit, in the panel)." : "")), true);
			return 1;
		})
				// One more kit: each player gets one of them at random.
				.then(Commands.literal("add").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
					if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
					List<String> taken = GameClaims.kitFrom(p);
					if (taken.isEmpty()) return fail(ctx, "Your inventory is empty: carry what the kit should hold first.");
					if (x.kit == null || x.kit.isEmpty()) x.kit = taken;
					else x.moreKits.add(taken);
					saveSettings();
					int n = GameClaims.kitCount(x.kit, x.moreKits);
					ctx.getSource().sendSuccess(() -> Words.lit(x.name + " has " + n + (n == 1 ? " kit" : " kits") + " now; each player gets one of them at random."), true);
					return 1;
				}))
				.then(Commands.literal("clear").executes(ctx -> {
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
					if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
					x.kit = new ArrayList<>();
					x.moreKits = new ArrayList<>();
					saveSettings();
					ctx.getSource().sendSuccess(() -> Words.lit(x.name + " has no kit now."), true);
					return 1;
				}))));
		root.then(Commands.literal("try").requires(admin).then(dungeonArg.get().executes(ctx -> {
			Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
			if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
			ServerPlayer p = ctx.getSource().getPlayer();
			if (p != null && playing.containsKey(p.getUUID())) return fail(ctx, "You're in a run already: " + cmd + " leave first.");
			String err = start(ctx.getSource().getServer(), p == null ? List.of() : List.of(p), x);
			if (err != null) return fail(ctx, err);
			ctx.getSource().sendSuccess(() -> Words.lit((rogue ? "Starting " : "Copying ") + x.name + "…"), true);
			return 1;
		})));
		root.then(Commands.literal("delete").requires(admin).then(dungeonArg.get().executes(ctx -> {
			Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
			if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
			settings.dungeons.remove(x);
			settings.fill();
			saveSettings();
			if (!rogue) DungeonTemplates.delete(ctx.getSource().getServer(), x.name);
			ctx.getSource().sendSuccess(() -> Words.lit("Removed " + x.name + " (runs already going carry on)."), true);
			return 1;
		})));
		root.then(Commands.literal("pool").requires(admin).then(dungeonArg.get().then(poolArg.get().executes(ctx -> {
			Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"), rogue);
			if (x == null) return fail(ctx, "There's no such " + what(rogue) + ".");
			x.pool = Dungeons.pool(StringArgumentType.getString(ctx, "pool"));
			saveSettings();
			ctx.getSource().sendSuccess(() -> Words.lit(x.name + " is in the " + x.pool + " pool now."), true);
			return 1;
		}))));
		root.then(Commands.literal("pad").requires(admin)
				.then(Commands.literal("add").then(poolArg.get().executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Dungeons.Pad pad = new Dungeons.Pad();
					pad.rogue = rogue;
					pad.pool = Dungeons.pool(StringArgumentType.getString(ctx, "pool"));
					pad.pos = new Games.Pos(p.level().dimension().identifier().toString(), Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5, 0);
					settings.pads.add(pad);
					settings.fill();
					saveSettings();
					ctx.getSource().sendSuccess(() -> Words.lit("Pad for the " + pad.pool + (rogue ? " rogue run pool" : " pool") + " here: the party leader standing on it for two seconds starts a run."), true);
					return 1;
				})))
				// A pool's waiting area: when every copy is in use, parties queue there.
				.then(Commands.literal("wait").then(poolArg.get()
						.executes(ctx -> waitArea(ctx, -1, rogue))
						.then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(2, 64)).executes(ctx -> waitArea(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), rogue)))
						.then(Commands.literal("clear").executes(ctx -> {
							String pool = Dungeons.pool(StringArgumentType.getString(ctx, "pool"));
							if (settings.waiting(rogue).remove(pool) == null) return fail(ctx, "The " + pool + " pool has no waiting area.");
							saveSettings();
							ctx.getSource().sendSuccess(() -> Words.lit("The " + pool + " pool has no waiting area any more: when every copy is in use, players try again later."), true);
							return 1;
						}))))
				.then(Commands.literal("remove").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					String dim = p.level().dimension().identifier().toString();
					Dungeons.Pad near = null;
					double best = 16;
					for (Dungeons.Pad pad : settings.pads(rogue)) {
						if (!pad.pos.dim.equals(dim)) continue;
						double dist = p.distanceToSqr(pad.pos.x, pad.pos.y, pad.pos.z);
						if (dist < best) {
							best = dist;
							near = pad;
						}
					}
					if (near == null) return fail(ctx, "No " + what(rogue) + " pad within 4 blocks.");
					settings.pads.remove(near);
					saveSettings();
					ctx.getSource().sendSuccess(() -> Words.lit("Pad removed."), true);
					return 1;
				})));
		root.then(Commands.literal("runs").requires(admin).executes(ctx -> {
			StringBuilder b = new StringBuilder();
			for (Run r : slots.values()) {
				if (r.dungeon.rogue() != rogue) continue;
				b.append("\nslot ").append(r.slot).append(": ").append(r.dungeon.name).append(" · ").append(r.state.name().toLowerCase(Locale.ROOT)).append(" · ").append(String.join(", ", r.names.values()));
			}
			ctx.getSource().sendSuccess(() -> Words.lit(b.isEmpty() ? "No " + what(rogue) + "s going right now." : (rogue ? "Rogue Runs:" : "Dungeon runs:") + b), false);
			return 1;
		}));
		d.register(root);
	}

	/** /rogue create <name> [pool]: a rogue run (a chain of rooms, set up in the panel). */
	private static int rogueCreate(CommandContext<CommandSourceStack> ctx, String pool) {
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		if (settings.dungeon(name) != null) return fail(ctx, "There's a " + what(settings.dungeon(name).rogue()) + " called " + name + " already (dungeons and rogue runs share names).");
		Dungeon d = new Dungeon();
		d.name = name;
		d.mode = "rogue";
		d.pool = Dungeons.pool(pool);
		d.roomGroups.put("fight", Dungeons.pool(name + "_fight"));
		d.roomGroups.put("elite", Dungeons.pool(name + "_elite"));
		d.roomGroups.put("boss", Dungeons.pool(name + "_boss"));
		for (String t : List.of("treasure", "shop", "rest")) d.roomGroups.put(t, Dungeons.pool(name + "_" + t));
		d.startGroup = Dungeons.pool(name + "_start");
		d.mobs.addAll(List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:spider"));
		d.bosses.add("ncc:frost_warden");
		settings.dungeons.add(d);
		settings.fill();
		saveSettings();
		ctx.getSource().sendSuccess(() -> Words.lit("Made the rogue run " + name + " in the " + d.pool + " pool. Its rooms come from the groups " + d.startGroup + " (first room, optional), "
				+ d.roomGroups.get("fight") + ", " + d.roomGroups.get("elite") + ", " + d.roomGroups.get("boss") + " (and _treasure, _shop, _rest, optional)"
				+ ": build rooms with a start marker, door markers (where players pick the next room) and spawn markers (where its mobs come), then /rogue room save <room> <group>."
				+ " Mobs, bosses, scaling, boons and the shop are in the panel (Games → Rogue Runs).").withStyle(ChatFormatting.LIGHT_PURPLE), true);
		return 1;
	}

	private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayer();
		MinecraftServer server = ctx.getSource().getServer();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		Selection s = p == null ? null : selections.get(p.getUUID());
		Dungeon d = settings.dungeon(name);
		if (d != null && d.rogue()) return fail(ctx, "There's a rogue run called " + name + " (dungeons and rogue runs share names).");
		ServerLevel level;
		BlockPos a, b;
		boolean corners = ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("to"));
		if (corners) {
			level = ctx.getSource().getLevel();
			a = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "from");
			b = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "to");
		} else if (s != null && s.name.equalsIgnoreCase(name) && s.b != null) {
			level = GameRuntime.levelOf(server, s.dim);
			a = s.a;
			b = s.b;
		} else if (d != null && d.from != null && d.to != null && d.sourceDim != null) {
			// Saving again after changes: the box it was made from.
			level = GameRuntime.levelOf(server, d.sourceDim);
			a = new BlockPos(d.from[0], d.from[1], d.from[2]);
			b = new BlockPos(d.to[0], d.to[1], d.to[2]);
		} else {
			return fail(ctx, "Mark the box first: /dungeon create " + name + " at one corner, /dungeon corner2 at the other.");
		}
		if (level == null) return fail(ctx, "That dimension isn't loaded.");
		long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
		if (volume > 16_000_000L) return fail(ctx, "That box is too big (" + String.format(Locale.ROOT, "%,d", volume) + " blocks; at most 16 million).");
		boolean fresh = d == null;
		Dungeon target = fresh ? new Dungeon() : d;
		if (fresh) target.name = name;
		target.savedBy = p == null ? "console" : p.getGameProfile().name();
		DungeonTemplates.save(server, level, a, b, target).whenComplete((ok, err) -> server.execute(() -> {
			if (err != null) {
				ctx.getSource().sendFailure(Words.lit("Could not save " + name + ": " + err.getMessage()));
				return;
			}
			if (fresh) settings.dungeons.add(target);
			settings.fill();
			saveSettings();
			if (p != null) selections.remove(p.getUUID());
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (Dungeons.Marker m : target.markers) counts.merge(m.type, 1, Integer::sum);
			String markers = counts.isEmpty() ? "no markers" : counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).reduce((x, y) -> x + ", " + y).orElse("");
			String spots = spots(target.connectors);
			ctx.getSource().sendSuccess(() -> Words.lit("Saved " + name + " (" + target.sizeX + " × " + target.sizeY + " × " + target.sizeZ + ", " + target.pieces + " pieces, " + markers + (spots.isEmpty() ? "" : ", " + spots)
					+ ") in the " + target.pool + " pool." + (target.hasMarker("start") ? "" : " It needs a start marker before players can get it.")
					+ (target.hasMarker("exit") || "boss".equals(target.clearOn) ? "" : " Without an exit marker it can't be cleared.")).withStyle(target.hasMarker("start") ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
		}));
		return 1;
	}

	/** /dungeon (or /rogue) pad wait <pool> [radius]: the pool's waiting area where the admin stands. */
	private static int waitArea(CommandContext<CommandSourceStack> ctx, int radius, boolean rogue) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String pool = Dungeons.pool(StringArgumentType.getString(ctx, "pool"));
		settings.waiting(rogue).put(pool, new Games.Pos(p.level().dimension().identifier().toString(), Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5, Math.round(p.getYRot() / 90f) * 90f));
		if (radius > 0) settings.waitRadius = radius;
		saveSettings();
		ctx.getSource().sendSuccess(() -> Words.lit("Waiting area for the " + pool + " pool here (radius " + settings.waitRadius + "): when every copy is in use, parties queue here and start as soon as one is free."), true);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Words.lit(why));
		return 0;
	}
}
