package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.Properties;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.dedicated.Settings;

/** The raw key/value pairs behind server.properties. */
@Mixin(Settings.class)
public interface SettingsAccessor {
	@Accessor("properties")
	Properties nicecontrolcenter$properties();
}
