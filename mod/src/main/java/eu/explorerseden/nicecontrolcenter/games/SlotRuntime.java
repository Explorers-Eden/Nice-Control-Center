package eu.explorerseden.nicecontrolcenter.games;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Points;
import eu.explorerseden.nicecontrolcenter.Slots;
import eu.explorerseden.nicecontrolcenter.Slots.Machine;
import eu.explorerseden.nicecontrolcenter.Slots.Type;
import eu.explorerseden.nicecontrolcenter.Slots.Win;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.players.PointsStore;

/**
 * Slot Machines: placed from the built-in looks (/slots place), the lever costs points and spins the shelf above the
 * base block (three reels, stopping one by one), a winning row pays out. The lever flicks back at once and works for
 * everyone (claims let anyone pull it); the machine's blocks can't be changed or broken (admins in creative can).
 */
public final class SlotRuntime {
	public static final String PERMISSION = "nicecontrolcenter.slots.admin";

	private static Slots.Settings settings = new Slots.Settings().fill();
	private static long modified = -1;
	private static final Random random = new Random();
	/** Machines spinning right now (machine id → its spin). */
	private static final Map<String, Spin> spinning = new HashMap<>();
	/** Levers to flick back (machine id → tick). */
	private static final Map<String, Long> flickBack = new HashMap<>();

	/** Where the parts are, around the base block, for a turned machine. */
	record Parts(String dim, BlockPos base, BlockPos lever, BlockPos reels, List<BlockPos> all, Direction front) {
		boolean has(BlockPos p) {
			return all.contains(p);
		}
	}

	private static final class Spin {
		Machine machine;
		Type type;
		Parts parts;
		UUID player;
		String name;
		List<String> row;
		Win win;
		int tick;
		int[] stopAt;
	}

