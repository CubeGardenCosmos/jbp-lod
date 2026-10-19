package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.JbpLodClient;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.LodFogPolicy;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Port of sealed Voxy {@code client/mixin/minecraft/MixinFogRenderer}.
 *
 * <p>The vanilla fog range ends at the loaded-chunk distance and hides exactly the terrain LoD exists to
 * draw. While a LoD runtime is active, the environmental and render-distance fog ranges are pushed out of
 * range, so distant stored terrain is not painted in the sky colour and the vanilla render-distance edge is
 * not fogged over. Without an active runtime (or with {@code -Djbp.lod.render=false}) vanilla fog is
 * untouched.
 */
@Mixin(value = FogRenderer.class, priority = 900)
public abstract class MixinFogRenderer {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void jbpLod$extendFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
                                  float darkenWorldAmount, ClientLevel level,
                                  CallbackInfoReturnable<FogData> cir) {
        if (!LodFogPolicy.shouldExtendLodFog(VulkanLodRenderer.enabled(),
                JbpLodClient.activeRuntime().isPresent())) {
            return;
        }
        FogData data = cir.getReturnValue();
        if (data == null) {
            return;
        }
        //Upstream keeps close-range environmental fog (underwater, lava, custom dimensions) intact.
        if (LodFogPolicy.shouldExtendEnvironmentalFog(data.environmentalEnd)) {
            data.environmentalStart = 99999999;
            data.environmentalEnd = 99999999;
        }
        data.renderDistanceStart = 999999999;
        data.renderDistanceEnd = 999999999;
    }
}
