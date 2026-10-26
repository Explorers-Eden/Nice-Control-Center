package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.players.Perks;

/** The tool care reward: tools and armour of a player with it lose half the durability (rounded by chance, so it's fair on average). */
@Mixin(ItemStack.class)
public abstract class ToolCareMixin {
	@Inject(method = "processDurabilityChange", at = @At("RETURN"), cancellable = true)
	private void nicecontrolcenter$care(int amount, ServerLevel level, ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
		int wear = cir.getReturnValueI();
		if (player == null || wear <= 0 || !Perks.has(player.getStringUUID(), Perks.Kind.TOOLCARE)) return;
		double half = wear / 2.0;
		int whole = (int) half;
		cir.setReturnValue(whole + (level.getRandom().nextDouble() < half - whole ? 1 : 0));
	}
}
