package eu.explorerseden.nicecontrolcenter.players;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the panel's web map needs from inside the game: where each dimension's region files are, the
 * world border, online players, GOML claims and the Explorer's Eden waypoint hubs, plus the map color
 * of every registered block (modded ones too). The panel renders the tiles itself from the region files.
 */
public final class MapExport {
	private MapExport() {
	}

	/** Server thread. */
	public static Map<String, Object> overlays(MinecraftServer server, String storageId) {
		Path serverDir = Path.of("").toAbsolutePath();
		Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> dims = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			Map<String, Object> d = new LinkedHashMap<>();
			Identifier id = level.dimension().identifier();
			d.put("id", id.toString());
			d.put("name", PlayerInspector.dimension(id.toString()));
			Path folder = DimensionType.getStorageFolder(level.dimension(), root).resolve("region").toAbsolutePath().normalize();
			d.put("regions", serverDir.relativize(folder).toString().replace('\\', '/'));
			d.put("minY", level.dimensionType().minY());
			d.put("height", level.dimensionType().height());
			d.put("ceiling", level.dimensionType().hasCeiling());
			var border = level.getWorldBorder();
			d.put("border", Map.of("x", border.getCenterX(), "z", border.getCenterZ(), "size", border.getSize()));
			BlockPos spawn = level.getRespawnData() == null ? BlockPos.ZERO : level.getRespawnData().pos();
			d.put("spawn", Map.of("x", spawn.getX(), "z", spawn.getZ()));
			dims.add(d);
		}
		out.put("dimensions", dims);

		List<Map<String, Object>> players = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator()) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", p.getUUID().toString());
			m.put("name", p.getGameProfile().name());
			m.put("dim", p.level().dimension().identifier().toString());
			m.put("x", (int) Math.floor(p.getX()));
			m.put("y", (int) Math.floor(p.getY()));
			m.put("z", (int) Math.floor(p.getZ()));
			players.add(m);
		}
		out.put("players", players);
		out.put("claims", claims(server, root));
		out.put("hubs", hubs(server, storageId));
		out.put("time", System.currentTimeMillis());
		return out;
	}

	private static List<Map<String, Object>> claims(MinecraftServer server, Path root) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!FabricLoader.getInstance().isModLoaded("goml")) {
			return out;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Identifier dim = level.dimension().identifier();
			Path file = root.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath()).resolve("data")
					.resolve("cardinal-components").resolve("world.dat");
			CompoundTag data = Files.exists(file) ? PlayerInspector.read(file) : null;
			if (data == null) {
				continue;
			}
			ListTag claims = data.getCompoundOrEmpty("data").getCompoundOrEmpty("cardinal_components").getCompoundOrEmpty("goml:claims").getListOrEmpty("Claims");
			for (int i = 0; i < claims.size(); i++) {
				CompoundTag claim = claims.getCompoundOrEmpty(i);
				CompoundTag box = claim.getCompoundOrEmpty("Box");
				BlockPos origin = BlockPos.of(box.getLongOr("OriginPos", 0));
				int radius = box.getIntOr("Radius", box.getIntOr("radius", 0));
				List<String> owners = new ArrayList<>();
				ListTag ownerList = claim.getListOrEmpty("Owners");
				for (int o = 0; o < ownerList.size(); o++) {
					ownerList.getIntArray(o).filter(a -> a.length == 4).ifPresent(a -> {
						UUID id = net.minecraft.core.UUIDUtil.uuidFromIntArray(a);
						owners.add(PlayerInspector.name(server, id).orElse(id.toString().substring(0, 8)));
					});
				}
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("dim", dim.toString());
				c.put("x", origin.getX());
				c.put("z", origin.getZ());
				c.put("radius", radius);
				c.put("owners", owners);
				String type = claim.getStringOr("Type", "");
				c.put("anchor", PlayerInspector.dimension(type.isEmpty() ? "unknown_anchor" : type));
				out.add(c);
			}
		}
		return out;
	}

	private static List<Map<String, Object>> hubs(MinecraftServer server, String storageId) {
		List<Map<String, Object>> out = new ArrayList<>();
		Identifier id = Identifier.tryParse(storageId == null ? "" : storageId);
		if (id == null) {
			return out;
		}
		CompoundTag hubs = server.getCommandStorage().get(id).getCompoundOrEmpty("waypoints").getCompoundOrEmpty("hubs");
		for (String key : hubs.keySet()) {
			CompoundTag hub = hubs.getCompoundOrEmpty(key);
			CompoundTag pos = hub.getCompoundOrEmpty("pos");
			Map<String, Object> h = new LinkedHashMap<>();
			h.put("dim", pos.getStringOr("dimension", "minecraft:overworld"));
			h.put("x", (int) Math.floor(PlayerInspector.number(pos.get("x"))));
			h.put("y", (int) Math.floor(PlayerInspector.number(pos.get("y"))));
			h.put("z", (int) Math.floor(PlayerInspector.number(pos.get("z"))));
			h.put("name", hub.getStringOr("waypoint_name", "Unnamed Waypoint"));
			h.put("description", hub.getStringOr("waypoint_description", "").trim());
			h.put("access", hub.getStringOr("access", "private"));
			h.put("owner", hub.getCompoundOrEmpty("profile").getStringOr("name", ""));
			out.add(h);
		}
		return out;
	}

	/** The map color of every block's default state, as RGB. */
	public static Map<String, Integer> colors() {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			try {
				int col = block.defaultBlockState().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
				out.put(BuiltInRegistries.BLOCK.getKey(block).toString(), col);
			} catch (RuntimeException e) {
				// A block that needs a real world for its color: left out.
			}
		}
		return out;
	}
}
