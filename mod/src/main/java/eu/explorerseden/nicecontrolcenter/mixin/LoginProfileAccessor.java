package eu.explorerseden.nicecontrolcenter.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Who is logging in, for the panel's login check while the login waits (PanelBridge). */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface LoginProfileAccessor {
	@Accessor("authenticatedProfile")
	GameProfile nicecontrolcenter$profile();
}
