package eu.explorerseden.nicecontrolcenter.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.dedicated.DedicatedServerSettings;

/** Where server.properties is saved. */
@Mixin(DedicatedServerSettings.class)
public interface DedicatedServerSettingsAccessor {
	@Accessor("source")
	Path nicecontrolcenter$source();
}
