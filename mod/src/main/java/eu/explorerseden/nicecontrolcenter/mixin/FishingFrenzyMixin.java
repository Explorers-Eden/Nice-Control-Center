package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import eu.explorerseden.nicecontrolcenter.games.QuickRuntime;

/** Fishing frenzy: a catch in a fishing round scores points instead of landing (QuickRuntime.fished). */
@Mixin(FishingHook.class)
public abstract class FishingFrenzyMixin {
	@WrapOperation(method = "retrieve", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<ItemStack> nicecontrolcenter$frenzy(LootTable table, LootParams params, Operation<ObjectArrayList<ItemStack>> original) {
		ObjectArrayList<ItemStack> loot = original.call(table, params);
		if (((FishingHook) (Object) this).getPlayerOwner() instanceof ServerPlayer p && QuickRuntime.fished(p, loot)) return new ObjectArrayList<>();
		return loot;
	}
}
