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
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import java.util.List;

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
	private static final int PARTICLES_EVERY = 10;
	/** Players see border particles from this far away (blocks), along this much of the line on each side. */
	private static final double SHOW_WITHIN = 24;
	private static final double SHOW_ALONG = 18;
	private static final DustParticleOptions SOFT = new DustParticleOptions(0x6FE89B, 1.4f);
	private static final DustParticleOptions HARD = new DustParticleOptions(0xFF7070, 1.4f);
	/** Pets this close come along when their owner is put back inside. */
	private static final double PET_RANGE = 16;

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
		if (borders.isEmpty()) return;
		if (tick % PARTICLES_EVERY == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) particles(player);
		}
		if (tick % EVERY != 0) return;
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
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file);
		} catch (java.io.IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		borders = Map.copyOf(Borders.read(file));
		long hard = borders.values().stream().filter(b -> b.hard > 0).count();
		if (hard > 0) NiceControlCenter.LOGGER.info("Borders: hard border active in {} dimension{}", hard, hard == 1 ? "" : "s");
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
		bringBack(level, player, target);
		player.sendOverlayMessage(Component.literal("You've reached the edge of the world.").withStyle(ChatFormatting.GOLD));
	}

	/**
	 * Puts the player at target with what belongs to them: the vehicle they ride (and whoever else rides
	 * it), animals on their lead, and pets that follow them (not those told to sit).
	 */
	private static void bringBack(ServerLevel level, ServerPlayer player, Vec3 target) {
		java.util.LinkedHashSet<Entity> along = new java.util.LinkedHashSet<>();
		Map<Entity, Entity> rides = new java.util.LinkedHashMap<>();
		Entity root = player.isPassenger() ? player.getRootVehicle() : null;
		if (root != null) {
			for (Entity p : root.getIndirectPassengers()) rides.put(p, p.getVehicle());
			along.add(root);
			along.addAll(rides.keySet());
		}
		AABB around = player.getBoundingBox().inflate(PET_RANGE);
		for (Entity e : level.getEntities(player, around, e -> e.isAlive() && !(e instanceof Player))) {
			if (e instanceof Leashable l && l.getLeashHolder() == player) along.add(e);
			else if (e instanceof OwnableEntity pet && pet.getOwnerReference() != null && pet.getOwnerReference().getUUID().equals(player.getUUID())
					&& !(e instanceof TamableAnimal t && t.isOrderedToSit()) && !e.isPassenger()) along.add(e);
		}
		rides.keySet().forEach(Entity::stopRiding);
		player.teleportTo(level, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), false);
		player.setDeltaMovement(Vec3.ZERO);
		for (Entity e : along) {
			if (e == player) continue;
			e.teleportTo(level, target.x, target.y, target.z, Set.of(), e.getYRot(), e.getXRot(), false);
			e.setDeltaMovement(Vec3.ZERO);
		}
		rides.forEach((passenger, vehicle) -> passenger.startRiding(vehicle));
	}

	/** Green particles along the soft border (and red ones along a round hard border) near the player. */
	private static void particles(ServerPlayer player) {
		if (player.isRemoved()) return;
		ServerLevel level = player.level();
		Borders.Border b = borders.get(level.dimension().identifier().toString());
		if (b == null || !b.showSoft) return;
		if (b.soft > 0 && (b.hard == 0 || b.hard > b.soft)) line(level, player, b, b.soft, SOFT);
		if (b.hard > 0 && b.circle()) line(level, player, b, b.hard, HARD);
	}

	private static void line(ServerLevel level, ServerPlayer player, Borders.Border b, int radius, DustParticleOptions color) {
		double px = player.getX();
		double pz = player.getZ();
		double y = player.getY();
		List<double[]> points = new java.util.ArrayList<>();
		if (b.circle()) {
			double dx = px - b.x;
			double dz = pz - b.z;
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (Math.abs(dist - radius) > SHOW_WITHIN) return;
			double angle = Math.atan2(dz, dx);
			double span = SHOW_ALONG / radius;
			for (double a = -span; a <= span; a += 1.5 / radius) {
				points.add(new double[] { b.x + Math.cos(angle + a) * radius, b.z + Math.sin(angle + a) * radius });
			}
		} else {
			// Each of the four edges the player is close to.
			for (int side : new int[] { -1, 1 }) {
				double ex = b.x + side * radius;
				if (Math.abs(px - ex) <= SHOW_WITHIN) {
					for (double z = Math.max(b.z - radius, pz - SHOW_ALONG); z <= Math.min(b.z + radius, pz + SHOW_ALONG); z += 1.5) points.add(new double[] { ex, z });
				}
				double ez = b.z + side * radius;
				if (Math.abs(pz - ez) <= SHOW_WITHIN) {
					for (double x = Math.max(b.x - radius, px - SHOW_ALONG); x <= Math.min(b.x + radius, px + SHOW_ALONG); x += 1.5) points.add(new double[] { x, ez });
				}
			}
		}
		for (double[] p : points) {
			for (double h = -0.5; h <= 2.5; h += 1.5) level.sendParticles(player, color, false, true, p[0], y + h, p[1], 1, 0.15, 0.3, 0.15, 0);
		}
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
