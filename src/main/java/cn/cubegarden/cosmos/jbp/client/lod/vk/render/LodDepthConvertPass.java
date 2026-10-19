package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.nio.ByteOrder;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;

/**
 * Seeds the LOD depth/stencil target inside the LOD pre-pass command buffer, mirroring upstream's
 * setup_stencil_depth: the target is cleared to FAR depth and stencil 1, then a full-screen triangle
 * writes NEAR depth and stencil 0 at vanilla pixels and discards sky pixels (game depth == FAR), so
 * LOD draws gated on stencil EQUAL 1 land only on sky. The source is an owned vanilla-only depth
 * snapshot captured before the previous frame's LOD composite; zero means no history yet.
 */
final class LodDepthConvertPass implements AutoCloseable {
    static final int GAME_DEPTH_BINDING = 0;
    static final int PARAMS_BINDING = 1;
    private static final int PARAMS_BYTES = 16;

    private final VulkanLodPipeline pipeline;
    private final GpuBuffer[] params;
    private final long depthSampler;

    LodDepthConvertPass(GameVulkan.Snapshot game, ShaderCompiler compiler, int frameSlots, long depthSampler) {
        this.depthSampler = depthSampler;
        ShaderCompiler.CompiledShader vertex = compiler.compile("lod/depth_convert.vert", Map.of());
        ShaderCompiler.CompiledShader fragment = compiler.compile("lod/depth_convert.frag", Map.of());
        VulkanLodPipeline created = VulkanLodPipeline.create(game, vertex, fragment,
                VulkanLodPipeline.State.of(VK_FORMAT_UNDEFINED, LodDepthTarget.SEED_FORMAT, true, true,
                        VK_COMPARE_OP_ALWAYS, VulkanLodPipeline.BlendMode.OPAQUE)
                        .withStencil(VulkanLodPipeline.Stencil.SEED_VANILLA), frameSlots);
        GpuBuffer[] buffers = new GpuBuffer[frameSlots];
        try {
            for (int i = 0; i < frameSlots; i++) {
                buffers[i] = GpuBuffer.allocate(game, PARAMS_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
            }
        } catch (RuntimeException | Error failure) {
            for (GpuBuffer buffer : buffers) {
                if (buffer != null) buffer.close();
            }
            created.close();
            throw failure;
        }
        this.pipeline = created;
        this.params = buffers;
    }

    /**
     * @param gameDepthView owned vanilla-only depth view (GENERAL), or zero on the first frame
     * @param reverseZ      the game's depth convention; the LOD target uses the same one (FAR = 0 and NEAR = 1 when true)
     */
    void record(VkCommandBuffer commandBuffer, int frameSlot, long gameDepthView, boolean reverseZ,
                LodDepthTarget target) {
        float gameFar = reverseZ ? 0.0f : 1.0f;
        float lodNear = reverseZ ? 1.0f : 0.0f;
        if (gameDepthView != 0) {
            GpuBuffer paramBuffer = params[frameSlot];
            paramBuffer.mappedBytes().order(ByteOrder.LITTLE_ENDIAN).putFloat(0, gameFar).putFloat(Float.BYTES, lodNear);
            paramBuffer.flush(0, PARAMS_BYTES);
            pipeline.writeCombinedImageSampler(frameSlot, GAME_DEPTH_BINDING, depthSampler, gameDepthView,
                    VK_IMAGE_LAYOUT_GENERAL);
            pipeline.writeBuffer(frameSlot, PARAMS_BINDING, paramBuffer.handle(), 0, PARAMS_BYTES);
        }

        LodRenderBarriers.gameDepthWritesToSampledRead(commandBuffer);
        LodRenderBarriers.depthImageToGeneral(commandBuffer, target.image(), target.layoutBeforeUse(),
                target.aspectMask(), LodRenderBarriers.LOD_DEPTH_USER_STAGES, LodRenderBarriers.LOD_DEPTH_USER_ACCESS,
                LodRenderBarriers.DEPTH_TEST_STAGES, LodRenderBarriers.DEPTH_ATTACHMENT_ACCESS);
        target.markUsed();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue clear = VkClearValue.calloc(stack);
            clear.depthStencil().depth(gameFar).stencil(1);
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.attachmentView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(clear);
            VkRenderingAttachmentInfo stencil = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.attachmentView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(clear);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                    .pDepthAttachment(depth).pStencilAttachment(stencil);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(target.width(), target.height());
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
        }
        if (gameDepthView != 0) {
            pipeline.bind(commandBuffer, frameSlot, 0, 0, target.width(), target.height());
            vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        }
        vkCmdEndRenderingKHR(commandBuffer);

        LodRenderBarriers.lodDepthWritesToReaders(commandBuffer);
        LodRenderBarriers.sampledReadToGameDepthWrites(commandBuffer);
    }

    @Override
    public void close() {
        for (GpuBuffer buffer : params) {
            buffer.close();
        }
        pipeline.close();
    }
}
