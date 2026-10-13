package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.commands.TellRawCommand;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.web.PanelBridge;

/** /tellraw to everyone online (what data packs use for their announcements and death messages), for the Discord bridge. */
@Mixin(TellRawCommand.class)
public abstract class TellRawBroadcastMixin {
	@Inject(method = "lambda$register$0", at = @At("HEAD"))
	private static void nicecontrolcenter$broadcast(CommandContext<CommandSourceStack> ctx, CallbackInfoReturnable<Integer> cir) {
		if (!PanelBridge.active()) return;
		try {
			Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
			var online = ctx.getSource().getServer().getPlayerList().getPlayers();
			if (online.isEmpty() || targets.size() < online.size() || !targets.containsAll(online)) return;
			ServerPlayer first = targets.iterator().next();
			PanelBridge.tellraw(ctx.getSource().getServer(), ComponentArgument.getResolvedComponent(ctx, "message", first));
		} catch (Exception e) {
			// The command itself reports bad arguments.
		}
	}
}
