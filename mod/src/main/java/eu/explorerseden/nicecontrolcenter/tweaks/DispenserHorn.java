package eu.explorerseden.nicecontrolcenter.tweaks;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Dispensers play goat horns (Gameplay tweak): a dispenser fired with a goat horn in it blows the horn (any
 * instrument, also data pack ones like Fabled Roots' horns, with their own sound), as loud and as far as a player
 * would, and keeps it. Sculk sensors hear it like a player's horn.
 * <p>
 * The wait afterwards is a player's, kept per dispenser: the horn's time (or its use_cooldown, which replaces
 * it) for its whole cooldown group, so all goat horns wait together and Fabled Roots' horns together. With
 * several horns inside, a horn whose group is still waiting gives way to one that's ready; when none is, the
 * dispenser only clicks. Server thread only.
 */
public final class DispenserHorn implements DispenseItemBehavior {
	public static final DispenserHorn INSTANCE = new DispenserHorn();
	/** Per dispenser: the game time each cooldown group may play again. */
	private static final Map<GlobalPos, Map<Identifier, Long>> ready = new HashMap<>();

	private DispenserHorn() {
	}

	/** Whether this is something a dispenser can blow (goat horns, or any item with an instrument). */
	public static boolean plays(ItemStack stack) {
		return stack.has(DataComponents.INSTRUMENT);
	}

	@Override
	public ItemStack dispense(BlockSource source, ItemStack stack) {
		ServerLevel level = source.level();
		BlockPos pos = source.pos();
		long now = level.getGameTime();
		Map<Identifier, Long> waits = ready.computeIfAbsent(GlobalPos.of(level.dimension(), pos), k -> new HashMap<>());
		waits.values().removeIf(t -> t <= now);
		ItemStack horn = canPlay(stack, waits) ? stack : null;
		// The one picked is still waiting: another horn inside whose group is ready plays instead.
		if (horn == null && source.blockEntity() instanceof DispenserBlockEntity box) {
			for (int i = 0; i < box.getContainerSize() && horn == null; i++) {
				ItemStack other = box.getItem(i);
				if (canPlay(other, waits)) horn = other;
			}
		}
		if (horn == null) {
			// None ready: the click of a dispenser with nothing to give.
			level.levelEvent(1001, pos, 0);
			if (waits.isEmpty()) ready.remove(GlobalPos.of(level.dimension(), pos));
			return stack;
		}
		Instrument instrument = horn.get(DataComponents.INSTRUMENT).instrument().value();
		Vec3 center = Vec3.atCenterOf(pos);
		// The holder as it is: data pack horns bring sounds of their own that aren't in the registry.
		level.playSound(null, center.x, center.y, center.z, instrument.soundEvent(), SoundSource.RECORDS, instrument.range() / 16.0F, 1.0F);
		level.gameEvent(GameEvent.INSTRUMENT_PLAY, center, GameEvent.Context.of(source.state()));
		// Like a player: the horn's time for its group, which a use_cooldown replaces (vanilla applies that one last).
		UseCooldown cooldown = horn.get(DataComponents.USE_COOLDOWN);
		int ticks = cooldown != null ? cooldown.ticks() : Mth.floor(instrument.useDuration() * 20.0F);
		if (ticks > 0) waits.put(group(horn), now + ticks);
		// Dispensers that were only used once don't stay remembered.
		if (ready.size() > 256) ready.values().removeIf(m -> {
			m.values().removeIf(t -> t <= now);
			return m.isEmpty();
		});
		return stack;
	}

	private static boolean canPlay(ItemStack stack, Map<Identifier, Long> waits) {
		InstrumentComponent component = stack.isEmpty() ? null : stack.get(DataComponents.INSTRUMENT);
		return component != null && !waits.containsKey(group(stack));
	}

	/** The cooldown group, as ItemCooldowns sees it: use_cooldown's group, else the item. */
	private static Identifier group(ItemStack stack) {
		UseCooldown cooldown = stack.get(DataComponents.USE_COOLDOWN);
		return cooldown != null && cooldown.cooldownGroup().isPresent() ? cooldown.cooldownGroup().get() : BuiltInRegistries.ITEM.getKey(stack.getItem());
	}
}
