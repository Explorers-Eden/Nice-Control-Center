package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** A saving chunk keeps at most so many arrows, XP orbs, snowballs… (Paper's entity-per-chunk-save-limit). */
@Mixin(EntityStorage.class)
public abstract class EntitySaveLimitMixin {
	@WrapOperation(method = "storeEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/ChunkEntities;getEntities()Ljava/util/stream/Stream;"))
	private Stream<Entity> nicecontrolcenter$limit(ChunkEntities<Entity> chunk, Operation<Stream<Entity>> original) {
		Stream<Entity> all = original.call(chunk);
		if (!PerfControl.saveLimitsOn()) return all;
		Map<EntityType<?>, int[]> seen = new HashMap<>();
		return all.filter(e -> {
			int limit = PerfControl.saveLimit(e.getType());
			return limit < 0 || ++seen.computeIfAbsent(e.getType(), t -> new int[1])[0] <= limit;
		});
	}
}
