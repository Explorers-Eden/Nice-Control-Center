package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.world.level.biome.Biome;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** /fillbiome run by a data pack for a player (Nice Things' biome brews): biome cells in claims they can't build on stay (ClaimBiomeResolverMixin). */
@Mixin(FillBiomeCommand.class)
public abstract class ClaimFillBiomeMixin {
	@WrapMethod(method = "fill(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder$Reference;Ljava/util/function/Predicate;)I")
	private static int nicecontrolcenter$actor(CommandSourceStack source, BlockPos from, BlockPos to, Holder.Reference<Biome> biome, Predicate<Holder<Biome>> filter,
			Operation<Integer> original) {
		boolean started = ClaimProtection.beginPackEdit(source);
		try {
			return original.call(source, from, to, biome, filter);
		} finally {
			if (started) ClaimProtection.end();
		}
	}
}
