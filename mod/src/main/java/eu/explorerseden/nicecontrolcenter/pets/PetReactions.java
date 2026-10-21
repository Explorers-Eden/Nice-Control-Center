package eu.explorerseden.nicecontrolcenter.pets;

import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;
import eu.explorerseden.nicecontrolcenter.core.Plop;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What every pet reacts to: a short move, particles, a chime and maybe a speech bubble everyone nearby sees
 * (lines and on/off per reaction from the panel). Warnings also tell the owner above their hotbar. Owners
 * switch reactions off in /pets. Each reaction waits its own time before it comes again, and there's a gap
 * between reactions (warnings and once-only ones don't wait for it). Server thread.
 */
final class PetReactions {
	private static final class State {
		final Map<Pets.Reaction, Long> last = new EnumMap<>(Pets.Reaction.class);
		long lastAny = Long.MIN_VALUE / 2;
		boolean underwater;
		boolean sleeping;
		boolean flying;
		boolean riding;
		boolean cold;
		boolean deep;
		boolean idle;
		boolean raining;
		boolean thundering;
		boolean dark;
		boolean high;
		boolean raid;
		ResourceKey<Level> dimension;
		int food = -1;
		int xp = -1;
		int fish = -1;
		int trades = -1;
		int bred = -1;
		int totems = -1;
		int enchants = -1;
		int records = -1;
		int bells = -1;
		long nightDay = -1;
		long morningDay = -1;
		boolean sneaking;
		long sneakAt;
		String birthday;
	}

	private static final Map<UUID, State> states = new HashMap<>();
	/** Joined or died lately: the pet's first reaction when it comes out is "missed you" or "…". */
	private static final Map<UUID, Long> joined = new HashMap<>();
	private static final Set<UUID> died = new HashSet<>();
	/** Two owners whose pets met lately (smaller UUID first), and when. */
	private static final Map<String, Long> met = new HashMap<>();
	/** Beaten, these make a pet cheer the most (registry ids: the constants moved between versions). */
	private static final Set<String> BOSSES = Set.of("ender_dragon", "wither", "warden", "elder_guardian");

	private PetReactions() {
	}

	static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joined.put(handler.player.getUUID(), (long) server.getTickCount()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) died.add(newPlayer.getUUID());
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (entity instanceof ServerPlayer p && taken > 0 && p.isAlive() && p.getHealth() > 6) fire(p, Pets.Reaction.HURT, null);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(source.getEntity() instanceof ServerPlayer p)) return;
			if (BOSSES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())) fire(p, Pets.Reaction.BOSS, null);
			else if (entity instanceof Enemy) fire(p, Pets.Reaction.KILL, null);
		});
		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (overlay || !(message.getContents() instanceof TranslatableContents tr) || !tr.getKey().startsWith("chat.type.advancement.")) return;
			Object[] args = tr.getArgs();
			if (args.length == 0) return;
			String name = args[0] instanceof Component c ? c.getString() : String.valueOf(args[0]);
			ServerPlayer p = server.getPlayerList().getPlayerByName(name);
			if (p != null) fire(p, Pets.Reaction.ADVANCEMENT, null);
		});
	}

	// ---------------------------------------------------------------- from pets and perks

	/** The pet just came out. */
	static void hello(ServerPlayer player) {
		UUID id = player.getUUID();
		if (died.remove(id)) {
			joined.remove(id);
			fire(player, Pets.Reaction.RESPAWN, null);
		} else if (joined.remove(id) != null) {
			fire(player, Pets.Reaction.JOIN, null);
		} else {
			fire(player, Pets.Reaction.HELLO, null);
		}
		birthday(player);
	}

	/** The pet is being put away: true when it says bye (it then goes a moment later). */
	static boolean bye(ServerPlayer player) {
		return fire(player, Pets.Reaction.BYE, null);
	}

	static void levelUp(ServerPlayer player, int level) {
		fire(player, Pets.Reaction.LEVEL_UP, String.valueOf(level));
	}

	static void fished(ServerPlayer player) {
		fire(player, Pets.Reaction.FISH, null);
	}

	/** The owner (or their pet) broke a block. */
	static void broke(ServerPlayer player, BlockState state) {
		if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE) || state.is(Blocks.ANCIENT_DEBRIS)) fire(player, Pets.Reaction.SHINY, null);
		else if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) fire(player, Pets.Reaction.HARVEST, null);
	}

	// ---------------------------------------------------------------- watching

	static void tick(MinecraftServer server) {
		long time = server.getTickCount();
		states.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		// "Missed you" only right after joining.
		joined.values().removeIf(at -> time - at > 1200);
		died.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		if (time % 1200 == 0) met.values().removeIf(t -> time - t > 12_000);
		if (!PetStore.enabled() || !PetStore.settings().reactions.enabled) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (PetFollowers.shownOwned(player.getUUID()) == null) continue;
			State st = states.computeIfAbsent(player.getUUID(), k -> new State());
			pat(player, st, time);
			// The rest once a second, spread over the ticks.
			if ((time + (player.getId() & 15)) % 20 == 0) watch(player, st, time);
		}
	}

	/** Sneaking twice in a second while looking at the pet. */
	private static void pat(ServerPlayer player, State st, long time) {
		boolean sneak = player.isShiftKeyDown();
		if (sneak && !st.sneaking) {
			Vec3 pet = PetFollowers.position(player.getUUID());
			Vec3 eyes = player.getEyePosition();
			boolean looking = pet != null && pet.distanceToSqr(eyes) < 16 && player.getLookAngle().dot(pet.subtract(eyes).normalize()) > 0.9;
			if (looking && time - st.sneakAt <= 20) {
				fire(player, Pets.Reaction.PAT, null);
				st.sneakAt = Long.MIN_VALUE / 2;
			} else {
				st.sneakAt = looking ? time : Long.MIN_VALUE / 2;
			}
		}
		st.sneaking = sneak;
	}

	private static void watch(ServerPlayer player, State st, long time) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		// Warnings.
		ItemStack hand = player.getMainHandItem();
		if (hand.isDamageableItem() && hand.getMaxDamage() - hand.getDamageValue() <= Math.max(1, hand.getMaxDamage() * 0.05)) fire(player, Pets.Reaction.TOOL, null);
		if (player.getHealth() <= 6) fire(player, Pets.Reaction.HEALTH, null);
		else if (player.getFoodData().getFoodLevel() <= 6) fire(player, Pets.Reaction.HUNGER, null);
		if (danger(player)) fire(player, Pets.Reaction.DANGER, null);
		if (player.isUnderWater() && player.getAirSupply() <= 60) fire(player, Pets.Reaction.AIR, null);
		if (inventoryFull(player)) fire(player, Pets.Reaction.INVENTORY, null);
		if ((player.isOnFire() || lavaNear(level, feet)) && !player.hasEffect(MobEffects.FIRE_RESISTANCE)) fire(player, Pets.Reaction.FIRE, null);

		// Things that start: fired once when they do.
		boolean under = player.isUnderWater();
		if (under && !st.underwater) fire(player, Pets.Reaction.UNDERWATER, null);
		st.underwater = under;
		ResourceKey<Level> dim = level.dimension();
		if (st.dimension != null && dim != st.dimension) {
			if (dim == Level.NETHER) fire(player, Pets.Reaction.NETHER, null);
			else if (dim == Level.END) fire(player, Pets.Reaction.END, null);
		}
		st.dimension = dim;
		int food = player.getFoodData().getFoodLevel();
		if (st.food >= 0 && food > st.food) fire(player, Pets.Reaction.EAT, null);
		st.food = food;
		boolean sleeping = player.isSleeping();
		if (sleeping && !st.sleeping) fire(player, Pets.Reaction.SLEEP, null);
		st.sleeping = sleeping;
		int xp = player.experienceLevel;
		if (st.xp >= 0 && st.xp < 30 && xp >= 30) fire(player, Pets.Reaction.LEVEL30, null);
		st.xp = xp;
		if (dim == Level.OVERWORLD) {
			long clock = level.getOverworldClockTime();
			long day = clock / 24000;
			long t = clock % 24000;
			if (t >= 12500 && t < 13500 && st.nightDay != day) {
				st.nightDay = day;
				fire(player, Pets.Reaction.NIGHTFALL, null);
			}
			if (t < 1000 && st.morningDay != day) {
				st.morningDay = day;
				fire(player, Pets.Reaction.MORNING, null);
			}
		}
		boolean deep = dim == Level.OVERWORLD && player.getY() < 0 && !level.canSeeSky(feet);
		if (deep && !st.deep) fire(player, Pets.Reaction.DEEP, null);
		st.deep = deep;
		boolean cold = level.getBiome(feet).value().coldEnoughToSnow(feet, level.getSeaLevel());
		if (cold && !st.cold) fire(player, Pets.Reaction.SNOW, null);
		st.cold = cold;
		boolean outside = level.canSeeSky(feet);
		boolean raining = level.isRaining() && outside;
		if (raining && !st.raining) fire(player, Pets.Reaction.RAIN, null);
		// The rain stopping where they can see the sky (not when they just walk indoors).
		if (st.raining && !raining && outside) fire(player, Pets.Reaction.SUNNY, null);
		st.raining = raining;
		boolean thundering = level.isThundering();
		if (thundering && !st.thundering) fire(player, Pets.Reaction.THUNDER, null);
		st.thundering = thundering;
		boolean dark = !outside && level.getBrightness(LightLayer.BLOCK, feet) == 0 && level.getBrightness(LightLayer.SKY, feet) == 0;
		if (dark && !st.dark) fire(player, Pets.Reaction.DARK, null);
		st.dark = dark;
		boolean high = dim == Level.OVERWORLD && player.getY() > 200;
		if (high && !st.high) fire(player, Pets.Reaction.HIGH, null);
		st.high = high;
		boolean raid = level.getRaidAt(feet) != null;
		if (raid && !st.raid) fire(player, Pets.Reaction.RAID, null);
		st.raid = raid;
		boolean flying = player.isFallFlying() || player.fallDistance > 6;
		if (flying && !st.flying) fire(player, Pets.Reaction.FLYING, null);
		st.flying = flying;
		boolean riding = player.isPassenger();
		if (riding && !st.riding) fire(player, Pets.Reaction.RIDING, null);
		st.riding = riding;
		int fish = player.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
		if (st.fish >= 0 && fish > st.fish) fire(player, Pets.Reaction.FISH, null);
		st.fish = fish;
		int trades = player.getStats().getValue(Stats.CUSTOM.get(Stats.TRADED_WITH_VILLAGER));
		if (st.trades >= 0 && trades > st.trades) fire(player, Pets.Reaction.TRADE, null);
		st.trades = trades;
		int bred = player.getStats().getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED));
		if (st.bred >= 0 && bred > st.bred) fire(player, Pets.Reaction.BREED, null);
		st.bred = bred;
		int totems = player.getStats().getValue(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING));
		if (st.totems >= 0 && totems > st.totems) fire(player, Pets.Reaction.TOTEM, null);
		st.totems = totems;
		int enchants = player.getStats().getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM));
		if (st.enchants >= 0 && enchants > st.enchants) fire(player, Pets.Reaction.ENCHANT, null);
		st.enchants = enchants;
		int records = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_RECORD));
		if (st.records >= 0 && records > st.records) fire(player, Pets.Reaction.MUSIC, null);
		st.records = records;
		int bells = player.getStats().getValue(Stats.CUSTOM.get(Stats.BELL_RING));
		if (st.bells >= 0 && bells > st.bells) fire(player, Pets.Reaction.BELL, null);
		st.bells = bells;
		// Idle, then AFK.
		if (PlayerActivity.isAfk(player)) {
			fire(player, Pets.Reaction.AFK, null);
		} else {
			boolean idle = PlayerActivity.idleTicks(player) >= 1200;
			if (idle && !st.idle) fire(player, Pets.Reaction.IDLE, null);
			st.idle = idle;
		}
		meet(player, time);
		if (musicNear(level, feet) && allowed(player, Pets.Reaction.MUSIC)) PetFollowers.dance(player.getUUID(), 30);
	}

	/** A jukebox playing within 12 blocks (only chunks already loaded). */
	private static boolean musicNear(ServerLevel level, BlockPos feet) {
		int cx = feet.getX() >> 4, cz = feet.getZ() >> 4;
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be instanceof JukeboxBlockEntity j && j.getSongPlayer().isPlaying() && be.getBlockPos().distSqr(feet) <= 144) return true;
				}
			}
		}
		return false;
	}

	/** A hissing creeper close by, or a mob after the owner from behind. */
	private static boolean danger(ServerPlayer player) {
		for (Creeper c : player.level().getEntitiesOfClass(Creeper.class, player.getBoundingBox().inflate(6), Creeper::isAlive)) {
			if (c.getSwellDir() > 0) return true;
		}
		Vec3 look = player.getLookAngle().multiply(1, 0, 1);
		for (Mob m : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(4), m -> m.isAlive() && m.getTarget() == player)) {
			Vec3 to = m.position().subtract(player.position()).multiply(1, 0, 1);
			if (to.lengthSqr() < 1e-4 || look.lengthSqr() < 1e-4) continue;
			// More than 120° away from where they look.
			if (look.normalize().dot(to.normalize()) < -0.5) return true;
		}
		return false;
	}

	/** No room for an item they could pick up right at their feet. */
	private static boolean inventoryFull(ServerPlayer player) {
		if (player.getInventory().getFreeSlot() >= 0) return false;
		for (ItemEntity e : player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(1.5), e -> e.isAlive() && !e.hasPickUpDelay())) {
			if (player.getInventory().getSlotWithRemainingSpace(e.getItem()) < 0) return true;
		}
		return false;
	}

	private static boolean lavaNear(ServerLevel level, BlockPos feet) {
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 0, 1))) {
			if (level.getFluidState(q).is(FluidTags.LAVA)) return true;
		}
		return false;
	}

	/** Two pets close together greet each other (each pair once in a while). */
	private static void meet(ServerPlayer player, long time) {
		for (ServerPlayer other : player.level().players()) {
			if (other == player || other.distanceToSqr(player) > 16 || PetFollowers.shownOwned(other.getUUID()) == null) continue;
			String a = player.getStringUUID();
			String b = other.getStringUUID();
			String key = a.compareTo(b) < 0 ? a + b : b + a;
			Long last = met.get(key);
			if (last != null && time - last < 12_000) continue;
			Vec3 mine = PetFollowers.position(player.getUUID());
			Vec3 theirs = PetFollowers.position(other.getUUID());
			if (mine == null || theirs == null) continue;
			met.put(key, time);
			fireAt(player, Pets.Reaction.MEET, null, theirs);
			fireAt(other, Pets.Reaction.MEET, null, mine);
		}
	}

	/** A year (or more) to the day since the pet came: once that day. */
	private static void birthday(ServerPlayer player) {
		Pets.Owned owned = PetFollowers.shownOwned(player.getUUID());
		if (owned == null || owned.since <= 0) return;
		LocalDate since = java.time.Instant.ofEpochMilli(owned.since).atZone(ZoneId.systemDefault()).toLocalDate();
		LocalDate today = LocalDate.now();
		if (today.getYear() <= since.getYear() || today.getMonthValue() != since.getMonthValue() || today.getDayOfMonth() != since.getDayOfMonth()) return;
		State st = states.computeIfAbsent(player.getUUID(), k -> new State());
		String key = today + owned.pet;
		if (key.equals(st.birthday)) return;
		st.birthday = key;
		fire(player, Pets.Reaction.BIRTHDAY, null);
	}

	// ---------------------------------------------------------------- showing it

	private static boolean fire(ServerPlayer player, Pets.Reaction r, String level) {
		return fireAt(player, r, level, null);
	}

	/** Reactions are on (server, owner and this one) and a pet is out. */
	private static boolean allowed(ServerPlayer player, Pets.Reaction r) {
		Pets.Settings s = PetStore.settings();
		if (!PetStore.enabled() || !s.reactions.enabled) return false;
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		return PetFollowers.shownOwned(player.getUUID()) != null && owner != null && owner.reactions && s.reactions.entry(r).on;
	}

	private static boolean fireAt(ServerPlayer player, Pets.Reaction r, String level, Vec3 lookAt) {
		if (!allowed(player, r)) return false;
		Pets.Settings s = PetStore.settings();
		UUID id = player.getUUID();
		Pets.Owned owned = PetFollowers.shownOwned(id);
		Pets.ReactionEntry entry = s.reactions.entry(r);
		long time = player.level().getServer().getTickCount();
		State st = states.computeIfAbsent(id, k -> new State());
		Long last = st.last.get(r);
		if (last != null && time - last < r.cooldown * 20L) return false;
		// Warnings and once-only reactions (hello, bye, level up…) don't wait for the gap.
		boolean waits = !r.warning && r.cooldown > 0;
		if (waits && time - st.lastAny < s.reactions.gapSeconds * 20L) return false;
		st.last.put(r, time);
		if (waits) st.lastAny = time;
		String line = entry.lines.isEmpty() ? null : entry.lines.get(player.getRandom().nextInt(entry.lines.size())).replace("{level}", level == null ? "" : level);
		PetFollowers.Move move = move(r);
		if (move != null) PetFollowers.animate(id, move, ticks(r, move), lookAt != null ? lookAt : player.getEyePosition());
		if (line != null) PetFollowers.bubble(id, line, 60);
		Vec3 at = PetFollowers.position(id);
		ParticleOptions particle = particle(r);
		if (at != null && particle != null) player.level().sendParticles(particle, at.x, at.y + 0.2, at.z, 5, 0.25, 0.2, 0.25, 0);
		if (at != null && happy(r)) player.level().playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.5f, 1.5f);
		if (r.warning) {
			player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + ": " + (line != null ? line : r.label)).withStyle(ChatFormatting.YELLOW));
			Plop.play(player);
		}
		return true;
	}

	private static PetFollowers.Move move(Pets.Reaction r) {
		return switch (r) {
			case DANGER, SNOW, NETHER, RAID -> PetFollowers.Move.SHIVER;
			case TOOL, INVENTORY -> PetFollowers.Move.HEAD_SHAKE;
			case HEALTH, RESPAWN -> PetFollowers.Move.DROOP;
			case HUNGER, MEET -> PetFollowers.Move.LOOK_AT;
			case AIR, FIRE, FISH, HELLO, EAT, SHINY, KILL, BELL -> PetFollowers.Move.HOP;
			case HARVEST, TRADE -> PetFollowers.Move.NOD;
			case UNDERWATER, BYE, IDLE -> PetFollowers.Move.WIGGLE;
			case DARK, DEEP -> PetFollowers.Move.LOOK_AROUND;
			case PAT, JOIN -> PetFollowers.Move.NUZZLE;
			case MUSIC -> PetFollowers.Move.DANCE;
			case HURT, THUNDER, TOTEM -> PetFollowers.Move.FLINCH;
			case NIGHTFALL, END, RAIN, HIGH, ENCHANT -> PetFollowers.Move.LOOK_UP;
			case SUNNY -> PetFollowers.Move.SHAKE_OFF;
			case RIDING, BREED -> PetFollowers.Move.BOUNCE;
			case SLEEP -> PetFollowers.Move.CURL;
			case MORNING -> PetFollowers.Move.SPIN;
			case FLYING -> PetFollowers.Move.FLIP;
			case ADVANCEMENT, LEVEL30, LEVEL_UP -> PetFollowers.Move.CHEER;
			case BOSS, BIRTHDAY -> PetFollowers.Move.PARTY;
			case AFK -> null;
		};
	}

	/** How long the move plays. */
	private static int ticks(Pets.Reaction r, PetFollowers.Move move) {
		return switch (move) {
			case CURL -> 200;
			case DANCE -> 100;
			case PARTY -> r == Pets.Reaction.BIRTHDAY ? 140 : 100;
			case CHEER, LOOK_AROUND -> 60;
			case FLIP -> 24;
			case SHAKE_OFF, NOD, HEAD_SHAKE -> 30;
			default -> 40;
		};
	}

	private static ParticleOptions particle(Pets.Reaction r) {
		return switch (r) {
			case DANGER -> ParticleTypes.ANGRY_VILLAGER;
			case AIR, UNDERWATER -> ParticleTypes.BUBBLE_POP;
			case FIRE -> ParticleTypes.SMOKE;
			case NETHER -> ParticleTypes.SMALL_FLAME;
			case END -> ParticleTypes.PORTAL;
			case FISH -> ParticleTypes.SPLASH;
			case HARVEST, TRADE -> ParticleTypes.HAPPY_VILLAGER;
			case SNOW -> ParticleTypes.SNOWFLAKE;
			case JOIN, HELLO, BREED, MEET, PAT -> ParticleTypes.HEART;
			case RESPAWN -> ParticleTypes.FALLING_WATER;
			case ADVANCEMENT, BIRTHDAY, TOTEM, BOSS -> ParticleTypes.TOTEM_OF_UNDYING;
			case ENCHANT -> ParticleTypes.ENCHANT;
			case MUSIC, BELL -> ParticleTypes.NOTE;
			case KILL -> ParticleTypes.CRIT;
			case SUNNY -> ParticleTypes.WAX_OFF;
			case RAID -> ParticleTypes.ANGRY_VILLAGER;
			case LEVEL30, LEVEL_UP -> ParticleTypes.FIREWORK;
			case SHINY -> ParticleTypes.END_ROD;
			default -> null;
		};
	}

	private static boolean happy(Pets.Reaction r) {
		return List.of(Pets.Reaction.HELLO, Pets.Reaction.JOIN, Pets.Reaction.ADVANCEMENT, Pets.Reaction.LEVEL30, Pets.Reaction.LEVEL_UP, Pets.Reaction.BIRTHDAY,
				Pets.Reaction.SHINY, Pets.Reaction.MEET, Pets.Reaction.PAT, Pets.Reaction.BREED, Pets.Reaction.HARVEST, Pets.Reaction.FISH, Pets.Reaction.TRADE,
				Pets.Reaction.BOSS, Pets.Reaction.KILL, Pets.Reaction.ENCHANT, Pets.Reaction.SUNNY).contains(r);
	}
}
