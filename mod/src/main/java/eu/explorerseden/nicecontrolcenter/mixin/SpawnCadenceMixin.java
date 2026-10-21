package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Monsters, water mobs and bats try to spawn only every few ticks (Performance → Spawning). */
@Mixin(ServerChunkCache.class)
public abstract class SpawnCadenceMixin {
	@WrapOperation(method = "tickSpawningChunk", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
	private void nicecontrolcenter$spawnCadence(ServerLevel serverLevel, LevelChunk chunk, NaturalSpawner.SpawnState state, List<MobCategory> categories,
			Operation<Void> original) {
		List<MobCategory> now = PerfControl.spawnCategories(serverLevel, categories);
		if (now.isEmpty()) return;
		original.call(serverLevel, chunk, state, now);
	}
}
