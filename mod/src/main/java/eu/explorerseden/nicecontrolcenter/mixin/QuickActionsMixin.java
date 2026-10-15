package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.resources.ResourceKey;

import eu.explorerseden.nicecontrolcenter.core.QuickActions;

/** A registry entry's JSON from a data pack, before it's read: see {@link QuickActions}. */
@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
public abstract class QuickActionsMixin {
	@ModifyExpressionValue(method = "loadFromResource",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StrictJsonParser;parse(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"))
	private static JsonElement nicecontrolcenter$quickActions(JsonElement json, @Local(argsOnly = true) ResourceKey<?> key) {
		return QuickActions.patch(key, json);
	}
}
