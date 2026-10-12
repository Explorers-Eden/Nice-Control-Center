package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;

/** The server list icon, swapped while in maintenance. */
@Mixin(MinecraftServer.class)
public interface StatusIconAccessor {
	@Accessor("statusIcon")
	ServerStatus.Favicon nicecontrolcenter$statusIcon();

	@Accessor("statusIcon")
	void nicecontrolcenter$setStatusIcon(ServerStatus.Favicon icon);
}
