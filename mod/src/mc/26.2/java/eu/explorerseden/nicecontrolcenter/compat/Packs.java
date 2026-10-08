package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

import java.util.stream.Stream;

/** Minecraft 26.1–26.2: a pack opens as a single resource set. */
public final class Packs {
	private Packs() {
	}

	public static Stream<PackResources> open(Pack pack) {
		return Stream.of(pack.open());
	}
}
