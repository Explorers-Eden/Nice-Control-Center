package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.resources.ResourceManager;

import eu.explorerseden.nicecontrolcenter.core.QuickActions;

/** The resources the server's registries are loaded from: see {@link QuickActions} (the game worlds' menus). */
@Mixin(RegistryDataLoader.class)
public abstract class QuickActionsLoaderMixin {
	@Inject(method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
	private static void nicecontrolcenter$resources(ResourceManager manager, List<HolderLookup.RegistryLookup<?>> lookups, List<RegistryDataLoader.RegistryData<?>> data, Executor executor,
			CallbackInfoReturnable<CompletableFuture<RegistryAccess.Frozen>> cir) {
		QuickActions.loading(manager);
	}
}
