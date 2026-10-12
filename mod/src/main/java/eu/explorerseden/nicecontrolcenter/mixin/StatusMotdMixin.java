package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.core.MaintenanceControl;

/**
 * Maintenance mode's MOTD in the server list only: setMotd would also write server.properties on a
 * dedicated server, which a crash during maintenance would leave behind.
 */
@Mixin(MinecraftServer.class)
public abstract class StatusMotdMixin {
	@WrapOperation(method = "buildServerStatus", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getMotd()Ljava/lang/String;"))
	private String nicecontrolcenter$maintenanceMotd(MinecraftServer server, Operation<String> original) {
		String motd = MaintenanceControl.motd();
		return motd != null ? motd : original.call(server);
	}
}
