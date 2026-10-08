package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.MinecraftServer;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	@Accessor("emptyTicks")
	int nicecontrolcenter$emptyTicks();

	@Invoker("pauseWhenEmptySeconds")
	int nicecontrolcenter$pauseWhenEmptySeconds();
}
