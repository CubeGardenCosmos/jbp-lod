package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.JbpLodClient;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.LodFogPolicy;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    @Unique
    private static final Logger JBP_LOD_LOGGER = LoggerFactory.getLogger("jbp-lod");
    @Unique
    private static final String DEBUG_FOG_PROPERTY = "jbp.lod.debugFog";
    @Unique
    private static int jbpLod$fogLogCounter;

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
        jbpLod$logFogSnapshot(data);
        //Upstream keeps close-range environmental fog (underwater, lava, custom dimensions) intact.
        if (LodFogPolicy.shouldExtendEnvironmentalFog(data.environmentalEnd)) {
            data.environmentalStart = 99999999;
            data.environmentalEnd = 99999999;
        }
        data.renderDistanceStart = 999999999;
        data.renderDistanceEnd = 999999999;
    }

    /**
     * Dev-only evidence for the vanilla/Sodium fog hand-off ({@code -Djbp.lod.debugFog=true}). Sodium's
     * {@code FogRendererMixin} stores a {@link FogParameters} copy at the same RETURN injection point, so
     * whichever callback runs first decides whether Sodium's terrain shader and cull distance see the
     * extended range. The captured values logged here are the ones Sodium would use for this frame.
     */
    @Unique
    private void jbpLod$logFogSnapshot(FogData data) {
        if (!Boolean.getBoolean(DEBUG_FOG_PROPERTY) || (jbpLod$fogLogCounter++ % 200) != 0) {
            return;
        }
        FogParameters captured = ((FogStorage) (Object) this).sodium$getFogParameters();
        JBP_LOD_LOGGER.info("[JBP-LOD] fog hand-off: data env={}..{} rd={}..{} sodium env={}..{} rd={}..{} cull={}",
                data.environmentalStart, data.environmentalEnd,
                data.renderDistanceStart, data.renderDistanceEnd,
                captured.environmentalStart(), captured.environmentalEnd(),
                captured.renderStart(), captured.renderEnd(), captured.cullDistance());
    }
}
