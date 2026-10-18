package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Upstream Voxy reads {@code StairBlock.baseState} through an access widener; JBP-LOD uses an accessor mixin. */
@Mixin(value = StairBlock.class, remap = false)
public interface StairBlockAccessor {
    @Accessor("baseState")
    BlockState jbpLod$getBaseState();
}
