package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerSettings;

@Mixin(DedicatedServer.class)
public interface DedicatedServerAccessor {
	@Accessor("settings")
	DedicatedServerSettings nicecontrolcenter$settings();
}
