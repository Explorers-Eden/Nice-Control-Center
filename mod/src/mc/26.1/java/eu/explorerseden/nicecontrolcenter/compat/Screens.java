package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.1: screens are switched on Minecraft itself. */
public final class Screens {
	private Screens() {
	}

	public static void show(Minecraft minecraft, Screen screen) {
		minecraft.setScreen(screen);
	}
}
