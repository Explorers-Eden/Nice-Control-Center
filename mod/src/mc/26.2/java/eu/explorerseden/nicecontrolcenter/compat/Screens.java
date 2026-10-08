package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.2+: screens are switched through the GUI. */
public final class Screens {
	private Screens() {
	}

	public static void show(Minecraft minecraft, Screen screen) {
		minecraft.gui.setScreen(screen);
	}
}
