package eu.explorerseden.nicecontrolcenter.games;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Box corners for dungeons and wave arenas: a position given in the command ("~ ~12 ~" works), else the block the
 * admin looks at (64 blocks), else the block at their feet. While a box is being set, its edges show as particles.
 */
public final class Corners {
	private record Outline(String dim, BlockPos a, BlockPos b, long until) {
	}

	private static final Map<UUID, Outline> outlines = new HashMap<>();

	private Corners() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0 && !outlines.isEmpty()) draw(server);
		});
	}

	/** The corner for a command: its "pos" argument when there is one, else what the player looks at or stands in. */
	static BlockPos pick(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
		try {
			return BlockPosArgument.getBlockPos(ctx, "pos");
		} catch (IllegalArgumentException noArgument) {
			HitResult hit = p.pick(64, 1f, false);
			if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
			return p.blockPosition();
		}
	}

	/** Shows the box between two corners (b may be null: just the first corner) to the player for 30 seconds. */
	static void show(ServerPlayer p, BlockPos a, BlockPos b) {
		outlines.put(p.getUUID(), new Outline(p.level().dimension().identifier().toString(), a, b == null ? a : b, System.currentTimeMillis() + 30_000));
	}

	static String size(BlockPos a, BlockPos b) {
		return (Math.abs(a.getX() - b.getX()) + 1) + " × " + (Math.abs(a.getY() - b.getY()) + 1) + " × " + (Math.abs(a.getZ() - b.getZ()) + 1);
	}

	private static void draw(MinecraftServer server) {
		long now = System.currentTimeMillis();
		outlines.entrySet().removeIf(e -> e.getValue().until < now || server.getPlayerList().getPlayer(e.getKey()) == null);
		for (Map.Entry<UUID, Outline> e : outlines.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Outline o = e.getValue();
			if (p == null || !p.level().dimension().identifier().toString().equals(o.dim)) continue;
			ServerLevel level = (ServerLevel) p.level();
			double x0 = Math.min(o.a.getX(), o.b.getX()), y0 = Math.min(o.a.getY(), o.b.getY()), z0 = Math.min(o.a.getZ(), o.b.getZ());
			double x1 = Math.max(o.a.getX(), o.b.getX()) + 1, y1 = Math.max(o.a.getY(), o.b.getY()) + 1, z1 = Math.max(o.a.getZ(), o.b.getZ()) + 1;
			// The 12 edges, with at most ~40 points each.
			double[][] edges = {
					{x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
					{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
					{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
			for (double[] ed : edges) {
				double len = Math.max(Math.abs(ed[3] - ed[0]), Math.max(Math.abs(ed[4] - ed[1]), Math.abs(ed[5] - ed[2])));
				int steps = (int) Math.max(1, Math.min(40, len));
				for (int i = 0; i <= steps; i++) {
					double t = i / (double) steps;
					level.sendParticles(p, ParticleTypes.END_ROD, true, false, ed[0] + (ed[3] - ed[0]) * t, ed[1] + (ed[4] - ed[1]) * t, ed[2] + (ed[5] - ed[2]) * t, 1, 0, 0, 0, 0);
				}
			}
		}
	}
}
