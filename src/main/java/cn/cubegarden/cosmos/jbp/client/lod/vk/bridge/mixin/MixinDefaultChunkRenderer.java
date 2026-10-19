package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.JbpLodClient;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.VulkanBridgeProbe;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {

    @Shadow
    @Final
    private boolean[] shouldDraw;
    @Unique
    private VulkanBridgeProbe.Frame jbpLod$probeFrame;
    @Unique
    private VulkanLodRenderer.Frame jbpLod$lodFrame;

    /**
     * Sodium prepares every pass even for an empty render list, then skips render's body when
     * shouldDraw is false. Keep CUTOUT's begin/end scope reachable without nearby terrain.
     * The probe deliberately does not depend on the renderer property or an active LOD scope.
     */
    @Inject(method = "prepare(Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Z)V",
            at = @At("TAIL"))
    private void jbpLod$keepCutoutReachable(ChunkRenderListIterable renderLists,
                                            CameraTransform camera,
                                            boolean indexedRenderingEnabled,
                                            CallbackInfo ci) {
        if (VulkanBridgeProbe.enabled()
                || (VulkanLodRenderer.enabled() && JbpLodClient.activeRuntime().isPresent())) {
            this.shouldDraw[DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.CUTOUT)] = true;
        }
    }

    /**
     * HEAD of the CUTOUT terrain render: the V1 probe (opt-in, independent of V4) and the V4 LOD
     * pre-pass, which records and submits its own command buffer ahead of the game's.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void jbpLod$recordComputeBeforeCutout(ChunkRenderMatrices matrices,
                                                   ChunkRenderListIterable renderLists,
                                                   TerrainRenderPass renderPass,
                                                   CameraTransform camera,
                                                   FogParameters parameters,
                                                   boolean indexedRenderingEnabled,
                                                   RenderPass pass,
                                                   GpuSampler terrainSampler,
                                                   GpuBufferSlice uniformData,
                                                   GpuBuffer sectionTimeInfo,
                                                   OitStage stage,
                                                   CallbackInfo ci) {
        this.jbpLod$probeFrame = null;
        this.jbpLod$lodFrame = null;
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            if (VulkanBridgeProbe.enabled()) {
                this.jbpLod$probeFrame = VulkanBridgeProbe.beforeCutout(matrices, renderPass, pass);
            }
            this.jbpLod$lodFrame = VulkanLodRenderer.beforeCutout(matrices, renderPass, pass, camera, parameters);
        }
    }

    /** After Sodium's draws, defer LOD composition until this frontend pass really closes. */
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
            shift = At.Shift.BEFORE))
    private void jbpLod$markInsideCutout(ChunkRenderMatrices matrices,
                                         ChunkRenderListIterable renderLists,
                                         TerrainRenderPass renderPass,
                                         CameraTransform camera,
                                         FogParameters parameters,
                                         boolean indexedRenderingEnabled,
                                         RenderPass pass,
                                         GpuSampler terrainSampler,
                                         GpuBufferSlice uniformData,
                                         GpuBuffer sectionTimeInfo,
                                         OitStage stage,
                                         CallbackInfo ci) {
        VulkanBridgeProbe.Frame probeFrame = this.jbpLod$probeFrame;
        this.jbpLod$probeFrame = null;
        VulkanLodRenderer.Frame lodFrame = this.jbpLod$lodFrame;
        this.jbpLod$lodFrame = null;
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            if (VulkanBridgeProbe.enabled()) {
                VulkanBridgeProbe.insideCutout(renderPass, pass, probeFrame);
            }
            VulkanLodRenderer.deferCutout(pass, lodFrame);
        }
    }
}
