package eu.explorerseden.nicecontrolcenter.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For the pets' Watchful Eye: monsters glow on one player's screen only. */
@Mixin(Entity.class)
public interface EntityFlagsAccessor {
	@Accessor("DATA_SHARED_FLAGS_ID")
	static EntityDataAccessor<Byte> nicecontrolcenter$flags() {
		throw new AssertionError();
	}
}
