package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * Tree felling (like FallingTree) and vein mining: breaking one log of a tree with an axe breaks the
 * whole tree, breaking one ore with a pickaxe the whole vein. Every block is broken as if the player
 * mined it (player.gameMode.destroyBlock), so drops, Fortune and Silk Touch, XP, tool wear and land
 * claims all work as usual. Sneaking breaks just the one block (configurable).
 */
public final class Felling {
	/** True while this class breaks blocks itself, so those breaks don't start new trees. */
	private static boolean busy;
	/** Where drops go while felling (dropsAtBreakPoint), else null. Server thread. */
	private static BlockPos dropPoint;
	/** While a falling tree is prepared: its drops, spawned when it lands. */
	private static List<ItemEntity> captured;

	private record Decay(ServerLevel level, BlockPos pos, long due) {
	}

	private static final List<Decay> decays = new ArrayList<>();

	private record Tree(List<BlockPos> logs, List<BlockPos> warts, List<BlockPos> roots, Set<BlockPos> leaves) {
	}

	private Felling() {
	}

	static void register() {
		PlayerBlockBreakEvents.BEFORE.register(Felling::before);
		PlayerBlockBreakEvents.AFTER.register(Felling::after);
	}

	/** For the drop mixin: move new items and XP here (or null). */
	public static BlockPos dropPoint() {
		return dropPoint;
	}

	/** For the drop mixin: true when the item is kept for a falling tree (and not spawned now). */
	public static boolean capture(Entity entity) {
		if (captured == null || !(entity instanceof ItemEntity item)) return false;
		captured.add(item);
		return true;
	}

	private static boolean active(ServerPlayer player, Tweaks.Sneak sneak, boolean inCreative) {
		if (player.isCreative() && !inCreative) return false;
		boolean sneaking = player.isShiftKeyDown();
		return switch (sneak) {
			case DISABLES -> !sneaking;
			case ENABLES -> sneaking;
			case IGNORED -> true;
		};
	}

	/** shiftDown: each log broken takes the top log of the tree instead, so the tree sinks. */
	private static boolean before(Level level, Player p, BlockPos pos, BlockState state, BlockEntity be) {
		if (busy || !(p instanceof ServerPlayer player) || !(level instanceof ServerLevel server)) return true;
		Tweaks.Felling fel = TweaksControl.settings().felling;
		if (!fel.enabled || !"shiftDown".equals(fel.breakMode) || !TweaksControl.isLog(state)
				|| !TweaksControl.fellingTool(player.getMainHandItem()) || !active(player, fel.sneak, fel.inCreative)) {
			return true;
		}
		Tree tree = findTree(server, pos, state, fel);
		if (tree == null || tree.logs().isEmpty()) return true;
		BlockPos top = tree.logs().stream().max(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(b -> b.distSqr(pos))).orElse(null);
		if (top == null) return true;
		breakAll(server, player, pos, List.of(top), fel.preserveTool, fel.durability, fel.dropsAtBreakPoint, true);
		player.connection.send(new ClientboundBlockUpdatePacket(server, pos));
		if (tree.logs().size() == 1 && fel.fastLeafDecay) scheduleDecay(server, tree.leaves());
		return false;
	}

	private static void after(Level level, Player p, BlockPos pos, BlockState state, BlockEntity be) {
		if (busy || !(p instanceof ServerPlayer player) || !(level instanceof ServerLevel server)) return;
		Tweaks.Settings s = TweaksControl.settings();
		ItemStack tool = player.getMainHandItem();
		if (s.felling.enabled && "instant".equals(s.felling.breakMode) && TweaksControl.isLog(state) && TweaksControl.fellingTool(tool)
				&& active(player, s.felling.sneak, s.felling.inCreative)) {
			fell(server, player, pos, state, s.felling);
		} else if (s.veins.enabled && TweaksControl.isOre(state) && TweaksControl.veinTool(tool) && active(player, s.veins.sneak, s.veins.inCreative)) {
			vein(server, player, pos, state, s.veins);
		}
	}

