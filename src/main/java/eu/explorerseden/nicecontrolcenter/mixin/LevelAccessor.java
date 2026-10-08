package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

@Mixin(Level.class)
public interface LevelAccessor {
	@Accessor("blockEntityTickers")
	List<TickingBlockEntity> nicecontrolcenter$blockEntityTickers();
}
