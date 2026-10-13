package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;

@Mixin(ThrownTrident.class)
public interface ThrownTridentAccessor {
	@Accessor("ID_LOYALTY")
	static EntityDataAccessor<Byte> nicecontrolcenter$loyalty() {
		throw new AssertionError();
	}

	@Accessor("dealtDamage")
	void nicecontrolcenter$setDealtDamage(boolean dealt);
}
