package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

import java.util.stream.Stream;

/** Minecraft 26.3: a pack can consist of several resource sets. */
public final class Packs {
	private Packs() {
	}

	public static Stream<PackResources> open(Pack pack) {
		return pack.open();
	}
}
