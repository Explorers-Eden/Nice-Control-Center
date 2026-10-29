package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.Claims.Role;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps what's in a claim safe: players need a role there (or the claim's switch for everyone) to
 * use doors and buttons, open storage, touch animals, or build. The world itself (explosions, fire,
 * fluids and pistons from outside, mobs) is checked by mixins through the helpers at the bottom.
 * Nothing is checked while claims are off.
 */
public final class ClaimProtection {
	private static final Map<UUID, Integer> lastWarned = new HashMap<>();

	private ClaimProtection() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register(ClaimTool::useBlock);
		UseItemCallback.EVENT.register(ClaimTool::useItem);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !(player instanceof ServerPlayer sp)
				|| GameClaims.itemAllows(sp, level, pos, pos, sp.getMainHandItem(), true) || allowed(sp, level, pos, Role.BUILD));
		UseBlockCallback.EVENT.register(ClaimProtection::useBlock);
		UseItemCallback.EVENT.register(ClaimProtection::useItem);
		// The claim tool never goes into item frames, armor stands or to animals.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> ClaimMode.isTool(player.getItemInHand(hand)) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entityAction(player, level, entity, false));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entityAction(player, level, entity, true));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(ClaimProtection::allowDamage);
	}

	public static void forget(UUID id) {
		lastWarned.remove(id);
	}

	// ---------------------------------------------------------------- players

	/** May the player do something needing {@code needed} at pos? Tells them why not. */
	public static boolean allowed(ServerPlayer player, Level level, BlockPos pos, Role needed) {
		if (!ClaimStore.enabled() || ClaimTool.bypassing(player)) return true;
		Claim c = ClaimStore.claimAt(level, pos);
		if (c == null || ClaimStore.data().allows(c, player.getStringUUID(), needed)) return true;
		deny(player, c, needed);
		return false;
	}

	private static void deny(ServerPlayer player, Claim c, Role needed) {
		int tick = player.level().getServer().getTickCount();
		Integer last = lastWarned.get(player.getUUID());
		if (last != null && tick - last < 30) return;
		lastWarned.put(player.getUUID(), tick);
		String who = c.owner == null ? "the server" : c.ownerName;
		String what = switch (needed) {
			case ACCESS -> "use that";
			case CONTAINERS -> "open or take that";
			default -> "build";
		};
		ClaimTool.bar(player, "You can't " + what + " here: this land belongs to " + who + ".", ChatFormatting.RED);
		ClaimVisuals.show(player, c.isSub() ? ClaimStore.byId(c.parent) : c, 100);
	}

	private static InteractionResult useBlock(Player p, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(p instanceof ServerPlayer player) || !ClaimStore.enabled()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		// A head hunt's heads are there to be clicked by anyone.
		if (eu.explorerseden.nicecontrolcenter.games.HeadHunt.isHuntHead(level, pos)) return InteractionResult.PASS;
		BlockState state = level.getBlockState(pos);
		ItemStack held = player.getItemInHand(hand);
		Role needed;
		BlockPos target = pos;
		BlockPos front = null;
		boolean usesBlock = !player.isSecondaryUseActive() || held.isEmpty();
		Role blockRole = usesBlock ? blockRole(state, level.getBlockEntity(pos)) : null;
		if (blockRole != null) {
			needed = blockRole;
		} else if (held.isEmpty()) {
			return InteractionResult.PASS;
		} else {
			// Placing or using an item on the block: counts where it lands.
			needed = Role.BUILD;
			if (held.getItem() instanceof BlockItem && !state.canBeReplaced()) {
				target = pos.relative(hit.getDirection());
			} else {
				// Flint and steel, spawn eggs, frames and the like also put something on the block in front: from
				// outside a claim's edge, that's inside it.
				BlockPos beside = pos.relative(hit.getDirection());
				Claim there = ClaimStore.claimAt(level, beside);
				if (there != null && there != ClaimStore.claimAt(level, pos)) front = beside;
			}
		}
		// Game claims in adventure mode: can_place_on on the item lets it be placed or used there.
		if (blockRole == null && GameClaims.itemAllows(player, level, pos, front == null ? target : front, held, false)) return InteractionResult.PASS;
		if (allowed(player, level, target, needed) && (target == pos || allowed(player, level, pos, needed == Role.BUILD ? Role.ACCESS : needed))
				&& (front == null || allowed(player, level, front, Role.BUILD))) {
			return InteractionResult.PASS;
		}
		resync(player, level, pos, front == null ? target : front);
		return InteractionResult.FAIL;
	}

	/**
	 * What a right-click on the block needs, or null when the block does nothing by itself (then the held
	 * item counts). Storage and blocks with an inventory need CONTAINERS; doors, switches and work blocks
	 * without storage ACCESS; blocks whose settings change when clicked BUILD.
	 */
	private static Role blockRole(BlockState state, BlockEntity be) {
		Block b = state.getBlock();
		if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
				|| b instanceof BedBlock || b instanceof BellBlock || b instanceof CraftingTableBlock || b instanceof EnchantingTableBlock
				|| b instanceof AnvilBlock || b instanceof GrindstoneBlock || b instanceof StonecutterBlock || b instanceof LoomBlock
				|| b instanceof CartographyTableBlock || b instanceof SmithingTableBlock || b instanceof RespawnAnchorBlock) {
			return Role.ACCESS;
		}
		if (be instanceof net.minecraft.world.Container || b instanceof JukeboxBlock || b instanceof ChiseledBookShelfBlock || b instanceof DecoratedPotBlock
				|| b instanceof CampfireBlock || b instanceof FlowerPotBlock || b instanceof ComposterBlock || b instanceof AbstractCauldronBlock
				|| b instanceof BeehiveBlock || b instanceof SweetBerryBushBlock || b instanceof CaveVinesBlock || b instanceof CaveVinesPlantBlock
				|| b instanceof CakeBlock || b instanceof CandleCakeBlock || b instanceof LecternBlock || b instanceof EnderChestBlock) {
			return b instanceof EnderChestBlock ? Role.ACCESS : Role.CONTAINERS;
		}
		if (b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof NoteBlock || b instanceof DaylightDetectorBlock
				|| b instanceof CandleBlock || b == Blocks.REDSTONE_WIRE || b instanceof CommandBlock || b instanceof StructureBlock
				|| b instanceof SignBlock || b instanceof VaultBlock || b instanceof CrafterBlock) {
			return Role.BUILD;
		}
		if (be != null && be.getType() != null && !(b instanceof SpawnerBlock)) return Role.CONTAINERS;
		return null;
	}

	private static InteractionResult useItem(Player p, Level level, InteractionHand hand) {
		if (!(p instanceof ServerPlayer player) || !ClaimStore.enabled()) return InteractionResult.PASS;
		ItemStack held = player.getItemInHand(hand);
		if (!(held.getItem() instanceof BucketItem) && !(held.getItem() instanceof BoatItem)) return InteractionResult.PASS;
		HitResult hit = player.pick(player.blockInteractionRange(), 1.0f, true);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
		BlockPos pos = block.getBlockPos();
		BlockPos beside = pos.relative(block.getDirection());
		if (allowed(player, level, pos, Role.BUILD) && allowed(player, level, beside, Role.BUILD)) return InteractionResult.PASS;
		resync(player, level, pos, beside);
		return InteractionResult.FAIL;
	}

	/** Using or hitting an entity that isn't alive (frames, vehicles) or using a living one. */
	private static InteractionResult entityAction(Player p, Level level, Entity entity, boolean attack) {
		if (!(p instanceof ServerPlayer player) || !ClaimStore.enabled()) return InteractionResult.PASS;
		Role needed = entityRole(entity, attack);
		if (needed == null) return InteractionResult.PASS;
		if (allowed(player, level, entity.blockPosition(), needed)) return InteractionResult.PASS;
		player.inventoryMenu.sendAllDataToRemote();
		return InteractionResult.FAIL;
	}

	/** What touching the entity needs; null = anyone may (unnamed monsters, players: PvP is its own switch). */
	private static Role entityRole(Entity e, boolean attack) {
		if (e instanceof Player || e instanceof Enemy && !e.hasCustomName()) return null;
		// Interaction entities only exist for data packs to notice clicks (graves, waypoint hubs, the spawn's
		// buttons and signs): anyone may click them either way, the pack decides what's allowed.
		if (e instanceof net.minecraft.world.entity.Interaction) return null;
		// Cushions are seats: anyone may sit, only builders may hit (break) them.
		// (By id: older Minecraft versions this mod is built for don't have them.)
		if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("minecraft:cushion")) return attack ? Role.BUILD : null;
		if (e instanceof LivingEntity && attack && !(e instanceof ArmorStand)) return null; // Damage is checked in allowDamage.
		if (e instanceof AbstractVillager) return attack ? Role.CONTAINERS : Role.ACCESS;
		if (e instanceof ArmorStand || e instanceof HangingEntity || e instanceof net.minecraft.world.entity.vehicle.VehicleEntity
				|| e instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity) {
			// Getting into a boat or minecart is fine, opening one with a chest isn't; breaking or changing it isn't either.
			if (!attack && e instanceof net.minecraft.world.entity.vehicle.ContainerEntity) return Role.CONTAINERS;
			return !attack && e instanceof net.minecraft.world.entity.vehicle.VehicleEntity ? null : Role.BUILD;
		}
		return Role.CONTAINERS;
	}

	/** Players hurting animals, villagers, named monsters (a farm's zombie), armor stands (also with arrows) or other players (PvP switch). */
	private static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
		if (!ClaimStore.enabled() || !(source.getEntity() instanceof ServerPlayer attacker) || attacker == victim) return true;
		if (ClaimTool.bypassing(attacker)) return true;
		Level level = victim.level();
		if (victim instanceof Player) {
			Claim at = ClaimStore.claimAt(level, victim.blockPosition());
			Claim from = ClaimStore.claimAt(level, attacker.blockPosition());
			if ((at == null || at.flags.pvp) && (from == null || from.flags.pvp)) return true;
			ClaimTool.bar(attacker, "No fighting in claims here.", ChatFormatting.RED);
			return false;
		}
		if (victim instanceof Enemy && !victim.hasCustomName()) return true;
		return allowed(attacker, level, victim.blockPosition(), victim instanceof ArmorStand ? Role.BUILD : Role.CONTAINERS);
	}

	/** After a refused click the client shows the real blocks and items again. */
	private static void resync(ServerPlayer player, Level level, BlockPos... positions) {
		for (BlockPos pos : positions) {
			player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
			player.connection.send(new ClientboundBlockUpdatePacket(level, pos.above()));
			player.connection.send(new ClientboundBlockUpdatePacket(level, pos.below()));
		}
		player.inventoryMenu.sendAllDataToRemote();
		player.containerMenu.sendAllDataToRemote();
	}

	// ---------------------------------------------------------------- the world (mixins)

	/** Things moving from one spot to another (fluids, pistons): fine unless they cross into someone else's claim. */
	public static boolean crossingBlocked(Level level, BlockPos from, BlockPos to) {
		if (!ClaimStore.enabled()) return false;
		String dim = ClaimStore.dim(level);
		Claim target = ClaimStore.topAt(dim, to.getX(), to.getZ());
		if (target == null) return false;
		Claim source = ClaimStore.topAt(dim, from.getX(), from.getZ());
		if (source == target) return false;
		return source == null || !java.util.Objects.equals(source.owner, target.owner);
	}

	public static boolean explosionAllowed(Level level, BlockPos pos) {
		if (!ClaimStore.enabled()) return true;
		Claim c = ClaimStore.claimAt(level, pos);
		return c == null || c.flags.explosions;
	}

	public static boolean fireAllowed(Level level, BlockPos pos) {
		if (!ClaimStore.enabled()) return true;
		Claim c = ClaimStore.claimAt(level, pos);
		return c == null || c.flags.fireSpread;
	}

	// ---------------------------------------------------------------- enchantments and data packs acting for someone

	/**
	 * Who is changing blocks right now on this thread, when it isn't a player's own click: an enchantment effect
	 * (Frost Walker and data pack walkers placing ice, magma or grass; set_block_properties) for its wearer, or a
	 * data pack function running as a player (a biome brew's fillbiome, a bloom scroll's place feature).
	 */
	private static final class Actor {
		final Entity entity;
		/** Walking over water would warn on every step; pack edits say once at the end what stayed. */
		final boolean silent;
		final Actor outer;
		int skipped;

		Actor(Entity entity, boolean silent, Actor outer) {
			this.entity = entity;
			this.silent = silent;
			this.outer = outer;
		}
	}

	private static final ThreadLocal<Actor> actor = new ThreadLocal<>();
	/** Fast path for the setBlock hook: nothing is acting for anyone anywhere. */
	private static volatile int actors;

	/** An enchantment effect starts changing blocks for this entity (the wearer). */
	public static void beginEffect(Entity entity) {
		begin(entity, true);
	}

	/**
	 * A command starts: a data pack acting for a player when the command runs as that player with suppressed output
	 * (advancement rewards, enchantment functions); a command the player typed isn't suppressed and isn't checked.
	 * Returns whether something was started, for {@link #end}.
	 */
	public static boolean beginPackEdit(net.minecraft.commands.CommandSourceStack source) {
		if (!ClaimStore.enabled() || !source.isSilent() || !(source.getEntity() instanceof ServerPlayer player)) return false;
		begin(player, false);
		return true;
	}

	private static void begin(Entity entity, boolean silent) {
		actor.set(new Actor(entity, silent, actor.get()));
		actors++;
	}

	public static void end() {
		Actor a = actor.get();
		if (a == null) return;
		actor.set(a.outer);
		actors--;
		if (!a.silent && a.skipped > 0 && a.entity instanceof ServerPlayer player) {
			ClaimTool.bar(player, "Claimed land you can't build on stayed as it was.", ChatFormatting.RED);
		}
	}

	public static boolean acting() {
		return actors > 0 && actor.get() != null;
	}

	/**
	 * A block (or biome cell) about to change while something acts for someone: a player needs to be a builder there,
	 * a mob needs the claim's mob switch. Server thread.
	 */
	public static boolean actorMayChange(Level level, BlockPos pos) {
		if (actors == 0 || level.isClientSide()) return true;
		Actor a = actor.get();
		if (a == null || !ClaimStore.enabled()) return true;
		Claim c = ClaimStore.claimAt(level, pos);
		boolean ok = c == null || (a.entity instanceof ServerPlayer player
				? ClaimTool.bypassing(player) || ClaimStore.data().allows(c, player.getStringUUID(), Role.BUILD)
				: c.flags.mobGriefing);
		if (!ok) a.skipped++;
		return ok;
	}

	public static boolean mobGriefingAllowed(Level level, BlockPos pos) {
		if (!ClaimStore.enabled()) return true;
		Claim c = ClaimStore.claimAt(level, pos);
		return c == null || c.flags.mobGriefing;
	}

	/**
	 * A projectile hitting something in a claim: the player who shot it needs the role there (they're told
	 * why not); a mob's projectile needs the mob switch; projectiles from dispensers and the like are fine.
	 */
	public static boolean projectileAllowed(net.minecraft.world.entity.projectile.Projectile projectile, BlockPos pos, Role needed) {
		if (!ClaimStore.enabled()) return true;
		Level level = projectile.level();
		Entity owner = projectile.getOwner();
		if (owner instanceof ServerPlayer player) return allowed(player, level, pos, needed);
		if (owner instanceof net.minecraft.world.entity.Mob) return mobGriefingAllowed(level, pos);
		return true;
	}

	/** Explosions hurting what's in a claim: animals, villagers, named monsters, frames and armor stands are safe unless explosions are allowed there. */
	public static boolean explosionMayHurt(Entity e) {
		if (!ClaimStore.enabled() || e instanceof Player || e instanceof Enemy && !e.hasCustomName()) return true;
		return explosionAllowed(e.level(), e.blockPosition());
	}

	/** Farmland: players need to be builders, other mobs need the mob switch. */
	public static boolean tramplingAllowed(Level level, BlockPos pos, Entity entity) {
		if (!ClaimStore.enabled()) return true;
		Claim c = ClaimStore.claimAt(level, pos);
		if (c == null) return true;
		Entity rider = entity.getControllingPassenger();
		Entity who = rider instanceof Player ? rider : entity;
		if (who instanceof ServerPlayer player) return ClaimTool.bypassing(player) || ClaimStore.data().allows(c, player.getStringUUID(), Role.BUILD);
		return c.flags.mobGriefing;
	}
}
