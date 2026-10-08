package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.ticks.LevelTicks;

import eu.explorerseden.nicecontrolcenter.core.Phase;
import eu.explorerseden.nicecontrolcenter.core.Tracker;

/** Per-dimension tick phases and per-entity tick time. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@WrapMethod(method = "tick")
	private void nicecontrolcenter$world(BooleanSupplier haveTime, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(haveTime);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.WORLD));
		try {
			original.call(haveTime);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapMethod(method = "tickNonPassenger")
	private void nicecontrolcenter$entity(Entity entity, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(entity);
			return;
		}
		Tracker.enter();
		try {
			original.call(entity);
		} finally {
			Tracker.exit(Tracker.bucket.entity(entity.getType()));
			Tracker.bucket.addChunkTime(Tracker.dimension((ServerLevel) (Object) this),
					ChunkPos.pack(entity.getBlockX() >> 4, entity.getBlockZ() >> 4), Tracker.lastElapsedNs);
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
	private void nicecontrolcenter$scheduledTicks(LevelTicks<?> ticks, long time, int max, BiConsumer<?, ?> consumer, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(ticks, time, max, consumer);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.SCHEDULED_TICKS));
		try {
			original.call(ticks, time, max, consumer);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raids;tick(Lnet/minecraft/server/level/ServerLevel;)V"))
	private void nicecontrolcenter$raids(Raids raids, ServerLevel level, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(raids, level);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.RAIDS));
		try {
			original.call(raids, level);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
	private void nicecontrolcenter$chunks(ServerChunkCache chunks, BooleanSupplier haveTime, boolean tickChunks, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(chunks, haveTime, tickChunks);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.CHUNKS));
		try {
			original.call(chunks, haveTime, tickChunks);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"))
	private void nicecontrolcenter$blockEvents(ServerLevel level, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(level);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.BLOCK_EVENTS));
		try {
			original.call(level);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
	private void nicecontrolcenter$entities(EntityTickList list, Consumer<?> action, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(list, action);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.ENTITIES));
		try {
			original.call(list, action);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"))
	private void nicecontrolcenter$blockEntities(ServerLevel level, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(level);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.BLOCK_ENTITIES));
		try {
			original.call(level);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;tick()V"))
	private void nicecontrolcenter$entityManagement(PersistentEntitySectionManager<?> manager, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(manager);
			return;
		}
		Tracker.enterPhase(Tracker.phase((ServerLevel) (Object) this, Phase.ENTITY_MANAGEMENT));
		try {
			original.call(manager);
		} finally {
			Tracker.exitPhase();
		}
	}
}
