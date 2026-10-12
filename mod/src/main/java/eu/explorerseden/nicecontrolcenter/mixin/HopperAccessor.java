package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.HopperBlockEntity;

@Mixin(HopperBlockEntity.class)
public interface HopperAccessor {
	@Accessor("cooldownTime")
	void nicecontrolcenter$setCooldownTime(int ticks);
}
