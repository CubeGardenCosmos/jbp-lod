package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.LiveChunkCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Source reference: sealed Voxy client/mixin/minecraft/MixinClientLevel.java. */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevelIngest {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void jbpLod$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (oldState != newState) LiveChunkCapture.changed((ClientLevel) (Object) this, pos.getX() >> 4, pos.getZ() >> 4);
    }
}
