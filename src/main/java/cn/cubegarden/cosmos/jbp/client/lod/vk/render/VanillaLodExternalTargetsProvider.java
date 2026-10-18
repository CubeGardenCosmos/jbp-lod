package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodExternalTargetsProvider;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameContext;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan.Snapshot;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.FrontendRenderPassAccessor;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.VulkanRenderPassAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/** Vanilla-only provider for the borrowed target and matrices of the current Sodium pass. */
public final class VanillaLodExternalTargetsProvider implements LodExternalTargetsProvider {
    public static final VanillaLodExternalTargetsProvider INSTANCE = new VanillaLodExternalTargetsProvider();
    private static final float LOD_FAR_PLANE = 3000.0f * 16.0f;

    private VanillaLodExternalTargetsProvider() {
    }

    @Override
    public LodFrameTargets targetsFor(LodFrameContext frame) {
        return frame.vanillaTargets();
    }

    /** Builds a borrowed frame view from the locked Sodium CUTOUT hook inputs. */
    public static LodFrameContext capture(long frameNumber, ChunkRenderMatrices matrices,
                                          TerrainRenderPass terrainPass, RenderPass renderPass,
                                          CameraTransform camera, FogParameters fogParameters) {
        Snapshot game = GameVulkan.current();
        if (!(renderPass instanceof FrontendRenderPass frontendPass)
                || !(frontendPass instanceof FrontendRenderPassAccessor frontendAccessor)) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT render pass is not an accessor-backed FrontendRenderPass.");
        }
        RenderPassBackend backend = frontendAccessor.jbpLod$getBackend();
        if (!(backend instanceof VulkanRenderPass vulkanPass)
                || !(vulkanPass instanceof VulkanRenderPassAccessor passAccessor)) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT backend is not an accessor-backed VulkanRenderPass.");
        }

        VkCommandBuffer commandBuffer = passAccessor.jbpLod$getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT VulkanRenderPass has no command buffer.");
        }
        RenderTarget target = terrainPass.getTarget();
        LodFrameTargets.ImageTarget color = imageTarget(target.getColorTextureView(), VK_IMAGE_ASPECT_COLOR_BIT);
        LodFrameTargets.ImageTarget depth = imageTarget(target.getDepthTextureView(), VK_IMAGE_ASPECT_DEPTH_BIT);
        if (color == null || depth == null) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT requires borrowed Vulkan color and depth views.");
        }
        RenderPass.RenderArea area = passAccessor.jbpLod$getRenderArea();
        Matrix4f projection = lodProjection(Minecraft.getInstance(), matrices.projection(), game);
        float[] projectionValues = projection.get(new float[16]);
        float[] modelViewValues = matrices.modelView().get(new float[16]);
        float[] gameProjectionValues = matrices.projection().get(new float[16]);
        LodFrameTargets.Fog fog = fogParameters == null ? LodFrameTargets.Fog.disabled()
                : new LodFrameTargets.Fog(fogParameters.environmentalStart(), fogParameters.environmentalEnd(),
                fogParameters.red(), fogParameters.green(), fogParameters.blue(), fogParameters.alpha());
        LodFrameTargets frameTargets = new LodFrameTargets(color, depth,
                new LodFrameTargets.Matrices(projectionValues, modelViewValues, gameProjectionValues),
                fog, commandBuffer.address());
        return new LodFrameContext(frameNumber, game.device().address(), commandBuffer, commandBuffer.address(), true,
                area.x(), area.y(), area.width(), area.height(), camera.x, camera.y, camera.z, frameTargets);
    }

    private static Matrix4f lodProjection(Minecraft minecraft, Matrix4fc passProjection, Snapshot game) {
        Matrix4fc rawProjection = minecraft.gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.projectionMatrix;
        Matrix4f extraProjection = new Matrix4f(rawProjection).invert().mul(passProjection);
        float near = minecraft.options.getEffectiveRenderDistance() * 16.0f <= 32.0f ? 8.0f : 16.0f;
        float far = LOD_FAR_PLANE;
        boolean reverseZ = DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
        if (reverseZ) {
            float swap = near;
            near = far;
            far = swap;
        }
        boolean zeroToOne = game.deviceInfo().isZZeroToOne();
        Matrix4f expandedProjection = new Matrix4f(rawProjection)
                .m22((zeroToOne ? far : far + near) / (near - far))
                .m32((zeroToOne ? far : far + far) * near / (near - far));
        return extraProjection.mul(expandedProjection);
    }

    private static LodFrameTargets.ImageTarget imageTarget(GpuTextureView view, int aspectMask) {
        if (view == null) {
            return null;
        }
        if (!(view instanceof VulkanGpuTextureView vulkanView)) {
            throw new IllegalStateException("[JBP-LOD] Sodium CUTOUT target view is not Vulkan: "
                    + view.getClass().getName());
        }
        VulkanGpuTexture texture = vulkanView.texture();
        return new LodFrameTargets.ImageTarget(texture.vkImage(), vulkanView.vkImageView(),
                VulkanConst.toVk(texture.getFormat()), view.getWidth(0), view.getHeight(0), 1,
                aspectMask, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0);
    }
}
