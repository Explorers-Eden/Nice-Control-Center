package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Bone meal on more plants: sugar cane and cactus grow a block, nether wart ages, lily pads and small
 * flowers spread nearby, vines grow down. Vanilla doesn't grow any of them with bone meal, so this only
 * adds; the claim rules for building apply.
 */
public final class BoneMeal {
	private BoneMeal() {
	}

	static void register() {
		UseBlockCallback.EVENT.register(BoneMeal::use);
	}

	private static InteractionResult use(Player p, Level lvl, InteractionHand hand, BlockHitResult hit) {
		if (!(p instanceof ServerPlayer player) || !(lvl instanceof ServerLevel level) || player.isSpectator() || eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.dungeonWorld(level)) return InteractionResult.PASS;
		ItemStack held = player.getItemInHand(hand);
		if (!held.is(Items.BONE_MEAL)) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		Tweaks.BoneMeal s = TweaksControl.settings().boneMeal;
		boolean ours = s.sugarCane && state.is(Blocks.SUGAR_CANE) || s.cactus && state.is(Blocks.CACTUS)
				|| s.netherWart && state.is(Blocks.NETHER_WART) || s.lilyPads && state.is(Blocks.LILY_PAD)
				|| s.vines && state.is(Blocks.VINE) || s.flowers && state.is(BlockTags.SMALL_FLOWERS) && !state.is(Blocks.WITHER_ROSE);
		if (!ours) return InteractionResult.PASS;
		if (!ClaimProtection.allowed(player, level, pos, Claims.Role.BUILD)) return InteractionResult.FAIL;
		BlockPos grown = grow(level, pos, state, s, level.getRandom());
		if (grown == null) return InteractionResult.FAIL;
		if (!player.hasInfiniteMaterials()) held.shrink(1);
		// The sound (vanilla's event; its particles only show for blocks the game grows itself), and the
		// green sparkles like vanilla: in the block that grew, or around it for plants that spread.
		level.levelEvent(1505, grown, 15);
		boolean spreads = state.is(Blocks.LILY_PAD) || state.is(BlockTags.SMALL_FLOWERS);
		double r = spreads ? 1.2 : 0.3;
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, grown.getX() + 0.5, grown.getY() + 0.5, grown.getZ() + 0.5, spreads ? 30 : 15, r, spreads ? 0.3 : 0.3, r, 0);
		return InteractionResult.SUCCESS;
	}

	/** Grows the plant; the block that changed, or null when nothing could grow. */
	private static BlockPos grow(ServerLevel level, BlockPos pos, BlockState state, Tweaks.BoneMeal s, RandomSource random) {
		Block block = state.getBlock();
		if (block == Blocks.SUGAR_CANE || block == Blocks.CACTUS) {
			BlockPos bottom = pos;
			while (level.getBlockState(bottom.below()).is(block)) bottom = bottom.below();
			BlockPos top = pos;
			while (level.getBlockState(top.above()).is(block)) top = top.above();
			if (top.getY() - bottom.getY() + 1 >= s.maxHeight) return null;
			BlockPos up = top.above();
			if (!level.getBlockState(up).isAir() || !block.defaultBlockState().canSurvive(level, up)) return null;
			level.setBlock(up, block.defaultBlockState(), 3);
			return up;
		}
		if (block == Blocks.NETHER_WART) {
			int age = state.getValue(NetherWartBlock.AGE);
			if (age >= NetherWartBlock.MAX_AGE) return null;
			level.setBlock(pos, state.setValue(NetherWartBlock.AGE, age + 1), 2);
			return pos;
		}
		if (block == Blocks.VINE) {
			BlockPos end = pos;
			while (level.getBlockState(end.below()).is(Blocks.VINE)) end = end.below();
			BlockPos down = end.below();
			if (!level.getBlockState(down).isAir()) return null;
			// The new piece hangs on the same sides, without the top.
			BlockState hanging = level.getBlockState(end).setValue(VineBlock.UP, false);
			level.setBlock(down, hanging, 3);
			return down;
		}
		if (block == Blocks.LILY_PAD) {
			for (int i = 0; i < 16; i++) {
				BlockPos p = pos.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
				if (level.getBlockState(p).isAir() && level.getFluidState(p.below()).is(Fluids.WATER) && level.getBlockState(p.below()).is(Blocks.WATER)) {
					level.setBlock(p, Blocks.LILY_PAD.defaultBlockState(), 3);
					return p;
				}
			}
			return null;
		}
		// Small flowers: a copy on free ground nearby.
		int r = s.flowerRadius;
		for (int i = 0; i < 24; i++) {
			BlockPos p = pos.offset(random.nextInt(r * 2 + 1) - r, random.nextInt(3) - 1, random.nextInt(r * 2 + 1) - r);
			if (level.getBlockState(p).isAir() && state.canSurvive(level, p)) {
				level.setBlock(p, state, 3);
				return p;
			}
		}
		return null;
	}
}
