package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import eu.explorerseden.nicecontrolcenter.tweaks.AntiXray;

/** Chunk packets measure and write the sections with hidden ores (the same copies for both, so the size fits). */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class AntiXrayChunkMixin {
	@WrapOperation(method = { "calculateChunkSize", "extractChunkData" },
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
	private static LevelChunkSection[] nicecontrolcenter$hideOres(LevelChunk chunk, Operation<LevelChunkSection[]> original) {
		LevelChunkSection[] real = original.call(chunk);
		return AntiXray.active() ? AntiXray.sections(chunk, real) : real;
	}
}
