package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.LiveChunkCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCacheIngest {
    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void jbpLod$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        ClientLevel world = (ClientLevel) ((ClientChunkCache) (Object) this).getLevel();
        Minecraft.getInstance().execute(() -> LiveChunkCapture.changed(world, pos.x(), pos.z()));
    }
    @Inject(method = "replaceBiomes", at = @At("TAIL"))
    private void jbpLod$biomesChanged(int x, int z, FriendlyByteBuf data, CallbackInfo ci) {
        LiveChunkCapture.changed((ClientLevel) ((ClientChunkCache) (Object) this).getLevel(), x, z);
    }
}
