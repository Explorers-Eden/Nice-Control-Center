package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.commands.PlaceCommand;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** /place feature run by a data pack for a player (Fabled Roots' bloom scrolls): blocks in claims they can't build on stay. */
@Mixin(PlaceCommand.class)
public abstract class ClaimPlaceFeatureMixin {
	@WrapMethod(method = "placeFeature(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/core/Holder$Reference;Lnet/minecraft/core/BlockPos;)I")
	private static int nicecontrolcenter$actor(CommandSourceStack source, Holder.Reference<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> feature, BlockPos pos, Operation<Integer> original) {
		boolean started = ClaimProtection.beginPackEdit(source);
		try {
			return original.call(source, feature, pos);
		} finally {
			if (started) ClaimProtection.end();
		}
	}
}
