package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.Predicate;

import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** While a data pack fills biomes for a player (ClaimFillBiomeMixin), cells in claims they can't build on keep their biome. */
@Mixin(FillBiomeCommand.class)
public abstract class ClaimBiomeResolverMixin {
	@ModifyReturnValue(method = "makeResolver", at = @At("RETURN"))
	private static BiomeResolver nicecontrolcenter$claims(BiomeResolver resolver, MutableInt count, ChunkAccess chunk, BoundingBox box, Holder<Biome> biome,
			Predicate<Holder<Biome>> filter) {
		if (!ClaimProtection.acting() || !(chunk instanceof LevelChunk loaded)) return resolver;
		return (x, y, z) -> ClaimProtection.actorMayChange(loaded.getLevel(), new BlockPos(QuartPos.toBlock(x), QuartPos.toBlock(y), QuartPos.toBlock(z)))
				? resolver.getNoiseBiome(x, y, z)
				: chunk.getNoiseBiome(x, y, z);
	}
}
