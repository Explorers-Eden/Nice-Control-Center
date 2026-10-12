package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;

@Mixin(TicketStorage.class)
public interface TicketStorageAccessor {
	@Accessor("tickets")
	Long2ObjectOpenHashMap<List<Ticket>> nicecontrolcenter$tickets();
}
