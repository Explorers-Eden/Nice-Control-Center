package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

@Mixin(PlayerList.class)
public interface PlayerListInvoker {
	/** One player's data, stats and advancements to disk. */
	@Invoker("save")
	void nicecontrolcenter$save(ServerPlayer player);
}
