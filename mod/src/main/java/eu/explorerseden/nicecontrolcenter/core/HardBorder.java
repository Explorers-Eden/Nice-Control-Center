package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.Borders;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps players inside the hard border of config/nicecontrolcenter-borders.json (see Borders): whoever
 * gets past it (walking, flying, pearls, portals) is put back where they last stood inside. Works for
 * circles too, which the vanilla world border can't do. A square hard border also sets the vanilla world
 * border of that dimension (unless worldBorder is off). The file is read again when it changes.
 */
public final class HardBorder {
	/** Checked this often (ticks); a player at full elytra speed gets about 10 blocks past. */
	private static final int EVERY = 5;
	private static final int RELOAD_EVERY = 100;

	private record Spot(String dimension, Vec3 pos, float yRot, float xRot) {
	}

	private static volatile Map<String, Borders.Border> borders = Map.of();
	private static long modified = -1;
	private static final Map<UUID, Spot> lastInside = new ConcurrentHashMap<>();

	private HardBorder() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Borders.FILE);
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (modified < 0 || tick % RELOAD_EVERY == 0) reload(server);
		if (tick % EVERY != 0 || borders.isEmpty()) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) check(player);
	}

	/** The borders as last read, by dimension id. */
	public static Map<String, Borders.Border> borders() {
		return borders;
	}

	private static void reload(MinecraftServer server) {
		Path file = file();
		long now;
		try {
			now = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
		} catch (java.io.IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		borders = Map.copyOf(Borders.read(file));
		long hard = borders.values().stream().filter(b -> b.hard > 0).count();
		if (hard > 0) NiceControlCenter.LOGGER.info("Hard border active in {} dimension{}", hard, hard == 1 ? "" : "s");
		borders.forEach((dim, b) -> {
			if (b.hard <= 0 || b.circle() || !b.worldBorder) return;
			Identifier id = Identifier.tryParse(dim);
			ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
			if (level == null) return;
			WorldBorder wb = level.getWorldBorder();
			double size = b.hard * 2.0;
			if (wb.getCenterX() != b.x || wb.getCenterZ() != b.z) wb.setCenter(b.x, b.z);
			if (wb.getSize() != size) wb.setSize(size);
		});
	}

	private static void check(ServerPlayer player) {
		if (player.isSpectator() || player.isRemoved()) return;
		ServerLevel level = player.level();
		String dim = level.dimension().identifier().toString();
		Borders.Border b = borders.get(dim);
		if (b == null || b.hard <= 0 || b.inside(player.getX(), player.getZ(), b.hard)) {
			lastInside.put(player.getUUID(), new Spot(dim, player.position(), player.getYRot(), player.getXRot()));
			return;
		}
		Spot back = lastInside.get(player.getUUID());
		Vec3 target = back != null && back.dimension().equals(dim) && b.inside(back.pos().x, back.pos().z, b.hard - 1) ? back.pos() : nearestInside(level, b, player);
		if (player.isPassenger()) player.stopRiding();
		player.teleportTo(level, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), false);
		player.setDeltaMovement(Vec3.ZERO);
		player.sendOverlayMessage(Component.literal("You've reached the edge of the world.").withStyle(ChatFormatting.GOLD));
	}

	/** Pulls the position back a few blocks inside the border and finds room to stand there. */
	private static Vec3 nearestInside(ServerLevel level, Borders.Border b, ServerPlayer player) {
		double dx = player.getX() - b.x;
		double dz = player.getZ() - b.z;
		int r = Math.max(1, b.hard - 4);
		double x;
		double z;
		if (b.circle()) {
			double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
			x = b.x + dx / len * r;
			z = b.z + dz / len * r;
		} else {
			x = b.x + Math.max(-r, Math.min(r, dx));
			z = b.z + Math.max(-r, Math.min(r, dz));
		}
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.dimensionType().hasCeiling()) {
			return new Vec3(bx + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz + 0.5);
		}
		// Nether-like: look down from the player's height for floor with two free blocks above.
		int from = Math.min((int) player.getY(), level.getMaxY() - 2);
		for (int y = from; y > level.getMinY(); y--) {
			BlockPos feet = new BlockPos(bx, y, bz);
			if (level.getBlockState(feet.below()).isSolid() && !level.getBlockState(feet).isSolid()
					&& !level.getBlockState(feet.above()).isSolid() && level.getFluidState(feet).isEmpty()) {
				return new Vec3(bx + 0.5, y, bz + 0.5);
			}
		}
		return new Vec3(bx + 0.5, player.getY(), bz + 0.5);
	}

	public static void forget(UUID player) {
		lastInside.remove(player);
	}
}
