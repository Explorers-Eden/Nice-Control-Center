package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.CallFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.resources.Identifier;

import eu.explorerseden.nicecontrolcenter.hubs.Hubs;

/**
 * Warping Wonders sets each hub's arrival message from the block under it every 2 seconds; hubs
 * whose owner switched it in the owner menu skip that, so the menu's choice stays.
 */
@Mixin(CallFunction.class)
public abstract class WaypointHubAnnounceMixin {
	private static final Identifier GET_ID = Identifier.fromNamespaceAndPath("wawo", "waypoint_hub/properties/announcement/get_id");

	@Shadow
	@Final
	private InstantiatedFunction<?> function;

	@Inject(method = "execute(Lnet/minecraft/commands/ExecutionCommandSource;Lnet/minecraft/commands/execution/ExecutionContext;Lnet/minecraft/commands/execution/Frame;)V", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$hubAnnouncement(ExecutionCommandSource<?> source, ExecutionContext<?> context, Frame frame, CallbackInfo ci) {
		if (GET_ID.equals(function.id()) && source instanceof CommandSourceStack stack && Hubs.ownerChoseAnnouncement(stack.getEntity())) ci.cancel();
	}
}
