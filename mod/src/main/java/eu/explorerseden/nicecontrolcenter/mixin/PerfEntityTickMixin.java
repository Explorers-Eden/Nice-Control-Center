package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Frozen chunks and the entity activation range: some entity ticks are skipped (see PerfControl). */
@Mixin(ServerLevel.class)
public abstract class PerfEntityTickMixin {
	@WrapMethod(method = "tickNonPassenger")
	private void nicecontrolcenter$activation(Entity entity, Operation<Void> original) {
		if (PerfControl.skip((ServerLevel) (Object) this, entity)) return;
		original.call(entity);
	}
}
