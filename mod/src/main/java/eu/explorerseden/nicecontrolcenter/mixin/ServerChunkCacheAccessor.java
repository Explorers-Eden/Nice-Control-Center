package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.TicketStorage;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
	@Accessor("ticketStorage")
	TicketStorage nicecontrolcenter$ticketStorage();
}
