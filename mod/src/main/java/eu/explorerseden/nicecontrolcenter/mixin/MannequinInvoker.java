package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.Mannequin;

/** NPC talking lines show in the mannequin's description (the line under its name). */
@Mixin(Mannequin.class)
public interface MannequinInvoker {
	@Invoker("setDescription")
	void nicecontrolcenter$setDescription(Component description);

	@Invoker("setHideDescription")
	void nicecontrolcenter$setHideDescription(boolean hide);
}
