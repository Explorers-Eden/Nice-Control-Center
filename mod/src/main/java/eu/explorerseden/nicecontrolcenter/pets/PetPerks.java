package eu.explorerseden.nicecontrolcenter.pets;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import eu.explorerseden.nicecontrolcenter.claims.ClaimTool;
import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;
import eu.explorerseden.nicecontrolcenter.tweaks.Felling;
import eu.explorerseden.nicecontrolcenter.mixin.EntityFlagsAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import eu.explorerseden.nicecontrolcenter.compat.Growth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pet bond levels and perks. A pet's bond grows while it's out and its owner is active; its perks (set per pet
 * in the panel) unlock at the bond levels from the settings. Nothing works while the owner is AFK, a spectator
 * or in creative mode, and nothing reaches where the owner's claim rights don't. Server thread.
 */
public final class PetPerks {
	private static final class State {
		String pet;
		int level = -1;
		long bondSaved;
		/** The fake light block clients were sent (never in the world). */
		ServerLevel lightLevel;
		BlockPos lightPos;
		int lightValue;
		long lightSent;
		/** When each perk may act again (game time). */
		final Map<Pets.Perk, Long> next = new EnumMap<>(Pets.Perk.class);
		/** The perk whose outing runs. */
		Pets.Perk outing;
		ItemEntity carrying;
		long fishingTicks;
		/** Natural ore and stone the owner broke lately: position, game time. */
		final ArrayDeque<long[]> mined = new ArrayDeque<>();
		/** The natural tree the owner is chopping (highest log first), until then. */
		List<BlockPos> tree;
		long treeUntil;
		/** Buried blocks Truffle Nose showed, and when. */
		final Map<BlockPos, Long> sniffed = new HashMap<>();
		/** Active ticks towards the next Cook and Tinkerer. */
		long cookTicks;
		long tinkerTicks;
		/** Monsters Watchful Eye made glow for the owner, by entity id. */
		final Map<Integer, Entity> glowing = new HashMap<>();
	}

	private static final Map<UUID, State> states = new HashMap<>();

	private PetPerks() {
	}

