package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import eu.explorerseden.nicecontrolcenter.npcs.NpcRuntime;

/** NPC drivers (the hidden mob a walking or fighting NPC's body follows) are never sent to players. */
@Mixin(Entity.class)
public abstract class NpcDriverHiddenMixin {
	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$hidden(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (((Entity) (Object) this).entityTags().contains(NpcRuntime.DRIVER)) cir.setReturnValue(false);
	}
}
