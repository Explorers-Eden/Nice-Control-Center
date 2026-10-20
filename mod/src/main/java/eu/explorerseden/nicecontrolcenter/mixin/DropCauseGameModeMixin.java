package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import eu.explorerseden.nicecontrolcenter.pets.PlayerDrops;

/** Pets' Fetch: what drops while a player breaks a block or uses an item on one is theirs. */
@Mixin(ServerPlayerGameMode.class)
public abstract class DropCauseGameModeMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void nicecontrolcenter$breakStart(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		PlayerDrops.push(player);
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void nicecontrolcenter$breakEnd(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		PlayerDrops.pop();
	}

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void nicecontrolcenter$useStart(ServerPlayer p, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		PlayerDrops.push(p);
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void nicecontrolcenter$useEnd(ServerPlayer p, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		PlayerDrops.pop();
	}
}
