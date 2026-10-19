package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.Map;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

/** Owns vanilla-only depth history, captured after game rendering and before LOD composition. */
final class LodVanillaDepthSnapshot implements AutoCloseable {
    private final VulkanLodPipeline pipeline;
    private final LodDepthTarget[] targets;
    private final long sampler;
    private boolean closed;

    LodVanillaDepthSnapshot(GameVulkan.Snapshot game, ShaderCompiler compiler, int frameSlots,
                            int width, int height, long sampler) {
        this.sampler = sampler;
        ShaderCompiler.CompiledShader vertex = compiler.compile("lod/depth_convert.vert", Map.of());
        ShaderCompiler.CompiledShader fragment = compiler.compile("lod/vanilla_depth_snapshot.frag", Map.of());
        VulkanLodPipeline created = VulkanLodPipeline.create(game, vertex, fragment,
                VulkanLodPipeline.State.of(VK_FORMAT_UNDEFINED, LodDepthTarget.DEPTH_ONLY_FORMAT,
                        true, true, VK_COMPARE_OP_ALWAYS, VulkanLodPipeline.BlendMode.OPAQUE), frameSlots);
        LodDepthTarget[] allocated = new LodDepthTarget[frameSlots];
        try {
            for (int slot = 0; slot < frameSlots; slot++) {
                allocated[slot] = LodDepthTarget.create(game, width, height, LodDepthTarget.DEPTH_ONLY_FORMAT);
            }
        } catch (RuntimeException | Error failure) {
            for (LodDepthTarget target : allocated) {
                if (target != null) target.close();
            }
            created.close();
            throw failure;
        }
        pipeline = created;
        targets = allocated;
    }

    /** The source view is sampled in GENERAL; no transfer usage or source layout change is required. */
    void record(VkCommandBuffer commandBuffer, int slot, long gameDepthView, boolean reverseZ) {
        LodDepthTarget target = targets[slot];
        pipeline.writeCombinedImageSampler(slot, 0, sampler, gameDepthView, VK_IMAGE_LAYOUT_GENERAL);
        LodRenderBarriers.gameDepthWritesToSampledRead(commandBuffer);
        // Includes earlier reads of this history slot, even when it was this frame's seed source.
        LodRenderBarriers.depthImageToGeneral(commandBuffer, target.image(), target.layoutBeforeUse(),
                target.aspectMask(), LodRenderBarriers.LOD_DEPTH_USER_STAGES,
                LodRenderBarriers.LOD_DEPTH_USER_ACCESS, LodRenderBarriers.DEPTH_TEST_STAGES,
                LodRenderBarriers.DEPTH_ATTACHMENT_ACCESS);
        target.markUsed();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue clear = VkClearValue.calloc(stack);
            clear.depthStencil().depth(reverseZ ? 0.0f : 1.0f);
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.attachmentView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(clear);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                    .pDepthAttachment(depth);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(target.width(), target.height());
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
        }
        pipeline.bind(commandBuffer, slot, 0, 0, target.width(), target.height());
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdEndRenderingKHR(commandBuffer);
        LodRenderBarriers.lodDepthWritesToReaders(commandBuffer);
        LodRenderBarriers.sampledReadToGameDepthWrites(commandBuffer);
    }

    long view(int slot) {
        return targets[slot].view();
    }

    /** Caller must retire all submissions using these views before destroying the snapshot. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pipeline.close();
        for (LodDepthTarget target : targets) target.close();
    }
}
