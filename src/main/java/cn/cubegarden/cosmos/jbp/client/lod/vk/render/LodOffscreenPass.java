package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameContext;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Owned LOD scopes followed by a depth-tested composite in the game's already-active CUTOUT scope.
 * Every image, uniform and descriptor is indexed by the retired LOD frame slot. The owner must keep
 * this pass alive until both the pre-pass submission and the game's composite submission retire.
 */
final class LodOffscreenPass implements AutoCloseable {
    private final VkDevice device;
    private final int width, height, colorFormat;
    private final LodColorTarget[] colors;
    private final LodDepthTarget[] depths;
    private final GpuBuffer[] params;
    private VulkanLodPipeline composite;
    private long sampler;
    private boolean closed;

    LodOffscreenPass(GameVulkan.Snapshot game, ShaderCompiler compiler, int colorFormat,
                     int gameDepthFormat, int width, int height, int frameSlots, boolean reverseZ) {
        if (width <= 0 || height <= 0 || frameSlots <= 0) throw new IllegalArgumentException("Invalid offscreen extent/slots");
        this.device = game.device();
        this.width = width;
        this.height = height;
        this.colorFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        colors = new LodColorTarget[frameSlots];
        depths = new LodDepthTarget[frameSlots];
        params = new GpuBuffer[frameSlots];
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0);
                int result = vkCreateSampler(device, info, null, handle);
                if (result != VK_SUCCESS) throw new IllegalStateException("LOD composite sampler creation failed: " + result);
                sampler = handle.get(0);
            }
            composite = VulkanLodPipeline.create(game,
                    compiler.compile("lod/depth_convert.vert", Map.of()),
                    compiler.compile("lod/offscreen_composite.frag", Map.of()),
                    VulkanLodPipeline.State.of(colorFormat, gameDepthFormat, true, true,
                            reverseZ ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL,
                            VulkanLodPipeline.BlendMode.ALPHA), frameSlots);
            for (int slot = 0; slot < frameSlots; slot++) {
                colors[slot] = new LodColorTarget(game, this.colorFormat, width, height);
                depths[slot] = LodDepthTarget.create(game, width, height, LodDepthTarget.SEED_FORMAT);
                params[slot] = GpuBuffer.allocate(game, 32, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
                composite.writeCombinedImageSampler(slot, 0, sampler, colors[slot].view(), VK_IMAGE_LAYOUT_GENERAL);
                composite.writeCombinedImageSampler(slot, 1, sampler, depths[slot].view(), VK_IMAGE_LAYOUT_GENERAL);
                composite.writeBuffer(slot, 2, params[slot].handle(), 0, 32);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    int width() { return width; }
    int height() { return height; }
    int colorFormat() { return colorFormat; }
    long colorView(int slot) { return colors[slot].view(); }
    long colorImage(int slot) { return colors[slot].image(); }
    long depthView(int slot) { return depths[slot].view(); }
    LodDepthTarget depthTarget(int slot) { return depths[slot]; }

    /** Depth and stencil must already have been seeded by LodDepthConvertPass for this slot. */
    void begin(VkCommandBuffer commandBuffer, int slot, boolean clearColor) {
        colors[slot].prepare(commandBuffer);
        LodRenderBarriers.memory(commandBuffer, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                LodRenderBarriers.DEPTH_TEST_STAGES, LodRenderBarriers.DEPTH_ATTACHMENT_ACCESS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                    .imageView(colorView(slot)).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.get(0).clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(depths[slot].attachmentView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingAttachmentInfo stencil = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(depths[slot].attachmentView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                    .pColorAttachments(color).pDepthAttachment(depth).pStencilAttachment(stencil);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(width, height);
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
        }
    }

    void end(VkCommandBuffer commandBuffer) {
        vkCmdEndRenderingKHR(commandBuffer);
        LodRenderBarriers.memory(commandBuffer,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | LodRenderBarriers.DEPTH_TEST_STAGES,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | LodRenderBarriers.DEPTH_TEST_STAGES,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | LodRenderBarriers.DEPTH_ATTACHMENT_ACCESS);
    }

    /** No rendering-scope changes or barriers are legal here: the game owns the active scope. */
    void recordComposite(VkCommandBuffer commandBuffer, int slot, LodFrameContext context,
                         LodDepthConversion gameDepth, LodDepthConversion lodDepth) {
        gameDepth.writeVec4(params[slot].mappedBytes(), 0);
        lodDepth.writeVec4(params[slot].mappedBytes(), LodDepthConversion.BYTE_SIZE);
        params[slot].flush(0, 32);
        composite.bind(commandBuffer, slot, context.renderAreaX(), context.renderAreaY(),
                context.renderAreaWidth(), context.renderAreaHeight());
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (composite != null) composite.close();
        for (GpuBuffer buffer : params) if (buffer != null) buffer.close();
        for (LodColorTarget color : colors) if (color != null) color.close();
        for (LodDepthTarget depth : depths) if (depth != null) depth.close();
        if (sampler != 0) vkDestroySampler(device, sampler, null);
    }
}
