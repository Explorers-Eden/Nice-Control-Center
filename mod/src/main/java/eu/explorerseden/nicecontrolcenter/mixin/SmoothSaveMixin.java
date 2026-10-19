package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;

import eu.explorerseden.nicecontrolcenter.core.SmoothSave;

/**
 * Smooth autosave, chunks: the autosave only notes which loaded chunks changed, and they're written a few
 * per tick afterwards (see SmoothSave). Saves with flush (save-all flush, stop) are untouched.
 */
@Mixin(ChunkMap.class)
public abstract class SmoothSaveMixin {
	/** At most this long per tick for the waiting chunks. */
	@Unique
	private static final long BUDGET_NANOS = 1_500_000;
	@Unique
	private static final int MAX_PER_TICK = 40;
	/** ChunkMap.MAX_ACTIVE_CHUNK_WRITES: past this many writes in flight, wait for the disk. */
	@Unique
	private static final int MAX_WRITES = 128;

	@Shadow
	private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
	@Shadow
	@Final
	private Long2LongMap nextChunkSaveTime;
	@Shadow
	@Final
	private AtomicInteger activeChunkWrites;
	@Shadow
	@Final
	private ServerLevel level;

	@Shadow
	private boolean saveChunkIfNeeded(ChunkHolder holder, long now) {
		throw new AssertionError();
	}

	@Unique
	private final LongLinkedOpenHashSet nicecontrolcenter$waiting = new LongLinkedOpenHashSet();

	@Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$saveAll(boolean flush, CallbackInfo ci) {
		if (flush || !SmoothSave.autosaving()) {
			// Vanilla saves every changed chunk now.
			nicecontrolcenter$waiting.clear();
			return;
		}
		// What the last autosave still had waiting goes now, as vanilla would have saved it.
		nicecontrolcenter$drain(Integer.MAX_VALUE, Long.MAX_VALUE);
		nextChunkSaveTime.clear();
		for (ChunkHolder holder : visibleChunkMap.values()) {
			ChunkAccess chunk = holder.getLatestChunk();
			if (chunk != null && chunk.isUnsaved()) nicecontrolcenter$waiting.add(holder.getPos().pack());
		}
		ci.cancel();
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
	private void nicecontrolcenter$saveSome(BooleanSupplier haveTime, CallbackInfo ci) {
		if (nicecontrolcenter$waiting.isEmpty() || level.noSave()) return;
		nicecontrolcenter$drain(MAX_PER_TICK, System.nanoTime() + BUDGET_NANOS);
	}

	@Unique
	private void nicecontrolcenter$drain(int max, long deadline) {
		long now = Util.getMillis();
		int saved = 0;
		while (!nicecontrolcenter$waiting.isEmpty() && saved < max) {
			if (max != Integer.MAX_VALUE && (activeChunkWrites.get() >= MAX_WRITES || System.nanoTime() > deadline)) return;
			// Chunks unloaded meanwhile were saved as they unloaded.
			ChunkHolder holder = visibleChunkMap.get(nicecontrolcenter$waiting.removeFirstLong());
			if (holder != null && saveChunkIfNeeded(holder, now)) saved++;
		}
	}
}
