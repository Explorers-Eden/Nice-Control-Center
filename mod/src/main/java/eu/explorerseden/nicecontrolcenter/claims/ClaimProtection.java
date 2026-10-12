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
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !(player instanceof ServerPlayer sp) || allowed(sp, level, pos, Role.BUILD));
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
		BlockState state = level.getBlockState(pos);
		ItemStack held = player.getItemInHand(hand);
		Role needed;
		BlockPos target = pos;
		boolean usesBlock = !player.isSecondaryUseActive() || held.isEmpty();
		Role blockRole = usesBlock ? blockRole(state, level.getBlockEntity(pos)) : null;
		if (blockRole != null) {
			needed = blockRole;
		} else if (held.isEmpty()) {
			return InteractionResult.PASS;
		} else {
			// Placing or using an item on the block: counts where it lands.
			needed = Role.BUILD;
			if (held.getItem() instanceof BlockItem && !state.canBeReplaced()) target = pos.relative(hit.getDirection());
		}
		if (allowed(player, level, target, needed) && (target == pos || allowed(player, level, pos, needed == Role.BUILD ? Role.ACCESS : needed))) {
			return InteractionResult.PASS;
		}
		resync(player, level, pos, target);
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

	/** What touching the entity needs; null = anyone may (monsters, players: PvP is its own switch). */
	private static Role entityRole(Entity e, boolean attack) {
		if (e instanceof Player || e instanceof Enemy) return null;
		if (e instanceof LivingEntity && attack && !(e instanceof ArmorStand)) return null; // Damage is checked in allowDamage.
		if (e instanceof AbstractVillager) return attack ? Role.CONTAINERS : Role.ACCESS;
		if (e instanceof ArmorStand || e instanceof HangingEntity || e instanceof net.minecraft.world.entity.vehicle.VehicleEntity
				|| e instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity) {
			// Getting into a boat or minecart is fine; breaking or changing it isn't.
			return !attack && e instanceof net.minecraft.world.entity.vehicle.VehicleEntity ? null : Role.BUILD;
		}
		return Role.CONTAINERS;
	}

	/** Players hurting animals, villagers, armor stands (also with arrows) or other players (PvP switch). */
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
		if (victim instanceof Enemy) return true;
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

	/** Explosions hurting what's in a claim: animals, villagers, frames and armor stands are safe unless explosions are allowed there. */
	public static boolean explosionMayHurt(Entity e) {
		if (!ClaimStore.enabled() || e instanceof Player || e instanceof Enemy) return true;
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
