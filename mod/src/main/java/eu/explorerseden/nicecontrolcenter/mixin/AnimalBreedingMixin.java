package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.animal.Animal;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Breeding cap: no love mode while too many of the same kind live nearby. */
@Mixin(Animal.class)
public abstract class AnimalBreedingMixin {
	@ModifyReturnValue(method = "canFallInLove", at = @At("RETURN"))
	private boolean nicecontrolcenter$cap(boolean can) {
		return can && PerfControl.mayBreed((Animal) (Object) this);
	}
}