	static void tick(MinecraftServer server) {
		long time = server.getTickCount();
		Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, State> e = it.next();
			ServerPlayer gone = server.getPlayerList().getPlayer(e.getKey());
			if (gone == null || PetFollowers.shownOwned(e.getKey()) == null) {
				eyeOff(gone, e.getValue());
				clear(e.getValue());
				it.remove();
			}
		}
		Pets.Settings s = PetStore.settings();
		if (!PetStore.enabled()) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Pets.Owned owned = PetFollowers.shownOwned(player.getUUID());
			Pets.Owner owner = owned == null ? null : PetStore.data().owners.get(player.getStringUUID());
			if (owner == null) continue;
			State st = states.computeIfAbsent(player.getUUID(), k -> new State());
			if (!s.perks.enabled || PlayerActivity.isAfk(player) || player.isSpectator() || player.isCreative() || !player.isAlive()) {
				lightOff(st);
				eyeOff(player, st);
				continue;
			}
			// The bond grows; a new level is celebrated (not when another pet comes out).
			owned.bond++;
			int level = Pets.level(owned.bond, s.perks);
			if (!owned.pet.equals(st.pet)) {
				st.pet = owned.pet;
				st.level = level;
			} else if (level > st.level) {
				levelUp(player, owned, st.level, level, s.perks);
				st.level = level;
			}
			if (time - st.bondSaved >= 1200) {
				st.bondSaved = time;
				PetStore.changed();
			}
			List<Pets.Perk> perks = PetStore.activePerks(owner, owned);
			if (perks.contains(Pets.Perk.LANTERN) || perks.contains(Pets.Perk.NIGHT_EYES)) lantern(player, st, perks.contains(Pets.Perk.NIGHT_EYES), s.perks, time);
			else lightOff(st);
			if (perks.contains(Pets.Perk.WATCHFUL_EYE)) {
				if (time % 20 == 0) watchfulEye(player, st, s.perks);
			} else {
				eyeOff(player, st);
			}
			if (perks.isEmpty()) continue;
			if (perks.contains(Pets.Perk.SECOND_WIND)) secondWind(player, owner, owned, s.perks);
			if (perks.contains(Pets.Perk.FIRE_WATCH) && time % 5 == 0 && ready(st, Pets.Perk.FIRE_WATCH, time)) fireWatch(player, st, owned, s.perks, time);
			if (perks.contains(Pets.Perk.SNACKS) && time % 20 == 0 && ready(st, Pets.Perk.SNACKS, time)) snack(player, st, owned, s.perks, time);
			if (perks.contains(Pets.Perk.XP_MAGNET) && time % 2 == 0) xpMagnet(player, s.perks);
			if (perks.contains(Pets.Perk.COOK) && ++st.cookTicks >= s.perks.cookMinutes * 1200L) cook(player, st, owned);
			if (perks.contains(Pets.Perk.TINKERER) && ++st.tinkerTicks >= s.perks.tinkerMinutes * 1200L) tinker(player, st, owned, s.perks);
			if (perks.contains(Pets.Perk.GUARDIAN_ROAR) && time % 10 == 0 && ready(st, Pets.Perk.GUARDIAN_ROAR, time) && roar(player, st, owned, s.perks, time)) continue;
			if (perks.contains(Pets.Perk.GIFTS)) gifts(player, owner, owned, s.perks);
			if (perks.contains(Pets.Perk.FISHING)) st.fishingTicks++;
			// One outing at a time; defending comes first and may call the pet back from anything else.
			if (perks.contains(Pets.Perk.NIPPER) && ready(st, Pets.Perk.NIPPER, time) && nipper(player, st, s.perks, time)) continue;
			if (perks.contains(Pets.Perk.SHIELD) && ready(st, Pets.Perk.SHIELD, time) && shield(player, st, s.perks, time)) continue;
			if (PetFollowers.onOuting(player.getUUID())) continue;
			if (perks.contains(Pets.Perk.FETCH) && time % 10 == 0 && fetch(player, st, s.perks)) continue;
			if (perks.contains(Pets.Perk.TORCHES) && time % 20 == 0 && ready(st, Pets.Perk.TORCHES, time) && torch(player, st, s.perks, time)) continue;
			if (perks.contains(Pets.Perk.MINER) && ready(st, Pets.Perk.MINER, time) && miner(player, st, s.perks, time)) continue;
			if (perks.contains(Pets.Perk.CHOPPER) && ready(st, Pets.Perk.CHOPPER, time) && chopper(player, st, s.perks, time)) continue;
			if (perks.contains(Pets.Perk.GARDENER) && time % 20 == 0 && ready(st, Pets.Perk.GARDENER, time) && gardener(player, st, s.perks, time)) continue;
			if (perks.contains(Pets.Perk.FISHING) && st.fishingTicks >= s.perks.fishingMinutes * 1200L && time % 100 == 0 && fishing(player, st, owned)) continue;
			if (perks.contains(Pets.Perk.TRUFFLE) && time % 100 == 0 && truffle(player, st, s.perks, time)) continue;
		}
	}

	/** The owner broke a block (natural: not placed by a player, for ore and stone). */
	static void mined(ServerPlayer player, BlockPos pos, BlockState state, boolean natural) {
		State st = states.get(player.getUUID());
		if (st == null) return;
		long time = player.level().getServer().getTickCount();
		boolean grown = state.getBlock() instanceof CropBlock crop ? crop.isMaxAge(state)
				: state.is(Blocks.NETHER_WART) && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
		if ((grown || natural && PlacedBlocks.isOre(state)) && active(player, Pets.Perk.LUCKY_PAW)) luckyPaw(player, pos, state, grown);
		if (grown && active(player, Pets.Perk.REPLANTER)) replant(player, pos, state);
		if (natural && PlacedBlocks.tracked(state)) {
			st.mined.addLast(new long[] {pos.asLong(), time});
			while (st.mined.size() > 20) st.mined.removeFirst();
		}
		if (Felling.isLog(state)) {
			BlockPos up = pos.above();
			List<BlockPos> logs = Felling.isLog(player.level().getBlockState(up)) ? Felling.naturalTree(player.level(), up) : null;
			if (logs != null) {
				logs.sort(Comparator.comparingInt((BlockPos b) -> b.getY()).reversed());
				st.tree = new ArrayList<>(logs);
				st.treeUntil = time + 600;
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	/** The owner's pet is out and this perk works for them right now (for perks that wait for something to happen). */
	static boolean active(ServerPlayer player, Pets.Perk perk) {
		if (!PetStore.enabled() || !PetStore.settings().perks.enabled) return false;
		if (PlayerActivity.isAfk(player) || player.isSpectator() || player.isCreative() || !player.isAlive()) return false;
		Pets.Owned owned = PetFollowers.shownOwned(player.getUUID());
		Pets.Owner owner = owned == null ? null : PetStore.data().owners.get(player.getStringUUID());
		return owner != null && PetStore.activePerks(owner, owned).contains(perk);
	}

	private static boolean ready(State st, Pets.Perk perk, long time) {
		return time >= st.next.getOrDefault(perk, 0L);
	}

	private static void wait(State st, Pets.Perk perk, long until) {
		st.next.put(perk, until);
	}

	/** May the player do this there (silent: the pet just doesn't)? */
	private static boolean may(ServerPlayer player, BlockPos pos, Claims.Role role) {
		if (!ClaimStore.enabled() || ClaimTool.bypassing(player)) return true;
		Claims.Claim c = ClaimStore.claimAt(player.level(), pos);
		return c == null || ClaimStore.data().allows(c, player.getStringUUID(), role);
	}

	/** Sends the pet out for a perk (remembered, so defending can call it back). */
	private static boolean go(ServerPlayer player, State st, Pets.Perk perk, java.util.function.Supplier<Vec3> target, double reach, int hold,
			Runnable arrive, java.util.function.Consumer<Vec3> each, java.util.function.Consumer<Boolean> done) {
		boolean started = PetFollowers.startOuting(player, target, reach, hold, arrive, each, ok -> {
			if (st.outing == perk) st.outing = null;
			if (done != null) done.accept(ok);
		}, 240);
		if (started) st.outing = perk;
		return started;
	}

	/** A tool from the hand or inventory that fits and has more than 5 % of its durability left. */
	private static ItemStack tool(ServerPlayer player, Predicate<ItemStack> fits) {
		List<ItemStack> order = new ArrayList<>();
		order.add(player.getMainHandItem());
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) order.add(inv.getItem(i));
		for (ItemStack s : order) {
			if (s.isEmpty() || !fits.test(s)) continue;
			if (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() - 1 > s.getMaxDamage() * 0.05) return s;
		}
		return null;
	}

	/** Breaks a block as the player would with the tool: its drops (and ore XP) count as theirs, and the tool wears. */
	private static void breakBlock(ServerPlayer player, BlockPos pos, ItemStack tool) {
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(pos);
		BlockEntity be = level.getBlockEntity(pos);
		PlayerDrops.during(player, () -> Block.dropResources(state, level, pos, be, player, tool));
		level.destroyBlock(pos, false, player);
		PlacedBlocks.forget(level, pos);
		tool.hurtAndBreak(1, level, player, x -> {
		});
		player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
		PetReactions.broke(player, state);
	}

	/** Into the inventory, or at the player's feet when it's full. */
	private static void give(ServerPlayer player, ItemStack stack) {
		player.getInventory().add(stack);
		if (stack.isEmpty()) return;
		ItemEntity e = new ItemEntity(player.level(), player.getX(), player.getY() + 0.3, player.getZ(), stack);
		e.setNoPickUpDelay();
		player.level().addFreshEntity(e);
	}

	private static void particles(ServerLevel level, ParticleOptions type, Vec3 at, int count, double spread) {
		level.sendParticles(type, at.x, at.y, at.z, count, spread, spread, spread, 0);
	}

	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) if (level.getBlockState(pos.relative(d)).isAir()) return true;
		return false;
	}

	/** Water or lava next to it: breaking it would let the fluid in. */
	private static boolean holdsFluid(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) if (!level.getFluidState(pos.relative(d)).isEmpty()) return true;
		return false;
	}

	/** The owner can see the block (nothing in between). */
	private static boolean visible(ServerPlayer player, BlockPos pos) {
		BlockHitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	// ---------------------------------------------------------------- bond

	private static void levelUp(ServerPlayer player, Pets.Owned owned, int before, int level, Pets.PerkSettings s) {
		String name = PetStore.displayName(owned);
		player.sendSystemMessage(Component.literal(name + " reached bond level " + level + "!").withStyle(ChatFormatting.GOLD));
		List<Pets.Perk> perks = PetStore.perks(owned);
		for (int slot = 0; slot < perks.size(); slot++) {
			int at = Pets.slotLevel(slot, s);
			if (at > before && at <= level) {
				player.sendSystemMessage(Component.literal("Perk unlocked: " + perks.get(slot).label + " · " + perks.get(slot).describe(s)).withStyle(ChatFormatting.GRAY));
			}
		}
		PetReactions.levelUp(player, level);
	}

	// ---------------------------------------------------------------- helpers that don't fly

	/** A light level 10 (Night Eyes: 14 deep underground) around the pet: a light block only clients get. */
	private static void lantern(ServerPlayer player, State st, boolean nightEyes, Pets.PerkSettings s, long time) {
		if (time % 5 != 0) return;
		Vec3 at = PetFollowers.position(player.getUUID());
		if (at == null) {
			lightOff(st);
			return;
		}
		ServerLevel level = player.level();
		BlockPos pos = BlockPos.containing(at);
		if (!level.getBlockState(pos).isAir()) pos = pos.above();
		if (!level.getBlockState(pos).isAir()) pos = player.blockPosition().above(2);
		if (!level.getBlockState(pos).isAir()) {
			lightOff(st);
			return;
		}
		BlockPos feet = player.blockPosition();
		int value = nightEyes && level.getBrightness(LightLayer.SKY, feet) == 0 && level.getBrightness(LightLayer.BLOCK, feet) < 4 ? s.nightEyesLevel : s.lanternLevel;
		if (level == st.lightLevel && pos.equals(st.lightPos) && value == st.lightValue && time - st.lightSent < 40) return;
		if (st.lightPos != null && (level != st.lightLevel || !pos.equals(st.lightPos))) lightOff(st);
		send(level, pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, value));
		st.lightLevel = level;
		st.lightPos = pos;
		st.lightValue = value;
		st.lightSent = time;
	}

	/** Clients get the real block back where the light was. */
	private static void lightOff(State st) {
		if (st.lightPos == null) return;
		send(st.lightLevel, st.lightPos, st.lightLevel.getBlockState(st.lightPos));
		st.lightPos = null;
		st.lightLevel = null;
	}

	private static void send(ServerLevel level, BlockPos pos, BlockState state) {
		ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(pos, state);
		for (ServerPlayer p : level.players()) if (p.blockPosition().distSqr(pos) < 160 * 160) p.connection.send(packet);
	}

	private static void secondWind(ServerPlayer player, Pets.Owner owner, Pets.Owned owned, Pets.PerkSettings s) {
		long now = System.currentTimeMillis();
		if (player.getHealth() > 4 || now < owner.secondWindAt) return;
		player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
		owner.secondWindAt = now + s.secondWindMinutes * 60_000L;
		PetStore.changed();
		Vec3 at = PetFollowers.position(player.getUUID());
		if (at != null) particles(player.level(), ParticleTypes.HEART, at, 5, 0.3);
		player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " gives you a second wind!").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	private static void gifts(ServerPlayer player, Pets.Owner owner, Pets.Owned owned, Pets.PerkSettings s) {
		if (++owner.giftTicks < s.giftMinutes * 1200L) return;
		owner.giftTicks = 0;
		PetStore.changed();
		int total = 0;
		for (Pets.Gift g : s.gifts) total += g.weight;
		if (total <= 0) return;
		int roll = player.getRandom().nextInt(total);
		for (Pets.Gift g : s.gifts) {
			roll -= g.weight;
			if (roll >= 0) continue;
			Identifier id = Identifier.tryParse(g.item);
			Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || item == Items.AIR) return;
			int count = g.min + player.getRandom().nextInt(g.max - g.min + 1);
			ItemStack stack = new ItemStack(item, count);
			String what = count + " × " + stack.getHoverName().getString();
			give(player, stack);
			player.sendSystemMessage(Component.literal(PetStore.displayName(owned) + " found " + what + " for you!").withStyle(ChatFormatting.GOLD));
			Vec3 at = PetFollowers.position(player.getUUID());
			if (at != null) particles(player.level(), ParticleTypes.HAPPY_VILLAGER, at, 6, 0.25);
			return;
		}
	}

	// ---------------------------------------------------------------- defending

	/** Like a wolf's: a monster that hurt the owner or that they attacked, lately; never creepers, ghasts, bosses, named or tamed mobs. */
	private static boolean nipTarget(ServerPlayer player, LivingEntity e) {
		if (e == null || !e.isAlive() || !(e instanceof Enemy) || e instanceof Player || e.level() != player.level() || e.hasCustomName()) return false;
		if (e instanceof TamableAnimal t && t.isTame()) return false;
		if (is(e, "creeper", "ghast") || boss(e)) return false;
		return e.distanceToSqr(player) <= 64;
	}

	/** The entity's type is one of these (vanilla ids). */
	private static boolean is(LivingEntity e, String... ids) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
		if (id == null || !id.getNamespace().equals("minecraft")) return false;
		for (String s : ids) if (id.getPath().equals(s)) return true;
		return false;
	}

	private static boolean boss(LivingEntity e) {
		return is(e, "ender_dragon", "wither", "warden");
	}

	private static boolean nipper(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		LivingEntity target = null;
		LivingEntity by = player.getLastHurtByMob();
		if (player.tickCount - player.getLastHurtByMobTimestamp() < 200 && nipTarget(player, by)) target = by;
		LivingEntity hit = player.getLastHurtMob();
		if (target == null && player.tickCount - player.getLastHurtMobTimestamp() < 200 && nipTarget(player, hit)) target = hit;
		if (target == null) return false;
		if (st.outing == Pets.Perk.NIPPER || st.outing == Pets.Perk.SHIELD) return true;
		if (st.outing != null) PetFollowers.cancelOuting(player.getUUID());
		LivingEntity mob = target;
		boolean started = go(player, st, Pets.Perk.NIPPER, () -> nipTarget(player, mob) ? mob.position().add(0, mob.getBbHeight() * 0.6, 0) : null,
				1.0 + mob.getBbWidth() / 2, 0, () -> {
					if (!nipTarget(player, mob)) return;
					ServerLevel level = player.level();
					mob.hurtServer(level, level.damageSources().playerAttack(player), (float) s.nipperDamage);
					particles(level, ParticleTypes.CRIT, mob.position().add(0, mob.getBbHeight() * 0.6, 0), 6, 0.2);
					level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.6f, 1.4f);
				}, null, null);
		if (started) wait(st, Pets.Perk.NIPPER, time + (long) (s.nipperSeconds * 20));
		return started;
	}

	private static boolean shield(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		LivingEntity near = null;
		double best = 9;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3), e -> e instanceof Enemy && e.isAlive())) {
			if (e instanceof Player || e.hasCustomName() || e instanceof TamableAnimal t && t.isTame()) continue;
			if (boss(e)) continue;
			double d = e.distanceToSqr(player);
			if (d < best) {
				best = d;
				near = e;
			}
		}
		if (near == null) return false;
		if (st.outing == Pets.Perk.NIPPER || st.outing == Pets.Perk.SHIELD) return true;
		if (st.outing != null) PetFollowers.cancelOuting(player.getUUID());
		LivingEntity mob = near;
		boolean started = go(player, st, Pets.Perk.SHIELD, () -> mob.isAlive() ? mob.position().add(0, mob.getBbHeight() * 0.6, 0) : null, 1.2 + mob.getBbWidth() / 2, 0, () -> {
			if (!mob.isAlive()) return;
			Vec3 away = mob.position().subtract(player.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-4) away = player.getLookAngle().multiply(1, 0, 1);
			away = away.normalize();
			mob.push(away.x * 0.6, 0.3, away.z * 0.6);
			particles(player.level(), ParticleTypes.CLOUD, mob.position().add(0, mob.getBbHeight() * 0.5, 0), 6, 0.2);
		}, null, null);
		if (started) wait(st, Pets.Perk.SHIELD, time + s.shieldSeconds * 20L);
		return started;
	}

	// ---------------------------------------------------------------- outings

	/** Brings an item that dropped because of the owner (it travels with the pet; nothing is ever held outside the world). */
	private static boolean fetch(ServerPlayer player, State st, Pets.PerkSettings s) {
		UUID me = player.getUUID();
		ItemEntity item = null;
		double best = Double.MAX_VALUE;
		for (ItemEntity e : player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(s.fetchRadius),
				e -> e.isAlive() && me.equals(PlayerDrops.owner(e)) && !e.hasPickUpDelay() && !e.isInLava()
						&& (player.getInventory().getFreeSlot() >= 0 || player.getInventory().getSlotWithRemainingSpace(e.getItem()) >= 0))) {
			double d = e.distanceToSqr(player);
			if (d > 2.25 && d < best && player.hasLineOfSight(e)) {
				best = d;
				item = e;
			}
		}
		if (item == null) return false;
		ItemEntity it = item;
		return go(player, st, Pets.Perk.FETCH, () -> it.isAlive() ? it.position().add(0, 0.25, 0) : null, 0.7, 0, () -> {
			if (!it.isAlive()) return;
			st.carrying = it;
			it.setNoGravity(true);
		}, pos -> {
			ItemEntity c = st.carrying;
			if (c == null) return;
			if (!c.isAlive()) {
				st.carrying = null;
				return;
			}
			// A short delay renewed every tick: after a crash it can be picked up again right away.
			c.setPickUpDelay(40);
			c.setPos(pos.x, pos.y - 0.45, pos.z);
			c.setDeltaMovement(Vec3.ZERO);
			c.needsSync = true;
		}, ok -> {
			ItemEntity c = st.carrying;
			st.carrying = null;
			if (c == null || !c.isAlive()) return;
			c.setNoGravity(false);
			if (ok) c.setPos(player.getX(), player.getY() + 0.3, player.getZ());
			c.setNoPickUpDelay();
			c.needsSync = true;
		});
	}

	/** One of the owner's torches where it's dark enough for monsters. */
	private static boolean torch(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		if (player.isShiftKeyDown()) return false;
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		if (level.getBrightness(LightLayer.BLOCK, feet) > 0 || level.getBrightness(LightLayer.SKY, feet) >= 8) return false;
		if (player.getInventory().countItem(Items.TORCH) == 0) return false;
		BlockState torch = Blocks.TORCH.defaultBlockState();
		BlockPos spot = null;
		double best = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 1, 2))) {
			if (q.equals(feet) || q.equals(feet.above())) continue;
			if (!level.getBlockState(q).isAir() || !torch.canSurvive(level, q) || level.getBrightness(LightLayer.BLOCK, q) > 0) continue;
			double d = q.distSqr(feet);
			if (d < best && may(player, q, Claims.Role.BUILD)) {
				best = d;
				spot = q.immutable();
			}
		}
		if (spot == null) return false;
		BlockPos at = spot;
		wait(st, Pets.Perk.TORCHES, time + s.torchSeconds * 20L);
		return go(player, st, Pets.Perk.TORCHES, () -> Vec3.atCenterOf(at).add(0, 0.4, 0), 0.8, 5, () -> {
			if (!level.getBlockState(at).isAir() || !torch.canSurvive(level, at) || !may(player, at, Claims.Role.BUILD)) return;
			Inventory inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (!stack.is(Items.TORCH)) continue;
				stack.shrink(1);
				level.setBlock(at, torch, Block.UPDATE_ALL);
				level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 1f);
				return;
			}
		}, null, null);
	}

	/** Visible natural ore near the owner, or natural stone next to where they mine right now. */
	private static boolean miner(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		BlockPos target = null;
		double best = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-5, -4, -5), feet.offset(5, 6, 5))) {
			BlockState bs = level.getBlockState(q);
			if (!PlacedBlocks.isOre(bs) || q.equals(feet.below())) continue;
			double d = Vec3.atCenterOf(q).distanceToSqr(eye);
			if (d > 25 || d >= best || !mineable(player, q, bs)) continue;
			if (!visible(player, q)) continue;
			best = d;
			target = q.immutable();
		}
		if (target == null) {
			st.mined.removeIf(m -> time - m[1] > 200);
			for (long[] m : st.mined) {
				BlockPos at = BlockPos.of(m[0]);
				for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 2, 2))) {
					if (q.getY() < feet.getY() || q.getY() > feet.getY() + 2) continue;
					BlockState bs = level.getBlockState(q);
					if (!PlacedBlocks.isStone(bs)) continue;
					double d = Vec3.atCenterOf(q).distanceToSqr(eye);
					if (d > 25 || d >= best || !exposed(level, q) || !mineable(player, q, bs)) continue;
					best = d;
					target = q.immutable();
				}
			}
		}
		if (target == null) return false;
		BlockPos at = target;
		wait(st, Pets.Perk.MINER, time + s.minerSeconds * 20L);
		return go(player, st, Pets.Perk.MINER, () -> Vec3.atCenterOf(at), 1.1, 4, () -> {
			BlockState bs = level.getBlockState(at);
			if (!(PlacedBlocks.isOre(bs) || PlacedBlocks.isStone(bs)) || !mineable(player, at, bs)) return;
			ItemStack pick = tool(player, t -> t.is(ItemTags.PICKAXES) && t.isCorrectToolForDrops(bs));
			if (pick != null) breakBlock(player, at, pick);
		}, null, null);
	}

	/** Natural, no fluid behind it, the owner may build there and carries a pickaxe for it. */
	private static boolean mineable(ServerPlayer player, BlockPos q, BlockState bs) {
		ServerLevel level = player.level();
		return !PlacedBlocks.isPlaced(level, q) && !holdsFluid(level, q) && may(player, q, Claims.Role.BUILD)
				&& tool(player, t -> t.is(ItemTags.PICKAXES) && t.isCorrectToolForDrops(bs)) != null;
	}

	/** The next log (from the top) of the natural tree the owner started chopping. */
	private static boolean chopper(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		if (st.tree == null) return false;
		if (time > st.treeUntil) {
			st.tree = null;
			return false;
		}
		ServerLevel level = player.level();
		st.tree.removeIf(p -> !Felling.isLog(level.getBlockState(p)));
		BlockPos log = null;
		for (BlockPos p : st.tree) {
			if (p.distToCenterSqr(player.position()) <= 36 && may(player, p, Claims.Role.BUILD)) {
				log = p;
				break;
			}
		}
		if (log == null) {
			if (st.tree.isEmpty()) st.tree = null;
			return false;
		}
		ItemStack axe = tool(player, t -> t.is(ItemTags.AXES));
		if (axe == null) return false;
		BlockPos at = log;
		wait(st, Pets.Perk.CHOPPER, time + s.chopperSeconds * 20L);
		st.treeUntil = time + 600;
		return go(player, st, Pets.Perk.CHOPPER, () -> Vec3.atCenterOf(at), 1.1, 4, () -> {
			if (!Felling.isLog(level.getBlockState(at)) || !may(player, at, Claims.Role.BUILD)) return;
			ItemStack tool = tool(player, t -> t.is(ItemTags.AXES));
			if (tool != null) breakBlock(player, at, tool);
		}, null, null);
	}

	/** One growth step for a crop next to the owner. */
	private static boolean gardener(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		List<BlockPos> crops = new ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 2, 4))) {
			BlockState bs = level.getBlockState(q);
			if (bs.is(BlockTags.CROPS) && Growth.canGrow(level, q, bs) && may(player, q, Claims.Role.CONTAINERS)) {
				crops.add(q.immutable());
			}
		}
		if (crops.isEmpty()) return false;
		BlockPos at = crops.get(player.getRandom().nextInt(crops.size()));
		wait(st, Pets.Perk.GARDENER, time + s.gardenerSeconds * 20L);
		return go(player, st, Pets.Perk.GARDENER, () -> Vec3.atCenterOf(at).add(0, 0.6, 0), 0.8, 10, () -> {
			BlockState bs = level.getBlockState(at);
			if (!Growth.canGrow(level, at, bs) || !may(player, at, Claims.Role.CONTAINERS)) return;
			Growth.grow(level, at, bs);
			particles(level, ParticleTypes.HAPPY_VILLAGER, Vec3.atCenterOf(at), 8, 0.3);
		}, null, null);
	}

	/** Dips into water nearby and brings a catch: fish and junk only (no fishing hook, so never treasure). */
	private static boolean fishing(ServerPlayer player, State st, Pets.Owned owned) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		BlockPos water = null;
		double best = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-6, -3, -6), feet.offset(6, 2, 6))) {
			if (!openWater(level, q)) continue;
			double d = q.distSqr(feet);
			if (d < best) {
				best = d;
				water = q.immutable();
			}
		}
		if (water == null) return false;
		BlockPos at = water;
		List<ItemStack> catches = new ArrayList<>();
		return go(player, st, Pets.Perk.FISHING, () -> Vec3.atCenterOf(at).add(0, 0.7, 0), 0.6, 30, () -> {
			st.fishingTicks = 0;
			particles(level, ParticleTypes.SPLASH, Vec3.atCenterOf(at).add(0, 0.6, 0), 12, 0.3);
			level.playSound(null, at, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6f, 1.2f);
			LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
			LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
					.withParameter(LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD)).withParameter(LootContextParams.THIS_ENTITY, player)
					.withLuck(0).create(LootContextParamSets.FISHING);
			catches.addAll(table.getRandomItems(params));
		}, null, ok -> {
			if (catches.isEmpty() || player.isRemoved()) return;
			String what = catches.get(0).getHoverName().getString();
			for (ItemStack c : catches) give(player, c);
			player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " caught " + what + "!").withStyle(ChatFormatting.AQUA));
			PetReactions.fished(player);
		});
	}

	/** A water source with air above and room around it (2×2). */
	private static boolean openWater(ServerLevel level, BlockPos q) {
		for (BlockPos p : new BlockPos[] {q, q.east(), q.south(), q.south().east()}) {
			if (!level.getFluidState(p).is(FluidTags.WATER) || !level.getFluidState(p).isSource() || !level.getBlockState(p.above()).isAir()) return false;
		}
		return true;
	}

	/** Suspicious sand or gravel nearby: the pet hovers over it for a few seconds. */
	private static boolean truffle(ServerPlayer player, State st, Pets.PerkSettings s, long time) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		int r = s.truffleRadius;
		st.sniffed.values().removeIf(t -> time - t > 1200);
		BlockPos found = null;
		double best = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -r / 2, -r), feet.offset(r, r / 2, r))) {
			BlockState bs = level.getBlockState(q);
			if (!bs.is(Blocks.SUSPICIOUS_SAND) && !bs.is(Blocks.SUSPICIOUS_GRAVEL)) continue;
			double d = q.distSqr(feet);
			if (d < best && !st.sniffed.containsKey(q)) {
				best = d;
				found = q.immutable();
			}
		}
		if (found == null) return false;
		BlockPos at = found;
		st.sniffed.put(at, time);
		Pets.Owned owned = PetFollowers.shownOwned(player.getUUID());
		return go(player, st, Pets.Perk.TRUFFLE, () -> Vec3.atCenterOf(at).add(0, 1.2, 0), 0.6, 100, () -> {
			if (owned != null) player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " sniffed out something buried!").withStyle(ChatFormatting.YELLOW));
		}, pos -> {
			if (level.getServer().getTickCount() % 10 == 0 && pos.distanceToSqr(Vec3.atCenterOf(at).add(0, 1.2, 0)) < 1) {
				particles(level, ParticleTypes.HAPPY_VILLAGER, Vec3.atCenterOf(at).add(0, 0.6, 0), 3, 0.25);
			}
		}, null);
	}

	// ---------------------------------------------------------------- small helps at the owner's side

	/** Orbs out of vanilla's 8 block reach drift closer, until the orbs come by themselves. */
	private static void xpMagnet(ServerPlayer player, Pets.PerkSettings s) {
		Vec3 me = player.position().add(0, player.getBbHeight() / 2, 0);
		for (ExperienceOrb orb : player.level().getEntitiesOfClass(ExperienceOrb.class, player.getBoundingBox().inflate(s.xpRadius), ExperienceOrb::isAlive)) {
			Vec3 way = me.subtract(orb.position());
			double d = way.length();
			if (d <= 7 || d > s.xpRadius) continue;
			Vec3 to = orb.position().add(way.scale(Math.min(0.8, d - 7) / d));
			orb.setPos(to.x, to.y, to.z);
			orb.setDeltaMovement(Vec3.ZERO);
		}
	}

	/** Raw food the Cook can cook, best first. */
	private static final Item[][] COOKING = {
			{Items.BEEF, Items.COOKED_BEEF}, {Items.PORKCHOP, Items.COOKED_PORKCHOP}, {Items.MUTTON, Items.COOKED_MUTTON},
			{Items.SALMON, Items.COOKED_SALMON}, {Items.CHICKEN, Items.COOKED_CHICKEN}, {Items.RABBIT, Items.COOKED_RABBIT},
			{Items.COD, Items.COOKED_COD}, {Items.POTATO, Items.BAKED_POTATO}};

	private static void cook(ServerPlayer player, State st, Pets.Owned owned) {
		Inventory inv = player.getInventory();
		for (Item[] pair : COOKING) {
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack raw = inv.getItem(i);
				if (!raw.is(pair[0])) continue;
				st.cookTicks = 0;
				raw.shrink(1);
				ItemStack done = new ItemStack(pair[1]);
				String what = done.getHoverName().getString();
				give(player, done);
				Vec3 at = PetFollowers.position(player.getUUID());
				if (at != null) particles(player.level(), ParticleTypes.SMOKE, at, 6, 0.2);
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.3f, 1.6f);
				player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " cooked " + what + " for you!").withStyle(ChatFormatting.GOLD));
				return;
			}
		}
		// Nothing to cook: it tries again in a minute.
		st.cookTicks = Math.max(0, st.cookTicks - 1200);
	}

	/** Food the Snack Sharer never picks: bad for you, or too good to eat by accident. */
	private static final java.util.Set<Item> NO_SNACK = java.util.Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
			Items.CHICKEN, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_CARROT);

	/** Eats the least filling food from the inventory for its owner, the way they would. */
	private static void snack(ServerPlayer player, State st, Pets.Owned owned, Pets.PerkSettings s, long time) {
		if (player.getFoodData().getFoodLevel() > s.snackFood || player.isUsingItem()) return;
		Inventory inv = player.getInventory();
		int slot = -1;
		int least = Integer.MAX_VALUE;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			FoodProperties food = stack.isEmpty() || NO_SNACK.contains(stack.getItem()) ? null : stack.get(DataComponents.FOOD);
			if (food != null && food.nutrition() > 0 && food.nutrition() < least) {
				least = food.nutrition();
				slot = i;
			}
		}
		if (slot < 0) return;
		wait(st, Pets.Perk.SNACKS, time + s.snackSeconds * 20L);
		ItemStack stack = inv.getItem(slot);
		String what = stack.getHoverName().getString();
		ItemStack left = stack.finishUsingItem(player.level(), player);
		if (left != stack) inv.setItem(slot, left);
		player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " shared " + what + " with you").withStyle(ChatFormatting.GOLD));
	}

	/** Mends the item in the hand, else the off hand, else the most worn armor. */
	private static void tinker(ServerPlayer player, State st, Pets.Owned owned, Pets.PerkSettings s) {
		ItemStack target = null;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack it = player.getItemBySlot(slot);
			if (!it.isDamageableItem() || it.getDamageValue() <= 0) continue;
			if (target == null || slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && target != player.getMainHandItem() && target != player.getOffhandItem()
					&& it.getDamageValue() > target.getDamageValue()) {
				target = it;
			}
		}
		if (target == null) {
			// Nothing worn: it tries again in a minute.
			st.tinkerTicks = Math.max(0, st.tinkerTicks - 1200);
			return;
		}
		st.tinkerTicks = 0;
		target.setDamageValue(Math.max(0, target.getDamageValue() - s.tinkerPoints));
		Vec3 at = PetFollowers.position(player.getUUID());
		if (at != null) particles(player.level(), ParticleTypes.WAX_OFF, at, 6, 0.25);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 0.3f, 1.5f);
		player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " mended your " + target.getHoverName().getString()).withStyle(ChatFormatting.GOLD));
	}

	/** Fishing rods: the pet adds its Lure levels (all together never more than Lure III's 15 seconds). */
	public static float lure(Player player, float seconds) {
		if (!(player instanceof ServerPlayer p) || seconds >= 15 || !active(p, Pets.Perk.LURE)) return seconds;
		return Math.min(15, seconds + 5 * PetStore.settings().perks.lureLevels);
	}

	/** Monsters near the owner glow on their screen only (sent again every second; anything else sees no change). */
	private static void watchfulEye(ServerPlayer player, State st, Pets.PerkSettings s) {
		Map<Integer, Entity> now = new HashMap<>();
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(s.eyeRadius),
				e -> e instanceof Enemy && e.isAlive() && !(e instanceof Player) && !e.isInvisible())) {
			if (e.distanceToSqr(player) > (double) s.eyeRadius * s.eyeRadius) continue;
			now.put(e.getId(), e);
			byte flags = e.getEntityData().get(EntityFlagsAccessor.nicecontrolcenter$flags());
			player.connection.send(new ClientboundSetEntityDataPacket(e.getId(),
					List.of(SynchedEntityData.DataValue.create(EntityFlagsAccessor.nicecontrolcenter$flags(), (byte) (flags | 0x40)))));
		}
		for (Map.Entry<Integer, Entity> e : st.glowing.entrySet()) if (!now.containsKey(e.getKey())) unglow(player, e.getValue());
		st.glowing.clear();
		st.glowing.putAll(now);
	}

	/** The owner's client gets the real flags back for every monster Watchful Eye lit up (no player: just forgotten). */
	private static void eyeOff(ServerPlayer player, State st) {
		if (st.glowing.isEmpty()) return;
		if (player != null) for (Entity e : st.glowing.values()) unglow(player, e);
		st.glowing.clear();
	}

	private static void unglow(ServerPlayer player, Entity e) {
		if (e.isRemoved() || e.level() != player.level()) return;
		byte flags = e.getEntityData().get(EntityFlagsAccessor.nicecontrolcenter$flags());
		player.connection.send(new ClientboundSetEntityDataPacket(e.getId(), List.of(SynchedEntityData.DataValue.create(EntityFlagsAccessor.nicecontrolcenter$flags(), flags))));
	}

	/** One extra of what the block dropped (never the ore block itself, so Silk Touch gets nothing more). */
	private static void luckyPaw(ServerPlayer player, BlockPos pos, BlockState state, boolean crop) {
		if (player.getRandom().nextInt(100) >= PetStore.settings().perks.luckyPercent) return;
		ServerLevel level = player.level();
		List<ItemStack> drops = new ArrayList<>();
		for (ItemStack d : Block.getDrops(state, level, pos, null, player, player.getMainHandItem())) {
			if (!d.isEmpty() && (crop || !d.is(state.getBlock().asItem()))) drops.add(d);
		}
		if (drops.isEmpty()) return;
		ItemStack extra = drops.get(player.getRandom().nextInt(drops.size())).copyWithCount(1);
		Pets.Owned owned = PetFollowers.shownOwned(player.getUUID());
		String what = extra.getHoverName().getString();
		PlayerDrops.during(player, () -> Block.popResource(level, pos, extra));
		particles(level, ParticleTypes.HAPPY_VILLAGER, Vec3.atCenterOf(pos), 6, 0.3);
		if (owned != null) player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " found one more " + what + "!").withStyle(ChatFormatting.GOLD));
	}

	/** Plants the crop again where it was harvested: a seed from the inventory, else one that just dropped. */
	private static void replant(ServerPlayer player, BlockPos pos, BlockState state) {
		ServerLevel level = player.level();
		BlockState young = state.getBlock().defaultBlockState();
		Item seed = state.getBlock().asItem();
		if (seed == Items.AIR || !level.getBlockState(pos).isAir() || !young.canSurvive(level, pos)) return;
		ItemStack from = null;
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize() && from == null; i++) if (inv.getItem(i).is(seed)) from = inv.getItem(i);
		if (from == null) {
			UUID me = player.getUUID();
			for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(1.5),
					e -> e.isAlive() && e.getItem().is(seed) && me.equals(PlayerDrops.owner(e)))) {
				from = e.getItem();
				break;
			}
		}
		if (from == null) return;
		from.shrink(1);
		level.setBlock(pos, young, Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8f, 1f);
		particles(level, ParticleTypes.HAPPY_VILLAGER, Vec3.atCenterOf(pos), 3, 0.25);
	}

	/** Puts out a burning owner (not in lava: that would only burn again). */
	private static void fireWatch(ServerPlayer player, State st, Pets.Owned owned, Pets.PerkSettings s, long time) {
		if (!player.isOnFire() || player.isInLava() || player.hasEffect(MobEffects.FIRE_RESISTANCE)) return;
		player.clearFire();
		wait(st, Pets.Perk.FIRE_WATCH, time + s.fireSeconds * 20L);
		particles(player.level(), ParticleTypes.SPLASH, player.position().add(0, 1, 0), 20, 0.4);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.2f);
		player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " put you out!").withStyle(ChatFormatting.AQUA));
	}

	/** A fall's damage, less what Soft Paws takes (only for falls that hurt more than a heart). */
	public static int softFall(Player player, int damage) {
		if (damage <= 2 || !(player instanceof ServerPlayer p)) return damage;
		State st = states.get(p.getUUID());
		long time = p.level().getServer().getTickCount();
		if (st == null || !ready(st, Pets.Perk.SOFT_PAWS, time) || !active(p, Pets.Perk.SOFT_PAWS)) return damage;
		Pets.PerkSettings s = PetStore.settings().perks;
		wait(st, Pets.Perk.SOFT_PAWS, time + s.softMinutes * 1200L);
		particles(p.level(), ParticleTypes.CLOUD, p.position().add(0, 0.2, 0), 10, 0.4);
		Pets.Owned owned = PetFollowers.shownOwned(p.getUUID());
		if (owned != null) p.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " softened your fall!").withStyle(ChatFormatting.LIGHT_PURPLE));
		return damage - Math.round(damage * s.softPercent / 100f);
	}

	/** Several monsters close in: one roar sends them all back. */
	private static boolean roar(ServerPlayer player, State st, Pets.Owned owned, Pets.PerkSettings s, long time) {
		List<LivingEntity> near = new ArrayList<>();
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(5), e -> e instanceof Enemy && e.isAlive())) {
			if (e instanceof Player || e.hasCustomName() || e instanceof TamableAnimal t && t.isTame() || boss(e)) continue;
			if (e.distanceToSqr(player) <= 25) near.add(e);
		}
		if (near.size() < s.roarCount) return false;
		wait(st, Pets.Perk.GUARDIAN_ROAR, time + s.roarMinutes * 1200L);
		ServerLevel level = player.level();
		for (LivingEntity mob : near) {
			Vec3 away = mob.position().subtract(player.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-4) away = player.getLookAngle().multiply(1, 0, 1);
			away = away.normalize();
			mob.push(away.x * 1.4, 0.45, away.z * 1.4);
			particles(level, ParticleTypes.POOF, mob.position().add(0, mob.getBbHeight() * 0.5, 0), 5, 0.2);
		}
		Vec3 at = PetFollowers.position(player.getUUID());
		if (at != null) particles(level, ParticleTypes.SONIC_BOOM, at, 1, 0);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.7f, 1.6f);
		player.sendOverlayMessage(Component.literal(PetStore.displayName(owned) + " roared them away!").withStyle(ChatFormatting.RED));
		return true;
	}

	/** The pet is gone: clients get their real blocks back and a carried item is let go. */
	private static void clear(State st) {
		lightOff(st);
		ItemEntity c = st.carrying;
		st.carrying = null;
		if (c != null && c.isAlive()) {
			c.setNoGravity(false);
			c.setNoPickUpDelay();
		}
	}

	static void removeAll() {
		for (State st : states.values()) {
			eyeOff(null, st);
			clear(st);
		}
		states.clear();
	}
}
