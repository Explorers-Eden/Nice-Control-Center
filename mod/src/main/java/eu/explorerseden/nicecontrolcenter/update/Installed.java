package eu.explorerseden.nicecontrolcenter.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Mods and data packs that can be updated: top-level jars in mods/ and zips in the world's datapacks folder. */
public final class Installed {
	/** {@code key} is "mod:<id>" or "pack:<file name>". */
	public record Item(String key, String kind, String id, String name, String version, Path file, String sha1, String sha512) {
	}

	private Installed() {
	}

	public static List<Item> scan(Path datapackDir) {
		List<Item> items = new ArrayList<>();
		Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModOrigin origin = mod.getOrigin();
			if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1 || mod.getContainingMod().isPresent()) {
				continue;
			}
			Path file = origin.getPaths().get(0).toAbsolutePath().normalize();
			if (!file.getFileName().toString().endsWith(".jar") || !modsDir.equals(file.getParent())) {
				continue;
			}
			String[] hashes = hashes(file);
			if (hashes == null) {
				continue;
			}
			String id = mod.getMetadata().getId();
			items.add(new Item("mod:" + id, "mod", id, mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(),
					file, hashes[0], hashes[1]));
		}
		if (datapackDir != null && Files.isDirectory(datapackDir)) {
			try (Stream<Path> files = Files.list(datapackDir)) {
				for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList()) {
					String[] hashes = hashes(file);
					if (hashes == null) {
						continue;
					}
					String name = file.getFileName().toString();
					String id = name.substring(0, name.length() - 4);
					items.add(new Item("pack:" + name, "datapack", id, id, "", file.toAbsolutePath().normalize(), hashes[0], hashes[1]));
				}
			} catch (IOException e) {
				// No data packs folder readable: only mods are checked.
			}
		}
		return items;
	}

	/** {sha1, sha512} of a file, or null if it can't be read. */
	static String[] hashes(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
			byte[] buffer = new byte[65536];
			int n;
			while ((n = in.read(buffer)) > 0) {
				sha1.update(buffer, 0, n);
				sha512.update(buffer, 0, n);
			}
			return new String[] {HexFormat.of().formatHex(sha1.digest()), HexFormat.of().formatHex(sha512.digest())};
		} catch (IOException | NoSuchAlgorithmException e) {
			return null;
		}
	}
}
