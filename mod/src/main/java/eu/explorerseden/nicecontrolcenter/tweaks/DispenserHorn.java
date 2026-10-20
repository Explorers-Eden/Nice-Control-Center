package eu.explorerseden.nicecontrolcenter.tweaks;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Dispensers play goat horns (Gameplay tweak): a dispenser fired with a goat horn in it blows the horn (any instrument,
 * also data pack ones like Fabled Roots' horns, with their own sound and wait), as loud
 * and as far as a player would, and keeps it. Sculk sensors hear it like a player's horn. After each blow the
 * dispenser waits as long as a player has to (a redstone clock can't make it blare); until then it only clicks.
 * Server thread only.
 */
public final class DispenserHorn implements DispenseItemBehavior {
	public static final DispenserHorn INSTANCE = new DispenserHorn();
	/** The game time each dispenser may blow again. */
	private static final Map<GlobalPos, Long> ready = new HashMap<>();

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
		InstrumentComponent component = stack.get(DataComponents.INSTRUMENT);
		long now = level.getGameTime();
		GlobalPos at = GlobalPos.of(level.dimension(), pos);
		if (component == null || ready.getOrDefault(at, 0L) > now) {
			// Not yet: the click of a dispenser with nothing to give.
			level.levelEvent(1001, pos, 0);
			return stack;
		}
		Instrument instrument = component.instrument().value();
		Vec3 center = Vec3.atCenterOf(pos);
		// The holder as it is: data pack horns (Fabled Roots) bring sounds of their own that aren't in the registry.
		level.playSound(null, center.x, center.y, center.z, instrument.soundEvent(), SoundSource.RECORDS, instrument.range() / 16.0F, 1.0F);
		level.gameEvent(GameEvent.INSTRUMENT_PLAY, center, GameEvent.Context.of(source.state()));
		// As long as a player waits: the horn's own time, or its use_cooldown when that's longer (Fabled Roots' horns have one).
		float seconds = instrument.useDuration();
		var cooldown = stack.get(DataComponents.USE_COOLDOWN);
		if (cooldown != null) seconds = Math.max(seconds, cooldown.seconds());
		ready.put(at, now + Math.max(1, Math.round(seconds * 20)));
		if (ready.size() > 256) ready.values().removeIf(t -> t <= now);
		return stack;
	}
}
