package eu.explorerseden.nicecontrolcenter.tweaks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/**
 * Dispensers place blocks (Gameplay tweak): a block item the dispenser would otherwise just drop is placed in
 * front of it, the way vanilla places shulker boxes. When it can't be placed (the spot is taken, the block can't
 * stand there, or it's someone else's claim) nothing happens and the dispenser clicks. Vanilla's own use comes
 * first: in 26.3+ a block shot at a sulfur cube still feeds it.
 */
public final class DispenserPlace implements DispenseItemBehavior {
	private static final Map<DispenseItemBehavior, DispenserPlace> OVER = new ConcurrentHashMap<>();

	/** What vanilla would have done (drop it, or feed a sulfur cube). */
	private final DispenseItemBehavior original;

	private DispenserPlace(DispenseItemBehavior original) {
		this.original = original;
	}

	public static DispenserPlace over(DispenseItemBehavior original) {
		return OVER.computeIfAbsent(original, DispenserPlace::new);
	}

	@Override
	public ItemStack dispense(BlockSource source, ItemStack stack) {
		ServerLevel level = source.level();
		Direction facing = source.state().getValue(DispenserBlock.FACING);
		BlockPos target = source.pos().relative(facing);
		if (!(stack.getItem() instanceof BlockItem block) || sulfurCubeAt(level, target)) return original.dispense(source, stack);
		boolean placed = false;
		// Like pistons: not into someone else's land claim.
		if (!ClaimProtection.crossingBlocked(level, source.pos(), target)) {
			Direction against = level.isEmptyBlock(target.below()) ? facing : Direction.UP;
			placed = block.place(new DirectionalPlaceContext(level, target, facing, stack, against)).consumesAction();
		}
		// The dispenser's click (or failed click) and smoke, as for any dispensed item.
		level.levelEvent(placed ? 1000 : 1001, source.pos(), 0);
		level.levelEvent(2000, source.pos(), facing.get3DDataValue());
		return stack;
	}

	private static boolean sulfurCubeAt(ServerLevel level, BlockPos pos) {
		return !level.getEntities((Entity) null, new AABB(pos), e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("sulfur_cube")).isEmpty();
	}
}