	private SlotRuntime() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			modified = -1;
			reload();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		// Preview displays left from before a restart go as their chunk loads.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.entityTags().contains(PREVIEW_TAG) && previews.values().stream().noneMatch(v -> v.shown.contains(entity))) entity.discard();
		});
		ServerTickEvents.END_SERVER_TICK.register(SlotRuntime::tick);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
			BlockPos pos = hit.getBlockPos();
			Machine m = machineAt(sl, pos);
			if (m == null) return InteractionResult.PASS;
			Parts parts = parts(m);
			if (pos.equals(parts.lever())) {
				pull(sl, p, m, parts);
				return InteractionResult.SUCCESS;
			}
			// Building against it: the base has nothing to open, the shelves and the trapdoor take blocks while sneaking
			// (then they aren't used). Otherwise they're for show.
			boolean placing = p.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem;
			if (placing && (pos.equals(parts.base()) || p.isSecondaryUseActive())) return InteractionResult.PASS;
			BlockState st = sl.getBlockState(pos);
			sl.sendBlockUpdated(pos, st, st, 3);
			p.inventoryMenu.sendAllDataToRemote();
			return InteractionResult.FAIL;
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return true;
			Machine m = machineAt(sl, pos);
			if (m == null) return true;
			if (admin(p) && p.isCreative()) {
				p.sendOverlayMessage(Words.lit("Part of a slot machine: /slots remove takes it away whole").withStyle(ChatFormatting.YELLOW));
				return true;
			}
			return false;
		});
	}

	// ---------------------------------------------------------------- settings

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Slots.FILE);
	}

	public static Slots.Settings settings() {
		return settings;
	}

	public static void reload() {
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		settings = Slots.read(file());
		if (!settings.takeAway.isEmpty() && server != null) {
			for (Machine m : settings.takeAway) takeAway(server, m);
			NiceControlCenter.LOGGER.info("Slots: {} slot machine(s) removed in the panel taken away", settings.takeAway.size());
			settings.takeAway.clear();
			save();
		}
	}

	private static MinecraftServer server;

	/** The machine's blocks go (the shelves emptied first, so nothing drops); a spin on it stops. */
	private static void takeAway(MinecraftServer server, Machine m) {
		spinning.remove(m.id);
		flickBack.remove(m.id);
		ServerLevel level = GameRuntime.levelOf(server, m.base.dim);
		if (level == null) return;
		Parts parts = parts(m);
		for (BlockPos b : parts.all()) if (level.getBlockEntity(b) instanceof ShelfBlockEntity shelf) shelf.getItems().clear();
		for (BlockPos b : parts.all()) level.setBlock(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
	}

	/** Reads the file again right now: commands change what the panel saved last, not an older copy. */
	public static void reloadNow() {
		eu.explorerseden.nicecontrolcenter.core.FileWatch.now(file());
		reload();
	}

	private static void save() {
		try {
			Slots.write(file(), settings);
			modified = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Slots: could not save the slot machines: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- where the parts are

	/**
	 * The parts of a machine. The saved look (rotation 0): the base block with the lever on its north side, the reel
	 * shelf on top of the base facing east (the front: players stand there), a second column of shelves to the west,
	 * a trapdoor on top.
	 */
	static Parts parts(Machine m) {
		Rotation rot = Rotation.values()[Math.floorMod(m.rotation, 4)];
		BlockPos base = BlockPos.containing(m.base.x, m.base.y, m.base.z);
		// Only the machine's own blocks (the base, the lever, three shelves, the trapdoor): the air around them in the
		// look is free to build in.
		List<BlockPos> all = new ArrayList<>();
		for (int[] o : new int[][] { { 0, 0, 0 }, { 0, 0, -1 }, { -1, 0, 0 }, { -1, 1, 0 }, { 0, 1, 0 }, { 0, 2, 0 } }) all.add(base.offset(new BlockPos(o[0], o[1], o[2]).rotate(rot)));
		return new Parts(m.base.dim, base, base.offset(new BlockPos(0, 0, -1).rotate(rot)), base.above(), all, rot.rotate(Direction.EAST));
	}

	/** The machine one of whose blocks is at pos (null = none). */
	static Machine machineAt(Level level, BlockPos pos) {
		if (!settings.enabled) return null;
		String dim = level.dimension().identifier().toString();
		for (Machine m : settings.machines) {
			if (!m.base.dim.equals(dim)) continue;
			if (Math.abs(m.base.x - pos.getX()) > 3 || Math.abs(m.base.z - pos.getZ()) > 3 || Math.abs(m.base.y - pos.getY()) > 3) continue;
			if (parts(m).has(pos)) return m;
		}
		return null;
	}

	/** A slot machine block at pos: claims let anyone use it (the lever) and nobody change it. */
	public static boolean isMachine(Level level, BlockPos pos) {
		return machineAt(level, pos) != null;
	}

	// ---------------------------------------------------------------- pulling the lever

	private static void pull(ServerLevel level, ServerPlayer p, Machine m, Parts parts) {
		if (p.isSpectator()) return;
		Type t = settings.type(m.type);
		if (t == null) {
			p.sendOverlayMessage(Words.lit("This slot machine isn't set up yet").withStyle(ChatFormatting.RED));
			return;
		}
		if (spinning.containsKey(m.id)) {
			p.sendOverlayMessage(Words.lit("Still spinning…").withStyle(ChatFormatting.GRAY));
			return;
		}
		Points.Settings ps = PointsStore.settings();
		if (t.cost > 0) {
			if (!ps.enabled) {
				p.sendOverlayMessage(Words.lit("Slot Machines need " + ps.name + ", which are off").withStyle(ChatFormatting.RED));
				return;
			}
			if (!PointsStore.take(p.getStringUUID(), p.getGameProfile().name(), t.cost, "Slot Machine " + t.shown())) {
				p.sendOverlayMessage(Words.lit("A pull costs " + Points.amount(ps, t.cost) + "; you have " + Points.amount(ps, PointsStore.balance(p.getStringUUID())))
						.withStyle(ChatFormatting.RED));
				level.playSound(null, parts.lever(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.6f, 0.6f);
				return;
			}
		}
		flick(level, parts.lever(), true);
		flickBack.put(m.id, level.getServer().getTickCount() + 6L);

		Spin s = new Spin();
		s.machine = m;
		s.type = t;
		s.parts = parts;
		s.player = p.getUUID();
		s.name = p.getGameProfile().name();
		s.win = roll(t);
		s.row = s.win != null ? s.win.symbols : losingRow(t);
		int last = (int) Math.round(t.spinSeconds * 20);
		s.stopAt = new int[] { Math.max(6, last * 2 / 5), Math.max(10, last * 7 / 10), Math.max(14, last) };
		spinning.put(m.id, s);
		p.sendOverlayMessage(Words.lit("−" + Points.shortAmount(ps, t.cost) + " · " + Points.amount(ps, PointsStore.balance(p.getStringUUID())) + " left").withStyle(ChatFormatting.GOLD));
	}

	/** The lever looks pulled (or not) without powering anything. */
	private static void flick(ServerLevel level, BlockPos lever, boolean on) {
		BlockState st = level.getBlockState(lever);
		if (!(st.getBlock() instanceof LeverBlock)) return;
		level.setBlock(lever, st.setValue(LeverBlock.POWERED, on), 2 | 16);
		level.playSound(null, lever, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, on ? 0.6f : 0.5f);
	}

	/** One win by its chance (they add up; the rest is a loss): null = no win. */
	private static Win roll(Type t) {
		double r = random.nextDouble() * 100;
		double sum = 0;
		for (Win w : t.wins) {
			sum += w.chance;
			if (r < sum) return w;
		}
		return null;
	}

	/** A row that isn't any win (and not three of a kind). */
	private static List<String> losingRow(Type t) {
		List<String> all = t.symbols();
		List<String> row = new ArrayList<>();
		for (int tries = 0; tries < 60; tries++) {
			row = List.of(all.get(random.nextInt(all.size())), all.get(random.nextInt(all.size())), all.get(random.nextInt(all.size())));
			boolean same = row.get(0).equals(row.get(1)) && row.get(1).equals(row.get(2));
			List<String> r = row;
			if (!same && t.wins.stream().noneMatch(w -> w.symbols.equals(r))) return row;
		}
		return List.of(all.get(0), all.get(1 % all.size()), all.get(0));
	}

	// ---------------------------------------------------------------- the spin

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 == 0) reload();
		if (server.getTickCount() % 20 == 0 && !previews.isEmpty()) {
			long ms = System.currentTimeMillis();
			for (UUID id : new ArrayList<>(previews.keySet())) {
				Preview v = previews.get(id);
				if (v.until < ms || server.getPlayerList().getPlayer(id) == null) clearPreview(server, id);
			}
		}
		long now = server.getTickCount();
		for (Iterator<Map.Entry<String, Long>> it = flickBack.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Long> e = it.next();
			if (e.getValue() > now) continue;
			it.remove();
			Machine m = settings.machines.stream().filter(x -> x.id.equals(e.getKey())).findFirst().orElse(null);
			ServerLevel level = m == null ? null : GameRuntime.levelOf(server, m.base.dim);
			if (level != null) flick(level, parts(m).lever(), false);
		}
		for (Iterator<Spin> it = spinning.values().iterator(); it.hasNext();) {
			Spin s = it.next();
			ServerLevel level = GameRuntime.levelOf(server, s.parts.dim());
			if (level == null || !(level.getBlockEntity(s.parts.reels()) instanceof ShelfBlockEntity shelf)) {
				it.remove();
				continue;
			}
			s.tick++;
			List<String> all = s.type.symbols();
			boolean changed = false;
			for (int i = 0; i < 3; i++) {
				if (s.tick == s.stopAt[i]) {
					shelf.getItems().set(i, stack(s.row.get(i)));
					changed = true;
					level.playSound(null, s.parts.reels(), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.BLOCKS, 0.7f, 0.8f + i * 0.1f);
				} else if (s.tick < s.stopAt[i] && s.tick % 2 == 0) {
					shelf.getItems().set(i, stack(all.get(random.nextInt(all.size()))));
					changed = true;
				}
			}
			if (s.tick < s.stopAt[2] && s.tick % 2 == 0) level.playSound(null, s.parts.reels(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.25f, 1.6f + random.nextFloat() * 0.3f);
			if (changed) {
				shelf.setChanged();
				BlockState st = level.getBlockState(s.parts.reels());
				level.sendBlockUpdated(s.parts.reels(), st, st, 3);
			}
			if (s.tick >= s.stopAt[2] + 4) {
				it.remove();
				done(server, level, s);
			}
		}
	}

	private static ItemStack stack(String id) {
		Identifier i = Identifier.tryParse(id);
		Item item = i == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(i);
		return new ItemStack(item == Items.AIR ? Items.BARRIER : item);
	}

	private static String symbolName(String id) {
		return stack(id).getHoverName().getString();
	}

	/** The reels stopped: the payout (or not). */
	private static void done(MinecraftServer server, ServerLevel level, Spin s) {
		ServerPlayer p = server.getPlayerList().getPlayer(s.player);
		BlockPos at = s.parts.reels();
		if (s.win == null) {
			level.playSound(null, at, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.7f, 0.5f);
			if (p != null) p.sendOverlayMessage(Words.lit("No luck this time").withStyle(ChatFormatting.GRAY));
			return;
		}
		Win w = s.win;
		Points.Settings ps = PointsStore.settings();
		String row = w.symbols.get(0).equals(w.symbols.get(1)) && w.symbols.get(1).equals(w.symbols.get(2))
				? "3× " + symbolName(w.symbols.get(0))
				: String.join(" · ", w.symbols.stream().map(SlotRuntime::symbolName).toList());
		if (w.payout > 0) PointsStore.add(s.player.toString(), s.name, w.payout, "Slot Machine " + s.type.shown() + ": " + row);
		boolean big = s.type.cost > 0 && w.payout >= s.type.cost * 10;
		level.playSound(null, at, big ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.8f, 1f);
		level.playSound(null, at, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8f, 1.2f);
		// Sparkles in front of the reels; a big win bursts.
		double fx = at.getX() + 0.5 + s.parts.front().getStepX() * 0.7, fy = at.getY() + 0.6, fz = at.getZ() + 0.5 + s.parts.front().getStepZ() * 0.7;
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, fx, fy, fz, 20, 0.5, 0.4, 0.5, 0.05);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE, fx, fy + 0.6, fz, 6, 0.4, 0.2, 0.4, 0.5);
		if (big) {
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, fx, fy + 0.3, fz, 60, 0.4, 0.5, 0.4, 0.5);
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.FIREWORK, fx, fy + 1.2, fz, 30, 0.3, 0.3, 0.3, 0.15);
		}
		String paid = w.payout > 0 ? "+" + Points.amount(ps, w.payout) : "";
		if (p != null) {
			String text = w.message.isBlank() ? s.type.shown() + ": " + row + "! " + paid
					: w.message.replace("{player}", s.name).replace("{payout}", Points.amount(ps, w.payout));
			p.sendSystemMessage(Words.lit(text.strip()).withStyle(ChatFormatting.GOLD));
		}
		if (w.announce) {
			server.getPlayerList().broadcastSystemMessage(Words.lit(s.name + " won " + (w.payout > 0 ? Points.amount(ps, w.payout) + " " : "") + "at " + s.type.shown() + " (" + row + ")!")
					.withStyle(ChatFormatting.GOLD), false);
		}
		for (String c : w.commands) {
			ConsoleRunner.quiet(server, c.replace("{player}", s.name).replace("{uuid}", s.player.toString()).replace("{machine}", s.type.name).replace("{payout}", String.valueOf(w.payout)));
		}
		NiceControlCenter.LOGGER.info("Slots: {} won {} at {} ({})", s.name, paid.isEmpty() ? "a row" : paid, s.type.name, row);
	}

	// ---------------------------------------------------------------- placing

	/** A placement being looked at: block displays where the machine would go, until confirmed or cancelled. */
	private static final class Preview {
		String type;
		String look;
		int turns;
		BlockPos base;
		String dim;
		final List<net.minecraft.world.entity.Entity> shown = new ArrayList<>();
		long until;
	}

	private static final Map<UUID, Preview> previews = new HashMap<>();
	private static final String PREVIEW_TAG = "ncc_slot_preview";

	/** The look's blocks where they'd go (turned), with the positions the whole box takes. */
	private static List<Map.Entry<BlockPos, BlockState>> blocks(ServerLevel level, String look, int turns, BlockPos base) {
		var manager = templates(level.getServer());
		var template = manager == null ? java.util.Optional.<StructureTemplate>empty() : manager.get(Identifier.fromNamespaceAndPath("nicecontrolcenter", "slot_machine/" + look));
		List<Map.Entry<BlockPos, BlockState>> out = new ArrayList<>();
		if (template.isEmpty()) return out;
		Rotation rot = Rotation.values()[turns];
		StructurePlaceSettings place = new StructurePlaceSettings().setRotation(rot);
		BlockPos origin = base.subtract(StructureTemplate.calculateRelativePosition(place, new BlockPos(1, 0, 1)));
		net.minecraft.nbt.CompoundTag tag = template.get().save(new net.minecraft.nbt.CompoundTag());
		var lookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
		List<BlockState> palette = new ArrayList<>();
		for (net.minecraft.nbt.Tag t : tag.getListOrEmpty("palette")) palette.add(t instanceof net.minecraft.nbt.CompoundTag c ? net.minecraft.nbt.NbtUtils.readBlockState(lookup, c) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		for (net.minecraft.nbt.Tag t : tag.getListOrEmpty("blocks")) {
			if (!(t instanceof net.minecraft.nbt.CompoundTag c)) continue;
			int[] pos = c.getListOrEmpty("pos").stream().mapToInt(x -> x.asInt().orElse(0)).toArray();
			int i = c.getIntOr("state", 0);
			if (pos.length < 3 || i < 0 || i >= palette.size()) continue;
			BlockPos at = origin.offset(StructureTemplate.calculateRelativePosition(place, new BlockPos(pos[0], pos[1], pos[2])));
			out.add(Map.entry(at, palette.get(i).rotate(rot)));
		}
		return out;
	}

	/** A new preview in front of the player: the front toward them, the base block two blocks ahead. */
	private static void preview(ServerPlayer p, Type t, String look) {
		Direction facing = p.getDirection();
		int turns = 0;
		while (Rotation.values()[turns].rotate(Direction.EAST) != facing.getOpposite()) turns++;
		Preview v = new Preview();
		v.type = t.id;
		v.look = look;
		v.turns = turns;
		v.base = p.blockPosition().relative(facing, 2);
		v.dim = p.level().dimension().identifier().toString();
		clearPreview(p.level().getServer(), p.getUUID());
		previews.put(p.getUUID(), v);
		show((ServerLevel) p.level(), p, v);
	}

	/** Block displays for the preview (glowing gold), and the buttons. */
	private static void show(ServerLevel level, ServerPlayer p, Preview v) {
		for (var e : v.shown) e.discard();
		v.shown.clear();
		v.until = System.currentTimeMillis() + 180_000;
		int inTheWay = 0;
		for (Map.Entry<BlockPos, BlockState> b : blocks(level, v.look, v.turns, v.base)) {
			if (b.getValue().isAir()) continue;
			BlockState now = level.getBlockState(b.getKey());
			if (!now.isAir() && !now.canBeReplaced()) inTheWay++;
			net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
			tag.put("block_state", net.minecraft.nbt.NbtUtils.writeBlockState(b.getValue()));
			tag.putBoolean("Glowing", true);
			tag.putInt("glow_color_override", 0xFFC83D);
			net.minecraft.nbt.ListTag tags = new net.minecraft.nbt.ListTag();
			tags.add(net.minecraft.nbt.StringTag.valueOf(PREVIEW_TAG));
			tag.put("Tags", tags);
			var entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:block_display")), tag, level,
					net.minecraft.world.entity.EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
			if (entity != null) entity.snapTo(b.getKey().getX(), b.getKey().getY(), b.getKey().getZ(), 0, 0);
			if (entity == null) continue;
			v.shown.add(entity);
			if (!level.addFreshEntity(entity)) v.shown.remove(entity);
		}
		Type t = settings.type(v.type);
		var msg = Words.lit("Slot Machine " + (t == null ? "" : t.shown()) + " (" + v.look + ")" + (inTheWay > 0 ? ": replaces " + inTheWay + " block" + (inTheWay == 1 ? "" : "s") : "") + "  ")
				.withStyle(inTheWay > 0 ? ChatFormatting.YELLOW : ChatFormatting.GOLD);
		msg.append(button("✔ Place", "/slots preview confirm", "Build it here", ChatFormatting.GREEN));
		msg.append(button("⟳ Turn", "/slots preview turn", "A quarter turn clockwise", ChatFormatting.AQUA));
		msg.append(button("Look", "/slots preview look", "The next look (" + String.join(", ", Slots.LOOKS) + ")", ChatFormatting.AQUA));
		msg.append(Component.literal("\n  Move: ").withStyle(ChatFormatting.GRAY));
		for (String dir : List.of("forward", "back", "left", "right", "up", "down")) msg.append(button(dir, "/slots preview " + dir, "One block " + dir + " (as you look now)", ChatFormatting.WHITE));
		msg.append(button("✖ Cancel", "/slots preview cancel", "No machine", ChatFormatting.RED));
		if (p != null) p.sendSystemMessage(msg);
	}

	private static Component button(String label, String command, String hover, ChatFormatting colour) {
		String action = command.substring(command.lastIndexOf(' ') + 1);
		return Component.literal("[" + label + "] ").withStyle(st -> st.withColor(colour)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.Custom(Identifier.fromNamespaceAndPath("nicecontrolcenter", "slots/" + action), java.util.Optional.empty()))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(hover))));
	}

	private static void clearPreview(MinecraftServer server, UUID player) {
		Preview v = previews.remove(player);
		if (v != null) for (var e : v.shown) e.discard();
	}

	/** Builds the previewed machine and keeps it. */
	private static String confirm(MinecraftServer server, String who, Preview v) {
		ServerLevel level = GameRuntime.levelOf(server, v.dim);
		Type t = settings.type(v.type);
		if (level == null || t == null) return "That type or dimension is gone.";
		var manager = templates(level.getServer());
		var template = manager == null ? java.util.Optional.<StructureTemplate>empty() : manager.get(Identifier.fromNamespaceAndPath("nicecontrolcenter", "slot_machine/" + v.look));
		if (template.isEmpty()) return "The look " + v.look + " isn't there.";
		Rotation rot = Rotation.values()[v.turns];
		// The look's air isn't placed: what's built around the machine stays.
		StructurePlaceSettings place = new StructurePlaceSettings().setRotation(rot).addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor.STRUCTURE_AND_AIR);
		BlockPos origin = v.base.subtract(StructureTemplate.calculateRelativePosition(place, new BlockPos(1, 0, 1)));
		template.get().placeInWorld(level, origin, origin, place, RandomSource.create(), 2);
		Machine m = new Machine();
		m.type = t.id;
		m.look = v.look;
		m.rotation = v.turns;
		m.base = new Games.Pos(v.dim, v.base.getX(), v.base.getY(), v.base.getZ(), 0);
		settings.machines.add(m);
		settings.fill();
		save();
		NiceControlCenter.LOGGER.info("Slots: {} placed a {} machine ({}) at {} {} {}", who, t.name, v.look, v.base.getX(), v.base.getY(), v.base.getZ());
		return null;
	}

	/** The structure templates (getStructureManager in 26.1, getStructureTemplateManager in 26.3). */
	private static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates(MinecraftServer server) {
		for (String name : new String[] { "getStructureTemplateManager", "getStructureManager" }) {
			try {
				Object o = MinecraftServer.class.getMethod(name).invoke(server);
				if (o instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager m) return m;
			} catch (ReflectiveOperationException e) {
				// the other name
			}
		}
		return null;
	}

	/** The machine closest to the player within 6 blocks. */
	private static Machine nearest(ServerPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		Machine best = null;
		double bestD = 36;
		for (Machine m : settings.machines) {
			if (!m.base.dim.equals(dim)) continue;
			double dx = m.base.x + 0.5 - p.getX(), dy = m.base.y - p.getY(), dz = m.base.z + 0.5 - p.getZ();
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		return best;
	}

	private static boolean admin(ServerPlayer p) {
		return Permissions.check(p, PERMISSION, PermissionLevel.GAMEMASTERS);
	}

	// ---------------------------------------------------------------- commands

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var admin = Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS);
		d.register(Commands.literal("slots").requires(src -> {
			reloadNow();
			return admin.test(src);
		})
				.then(Commands.literal("place").then(Commands.argument("type", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.types.stream().map(t -> t.name), b))
						.executes(ctx -> place(ctx, Slots.LOOKS.get(0)))
						.then(Commands.argument("look", StringArgumentType.word()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(Slots.LOOKS, b))
								.executes(ctx -> place(ctx, StringArgumentType.getString(ctx, "look").toLowerCase(Locale.ROOT))))))
				.then(Commands.literal("preview").then(Commands.argument("action", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("confirm", "cancel", "turn", "look", "forward", "back", "left", "right", "up", "down"), b))
						.executes(ctx -> {
							previewAction(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "action"));
							return 1;
						})))
				.then(Commands.literal("remove").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Machine m = nearest(p);
					if (m == null) return fail(ctx, "No slot machine within 6 blocks.");
					settings.machines.remove(m);
					save();
					takeAway(ctx.getSource().getServer(), m);
					ctx.getSource().sendSuccess(() -> Component.literal("Slot Machine removed."), true);
					return 1;
				}))
				.then(Commands.literal("type").then(Commands.argument("type", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.types.stream().map(t -> t.name), b))
						.executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							Machine m = nearest(p);
							if (m == null) return fail(ctx, "No slot machine within 6 blocks.");
							Type t = settings.type(StringArgumentType.getString(ctx, "type"));
							if (t == null) return fail(ctx, "No type called that (Games → Slot Machines in the panel).");
							m.type = t.id;
							save();
							ctx.getSource().sendSuccess(() -> Component.literal("That slot machine is now a " + t.shown() + "."), true);
							return 1;
						})))
				.then(Commands.literal("list").executes(ctx -> {
					if (settings.machines.isEmpty()) {
						ctx.getSource().sendSuccess(() -> Component.literal("No slot machines yet: /slots place <type> [look] (types are made in the panel, Games → Slot Machines)."), false);
						return 1;
					}
					StringBuilder out = new StringBuilder(settings.machines.size() + " slot machines:");
					for (Machine m : settings.machines) {
						Type t = settings.type(m.type);
						out.append("\n").append(t == null ? "(no type)" : t.shown()).append(" · ").append(m.look).append(" · ").append(m.base.dim.replace("minecraft:", ""))
								.append(" ").append((int) m.base.x).append(" ").append((int) m.base.y).append(" ").append((int) m.base.z);
					}
					ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
					return 1;
				})));
	}

	private static int place(CommandContext<CommandSourceStack> ctx, String look) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Type t = settings.type(StringArgumentType.getString(ctx, "type"));
		if (t == null) return fail(ctx, settings.types.isEmpty() ? "No slot machine types yet: make one in the panel (Games → Slot Machines)." : "No type called that. Types: "
				+ String.join(", ", settings.types.stream().map(x -> x.name).toList()) + ".");
		if (!Slots.LOOKS.contains(look)) return fail(ctx, "Looks: " + String.join(", ", Slots.LOOKS) + ".");
		preview(p, t, look);
		return 1;
	}

	/** A preview button (a chat click, answered without a command, or /slots preview <action>). Server thread. */
	public static void previewAction(ServerPlayer p, String action) {
		if (!admin(p)) return;
		reloadNow();
		Preview v = previews.get(p.getUUID());
		if (v == null) {
			p.sendSystemMessage(Component.literal("No placement going on: /slots place <type> [look].").withStyle(ChatFormatting.RED));
			return;
		}
		MinecraftServer server = p.level().getServer();
		if (action.equals("cancel")) {
			clearPreview(server, p.getUUID());
			p.sendSystemMessage(Component.literal("No slot machine placed.").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (action.equals("confirm")) {
			String err = confirm(server, p.getGameProfile().name(), v);
			clearPreview(server, p.getUUID());
			p.sendSystemMessage(Component.literal(err != null ? err : "Slot Machine placed. /slots remove next to it takes it away.").withStyle(err != null ? ChatFormatting.RED : ChatFormatting.GREEN));
			return;
		}
		if (!v.dim.equals(p.level().dimension().identifier().toString())) {
			p.sendSystemMessage(Component.literal("The preview is in another dimension.").withStyle(ChatFormatting.RED));
			return;
		}
		Direction f = p.getDirection();
		switch (action) {
			case "turn" -> v.turns = (v.turns + 1) % 4;
			case "look" -> v.look = Slots.LOOKS.get((Slots.LOOKS.indexOf(v.look) + 1) % Slots.LOOKS.size());
			case "forward" -> v.base = v.base.relative(f);
			case "back" -> v.base = v.base.relative(f.getOpposite());
			case "left" -> v.base = v.base.relative(f.getCounterClockWise());
			case "right" -> v.base = v.base.relative(f.getClockWise());
			case "up" -> v.base = v.base.above();
			case "down" -> v.base = v.base.below();
			default -> {
				return;
			}
		}
		show((ServerLevel) p.level(), p, v);
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}
}
