package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;

/** Explosions leave claimed blocks alone (and light no fire there) unless the claim allows explosions. */
@Mixin(ServerExplosion.class)
public abstract class ClaimExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> nicecontrolcenter$keepClaimed(List<BlockPos> blocks) {
		return filter(blocks);
	}

	@ModifyVariable(method = "createFire", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> nicecontrolcenter$noFireInClaims(List<BlockPos> blocks) {
		return filter(blocks);
	}

	private List<BlockPos> filter(List<BlockPos> blocks) {
		if (!ClaimStore.enabled() || blocks.isEmpty()) return blocks;
		boolean any = false;
		for (BlockPos p : blocks) {
			if (!ClaimProtection.explosionAllowed(level, p)) {
				any = true;
				break;
			}
		}
		if (!any) return blocks;
		return blocks.stream().filter(p -> ClaimProtection.explosionAllowed(level, p)).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
	}
}
