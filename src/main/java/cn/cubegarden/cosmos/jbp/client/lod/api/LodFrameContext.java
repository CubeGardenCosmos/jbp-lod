package cn.cubegarden.cosmos.jbp.client.lod.api;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;

/**
 * Per-recording-scope input to a {@link LodExternalTargetsProvider}. The command buffer and
 * vanilla targets are borrowed and valid only during the callback on the render thread.
 */
public record LodFrameContext(
        long frameNumber,
        long deviceAddress,
        VkCommandBuffer commandBuffer,
        long renderScopeIdentity,
        boolean dynamicRenderingActive,
        int renderAreaX,
        int renderAreaY,
        int renderAreaWidth,
        int renderAreaHeight,
        double cameraX,
        double cameraY,
        double cameraZ,
        LodFrameTargets vanillaTargets
) {
    public LodFrameContext {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(vanillaTargets, "vanillaTargets");
        if (deviceAddress == 0 || renderScopeIdentity == 0) {
            throw new IllegalArgumentException("deviceAddress and renderScopeIdentity must be nonzero");
        }
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
            throw new IllegalArgumentException("camera position must be finite");
        }
    }
}