	private static void fell(ServerLevel level, ServerPlayer player, BlockPos origin, BlockState originState, Tweaks.Felling fel) {
		Tree tree = findTree(level, origin, originState, fel);
		if (tree == null) return;
		List<BlockPos> logs = new ArrayList<>(tree.logs());
		if (logs.size() > fel.maxLogs - 1) {
			if (!"cutUpTo".equals(fel.tooBig)) {
				player.sendOverlayMessage(Component.literal("This tree is too big to fell at once (more than " + fel.maxLogs + " logs).").withStyle(ChatFormatting.GRAY));
				Plop.play(player);
				return;
			}
			logs.sort(Comparator.comparingDouble(b -> b.distSqr(origin)));
			logs = new ArrayList<>(logs.subList(0, fel.maxLogs - 1));
		}
		// From the top down, so a worn-out axe leaves a stump rather than a floating crown.
		logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed().thenComparingDouble(b -> b.distSqr(origin)));
		List<ItemEntity> drops = fel.animation ? new ArrayList<>() : null;
		captured = drops;
		List<TreeFall.Piece> pieces;
		Set<BlockPos> fallen = new HashSet<>();
		try {
			pieces = breakAll(level, player, origin, logs, fel.preserveTool, fel.durability, fel.dropsAtBreakPoint, !fel.animation);
			busy = true;
			BlockPos before = dropPoint;
			dropPoint = fel.dropsAtBreakPoint ? origin : null;
			try {
				if (fel.netherWarts) for (BlockPos w : tree.warts()) destroy(level, player, w, pieces);
				if (fel.mangroveRoots) for (BlockPos r : tree.roots()) destroy(level, player, r, pieces);
				// The crown falls with the tree: leaves no other log holds any more.
				if (fel.animation) {
					for (BlockPos leaf : looseLeaves(level, tree.leaves())) {
						BlockState s = level.getBlockState(leaf);
						for (ItemStack drop : Block.getDrops(s, level, leaf, null)) {
							ItemEntity item = new ItemEntity(level, leaf.getX() + 0.5, leaf.getY() + 0.5, leaf.getZ() + 0.5, drop);
							item.setDefaultPickUpDelay();
							drops.add(item);
						}
						level.removeBlock(leaf, false);
						pieces.add(new TreeFall.Piece(leaf, s));
						fallen.add(leaf);
					}
				}
			} finally {
				busy = false;
				dropPoint = before;
			}
		} finally {
			captured = null;
		}
		if (fel.animation) {
			if (pieces.isEmpty()) drops.forEach(level::addFreshEntity);
			else TreeFall.start(level, origin, player.position(), pieces, drops, fel.dropsAtBreakPoint);
		}
		if (fel.fastLeafDecay) {
			Set<BlockPos> rest = new HashSet<>(tree.leaves());
			rest.removeAll(fallen);
			scheduleDecay(level, rest);
		}
	}

	private static void destroy(ServerLevel level, ServerPlayer player, BlockPos pos, List<TreeFall.Piece> pieces) {
		BlockState s = level.getBlockState(pos);
		if (level.destroyBlock(pos, true, player)) pieces.add(new TreeFall.Piece(pos, s));
	}

	/** Natural leaves among these that no log keeps alive any more (vanilla's rule: none within 6 leaves). */
	private static List<BlockPos> looseLeaves(ServerLevel level, Set<BlockPos> leaves) {
		if (leaves.isEmpty()) return List.of();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : leaves) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		int r = LeavesBlock.DECAY_DISTANCE - 1;
		// From every log still standing nearby, through leaves, up to 6 steps.
		Set<BlockPos> held = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos p : BlockPos.betweenClosed(minX - r, minY - r, minZ - r, maxX + r, maxY + r, maxZ + r)) {
			if (level.getBlockState(p).is(BlockTags.LOGS)) queue.add(p.immutable());
		}
		for (int depth = 0; depth < r && !queue.isEmpty(); depth++) {
			int n = queue.size();
			for (int i = 0; i < n; i++) {
				BlockPos p = queue.poll();
				for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
					BlockPos q = p.relative(d);
					if (!held.contains(q) && level.getBlockState(q).getBlock() instanceof LeavesBlock) {
						held.add(q);
						queue.add(q);
					}
				}
			}
		}
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : leaves) {
			if (held.contains(p)) continue;
			BlockState s = level.getBlockState(p);
			if (TweaksControl.isLeaves(s) && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) out.add(p);
		}
		return out;
	}

	/** The logs of the tree around a broken log (without it), its wart blocks, roots and leaves; null when it isn't a tree. */
	private static Tree findTree(ServerLevel level, BlockPos origin, BlockState originState, Tweaks.Felling fel) {
		int maxScan = Math.max(fel.maxLogs * 4, 64);
		Block kind = originState.getBlock();
		Set<BlockPos> logs = new LinkedHashSet<>();
		Set<BlockPos> seen = new HashSet<>();
		List<BlockPos> warts = new ArrayList<>();
		List<BlockPos> roots = new ArrayList<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin);
		seen.add(origin);
		while (!queue.isEmpty() && logs.size() < maxScan) {
			BlockPos p = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						BlockPos n = p.offset(dx, dy, dz);
						// Never below the cut: the stump of the next tree over, or the ground, stays.
						if (n.getY() < origin.getY() || !seen.add(n)) continue;
						BlockState s = level.getBlockState(n);
						if (TweaksControl.isLog(s) && (fel.mixedLogs || s.getBlock() == kind)) {
							logs.add(n);
							queue.add(n);
						} else if (s.is(BlockTags.WART_BLOCKS) || s.is(Blocks.SHROOMLIGHT)) {
							warts.add(n);
						} else if (s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS)) {
							roots.add(n);
							queue.add(n);
						}
					}
				}
			}
		}
		if (logs.isEmpty()) return null;
		// A tree has leaves (or nether wart) at its top; a log wall someone built doesn't.
		int topY = logs.stream().mapToInt(BlockPos::getY).max().orElse(origin.getY());
		Set<BlockPos> leaves = new HashSet<>();
		int natural = 0;
		for (BlockPos log : logs) {
			if (log.getY() < topY - 2) continue;
			for (BlockPos n : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
				BlockState s = level.getBlockState(n);
				if (TweaksControl.isLeaves(s)) {
					if (leaves.add(n.immutable()) && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) natural++;
				}
			}
		}
		natural += warts.size();
		if (natural < fel.minLeaves) return null;
		// All leaves near the trunk, for the fast decay and the falling crown.
		if (fel.fastLeafDecay || fel.animation) {
			for (BlockPos log : logs) {
				int r = 2;
				for (BlockPos n : BlockPos.betweenClosed(log.offset(-r, -r, -r), log.offset(r, r, r))) {
					if (TweaksControl.isLeaves(level.getBlockState(n))) leaves.add(n.immutable());
				}
			}
			spreadLeaves(level, leaves, fel.leafRadius);
		}
		return new Tree(new ArrayList<>(logs), warts, roots, leaves);
	}

	/** Leaves connected to the ones found, up to radius blocks further out. */
	private static void spreadLeaves(ServerLevel level, Set<BlockPos> leaves, int radius) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(leaves);
		int depth = 0;
		while (!queue.isEmpty() && depth < radius && leaves.size() < 4096) {
			int n = queue.size();
			for (int i = 0; i < n; i++) {
				BlockPos p = queue.poll();
				for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
					BlockPos q = p.relative(d);
					if (!leaves.contains(q) && TweaksControl.isLeaves(level.getBlockState(q))) {
						leaves.add(q);
						queue.add(q);
					}
				}
			}
			depth++;
		}
	}

	private static void vein(ServerLevel level, ServerPlayer player, BlockPos origin, BlockState originState, Tweaks.Veins veins) {
		String kind = kind(originState);
		List<BlockPos> found = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin);
		seen.add(origin);
		while (!queue.isEmpty() && found.size() < veins.maxBlocks - 1) {
			BlockPos p = queue.poll();
			for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
				BlockPos m = n.immutable();
				if (!seen.add(m)) continue;
				BlockState s = level.getBlockState(m);
				if (TweaksControl.isOre(s) && kind(s).equals(kind)) {
					found.add(m);
					queue.add(m);
					if (found.size() >= veins.maxBlocks - 1) break;
				}
			}
		}
		if (found.isEmpty()) return;
		found.sort(Comparator.comparingDouble(b -> b.distSqr(origin)));
		breakAll(level, player, origin, found, veins.preserveTool, veins.durability, veins.dropsAtBreakPoint, true);
	}

	/** Iron ore and deepslate iron ore are one vein. */
	private static String kind(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id.getNamespace() + ":" + id.getPath().replace("deepslate_", "");
	}

	/**
	 * Breaks the blocks as the player, stopping when the tool would break or is put away. The tool wears
	 * wear times as fast as mining them one by one. particles: show the breaking to the player too
	 * (the game leaves them out for whoever breaks the block). Returns the blocks broken.
	 */
	private static List<TreeFall.Piece> breakAll(ServerLevel level, ServerPlayer player, BlockPos origin, List<BlockPos> blocks, boolean preserveTool,
			double wear, boolean dropsAtOrigin, boolean particles) {
		List<TreeFall.Piece> broken = new ArrayList<>();
		ItemStack tool = player.getMainHandItem();
		busy = true;
		BlockPos before = dropPoint;
		dropPoint = dropsAtOrigin ? origin : null;
		ToolWear wearing = new ToolWear(wear, preserveTool);
		try {
			for (BlockPos p : blocks) {
				ItemStack now = player.getMainHandItem();
				if (now != tool || now.isEmpty()) break;
				if (preserveTool && now.isDamageableItem() && now.getMaxDamage() - now.getDamageValue() <= 1) {
					player.sendOverlayMessage(Component.literal("Your tool is almost broken: the rest stays.").withStyle(ChatFormatting.GOLD));
					Plop.play(player);
					break;
				}
				BlockState state = level.getBlockState(p);
				if (state.isAir()) continue;
				int damage = now.getDamageValue();
				if (!player.gameMode.destroyBlock(p)) continue;
				broken.add(new TreeFall.Piece(p, state));
				if (particles) player.connection.send(new ClientboundLevelEventPacket(2001, p, Block.getId(state), false));
				wearing.apply(player, now, damage);
			}
		} finally {
			busy = false;
			dropPoint = before;
		}
		return broken;
	}

	private static void scheduleDecay(ServerLevel level, Set<BlockPos> leaves) {
		long now = level.getGameTime();
		for (BlockPos p : leaves) decays.add(new Decay(level, p, now + 20 + level.getRandom().nextInt(50)));
	}

	/** Leaves left without a log nearby drop now instead of over the next minutes. */
	static void tick(MinecraftServer server) {
		if (decays.isEmpty()) return;
		int done = 0;
		var it = decays.iterator();
		while (it.hasNext() && done < 96) {
			Decay d = it.next();
			if (d.level().getGameTime() < d.due()) continue;
			it.remove();
			done++;
			BlockState s = d.level().getBlockState(d.pos());
			if (!s.hasProperty(LeavesBlock.DISTANCE) || !s.hasProperty(LeavesBlock.PERSISTENT)) continue;
			if (s.getValue(LeavesBlock.PERSISTENT) || s.getValue(LeavesBlock.DISTANCE) < LeavesBlock.DECAY_DISTANCE) continue;
			Block.dropResources(s, d.level(), d.pos());
			d.level().removeBlock(d.pos(), false);
		}
	}
}
