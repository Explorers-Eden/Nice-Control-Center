package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** 26.3 has the sulfur cube (this mixin makes it hurt on touch in games); before it there's nothing to change. */
@Mixin(net.minecraft.world.entity.monster.Slime.class)
public abstract class GameSulfurCubeMixin {
}
