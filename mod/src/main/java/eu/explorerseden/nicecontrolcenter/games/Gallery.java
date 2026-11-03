package eu.explorerseden.nicecontrolcenter.games;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;

/**
 * Snowball gallery (quick round "gallery"): mobs from the pool walk along the lanes (set in game), one after another
 * and faster as the round goes on; a snowball (or any shot) that hits one scores its points (galleryScores; 1 when
 * not listed, villagers cost by default). They can't be hurt otherwise and don't fight back.
 */
final class Gallery {
	private static final String WALKER = "ncc_gallery_";

	private Gallery() {
	}

	/** Lanes with both ends set. */
	private static List<Games.Lane> lanes(Arena a) {
		return a.galleryLanes.stream().filter(l -> l.from != null && l.to != null && l.from.dim.equals(l.to.dim)).toList();
	}

	/** "minecraft:villager=-5" → points per mob type (mobs not listed: 1). */
	static Map<String, Integer> scores(Arena a) {
		Map<String, Integer> out = new HashMap<>();
		for (String line : a.galleryScores) {
			int eq = line.lastIndexOf('=');
			if (eq < 0) continue;
			String id = line.substring(0, eq).strip().toLowerCase(java.util.Locale.ROOT);
			try {
				out.put(id.contains(":") ? id : "minecraft:" + id, Integer.parseInt(line.substring(eq + 1).strip()));
			} catch (NumberFormatException e) {
				// Skipped.
			}
		}
		return out;
	}

	static void tick(MinecraftServer server, ServerLevel level, QuickRuntime.Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		List<Games.Lane> lanes = lanes(a);
		if (lanes.isEmpty()) return;
		long now = System.currentTimeMillis();
		// 15 % faster every 15 seconds.
		double speed = a.gallerySpeed * (1 + 0.15 * (r.elapsed() / 15_000.0));
		for (Iterator<Map.Entry<UUID, double[]>> it = r.walkers.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, double[]> e = it.next();
			Entity m = level.getEntity(e.getKey());
			int li = (int) e.getValue()[0];
			if (m == null || !m.isAlive() || li >= lanes.size()) {
				if (m != null) m.discard();
				it.remove();
				continue;
			}
			Games.Lane l = lanes.get(li);
			double len = Math.max(0.5, Math.sqrt(l.from.distanceSq(l.to)));
			double t = e.getValue()[1] + speed * 0.1 / len;
			if (t >= 1) {
				m.discard();
				it.remove();
				continue;
			}
			e.getValue()[1] = t;
			double x = l.from.x + (l.to.x - l.from.x) * t, y = l.from.y + (l.to.y - l.from.y) * t, z = l.from.z + (l.to.z - l.from.z) * t;
			float yaw = (float) Math.toDegrees(Math.atan2(-(l.to.x - l.from.x), l.to.z - l.from.z));
			m.setPos(x, y, z);
			m.clearFire();
			m.setYRot(yaw);
			m.setYHeadRot(yaw);
			if (m instanceof LivingEntity le) le.setYBodyRot(yaw);
		}
		// Kit rounds keep the snowballs coming.
		if (QuickRuntime.kit(a) && level.getGameTime() % 20 == 0) {
			for (ServerPlayer p : alive) {
				int have = p.getInventory().countItem(Items.SNOWBALL);
				if (have < 8) GameClaims.giveKitItem(p, new ItemStack(Items.SNOWBALL, 16 - have), "quick");
			}
		}
		// Newly summoned ones: found by their own tag, then walking.
		for (Entity m : level.getEntitiesOfClass(Entity.class, box(a), x -> x.isAlive() && x.entityTags().contains(r.tag()) && !r.walkers.containsKey(x.getUUID()))) {
			String own = m.entityTags().stream().filter(t -> t.startsWith(WALKER)).findFirst().orElse(null);
			if (own == null) continue;
			m.removeTag(own);
			String[] k = own.substring(WALKER.length()).split("_");
			r.walkers.put(m.getUUID(), new double[] { Integer.parseInt(k[0]), 0 });
		}
		if (now < r.nextWalker || r.walkers.size() >= a.maxAlive) return;
		List<MobLines.Entry> pool = a.pool.stream().map(MobLines::parse).filter(e -> e != null && !e.id().startsWith("npc:")).toList();
		if (pool.isEmpty()) return;
		Random random = QuickRuntime.random();
		// More often as it goes on.
		r.nextWalker = now + Math.round((700 + random.nextInt(900)) / (1 + r.elapsed() / 40_000.0));
		int li = random.nextInt(lanes.size());
		Games.Lane l = lanes.get(li);
		MobLines.Entry e = pool.get(random.nextInt(pool.size()));
		String tag = WALKER + li + "_" + (r.walkerCount++);
		MobLines.summon(server, l.from.dim, l.from.x, l.from.y, l.from.z, new MobLines.Entry(e.id(), 1, e.nbt()),
				"NoAI:1b,Silent:1b,Invulnerable:1b,NoGravity:1b,Tags:[\"" + r.tag() + "\",\"" + tag + "\"]");
	}

	/** Around every lane (where its mobs are). */
	private static AABB box(Arena a) {
		AABB box = null;
		for (Games.Lane l : lanes(a)) {
			AABB b = new AABB(l.from.x, l.from.y, l.from.z, l.to.x, l.to.y, l.to.z).inflate(3);
			box = box == null ? b : box.minmax(b);
		}
		return box == null ? QuickRuntime.box(a) : box;
	}

	/** A shot by a player in the round, from → to this tick: true when it hit a walking mob (the shot is used up). */
	static boolean shot(ServerLevel level, QuickRuntime.Round r, ServerPlayer p, Projectile shot, Vec3 from, Vec3 to) {
		for (UUID id : r.walkers.keySet()) {
			Entity m = level.getEntity(id);
			if (m == null || !m.isAlive()) continue;
			AABB bb = m.getBoundingBox().inflate(0.3);
			if (!bb.contains(from) && bb.clip(from, to).isEmpty()) continue;
			r.walkers.remove(id);
			shot.discard();
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
			int points = scores(r.arena).getOrDefault(type, 1);
			Vec3 c = m.getBoundingBox().getCenter();
			m.discard();
			QuickRuntime.add(r, p, points, null);
			if (points >= 0) {
				level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 10, 0.3, 0.3, 0.3, 0.05);
				level.sendParticles(ParticleTypes.ITEM_SNOWBALL, c.x, c.y, c.z, 8, 0.3, 0.3, 0.3, 0.1);
				QuickRuntime.ding(p, SoundEvents.ARROW_HIT_PLAYER, 0.8f, 1.2f + Math.min(0.6f, points * 0.1f));
			} else {
				level.sendParticles(ParticleTypes.ANGRY_VILLAGER, c.x, c.y, c.z, 4, 0.3, 0.3, 0.3, 0);
				QuickRuntime.ding(p, SoundEvents.VILLAGER_HURT, 0.8f, 1.0f);
			}
			return true;
		}
		return false;
	}

	/** Why it can't run yet (null when it can). */
	static String check(Arena a) {
		if (lanes(a).isEmpty()) return a.name + " has no lanes yet (/game edit " + a.name + " lane add at one end, then lane end at the other).";
		if (a.pool.stream().map(MobLines::parse).noneMatch(e -> e != null && !e.id().startsWith("npc:"))) return a.name + " has no mobs to walk the lanes (mob pool in the panel).";
		return null;
	}
}
