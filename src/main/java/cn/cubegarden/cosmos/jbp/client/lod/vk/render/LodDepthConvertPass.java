package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * Re-expresses the game's depth buffer in the LOD projection's depth range inside the LOD pre-pass
 * command buffer (a full-screen triangle that writes {@code gl_FragDepth}); replaces upstream's GL
 * framebuffer depth copy. The source is the previous frame's final game depth, matching upstream's
 * one-frame-latent HiZ.
 */
final class LodDepthConvertPass implements AutoCloseable {
    static final int GAME_DEPTH_BINDING = 0;
    static final int PARAMS_BINDING = 1;
    private static final int PARAMS_BYTES = 32;

    private final VulkanLodPipeline pipeline;
    private final GpuBuffer[] params;
    private final long depthSampler;

    LodDepthConvertPass(GameVulkan.Snapshot game, ShaderCompiler compiler, int frameSlots, long depthSampler) {
        this.depthSampler = depthSampler;
        ShaderCompiler.CompiledShader vertex = compiler.compile("lod/depth_convert.vert", Map.of());
        ShaderCompiler.CompiledShader fragment = compiler.compile("lod/depth_convert.frag", Map.of());
        VulkanLodPipeline created = VulkanLodPipeline.create(game, vertex, fragment,
                VulkanLodPipeline.State.of(VK_FORMAT_UNDEFINED, LodDepthTarget.FORMAT, true, true,
                        VK_COMPARE_OP_ALWAYS, VulkanLodPipeline.BlendMode.OPAQUE), frameSlots);
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
     * @param gameDepthView depth-aspect sampled view of the game's depth image (GENERAL layout)
     */
    void record(VkCommandBuffer commandBuffer, int frameSlot, long gameDepthView,
                LodDepthConversion game, LodDepthConversion lod, LodDepthTarget target) {
        GpuBuffer paramBuffer = params[frameSlot];
        game.writeVec4(paramBuffer.mappedBytes(), 0);
        lod.writeVec4(paramBuffer.mappedBytes(), LodDepthConversion.BYTE_SIZE);
        paramBuffer.flush(0, PARAMS_BYTES);
        pipeline.writeCombinedImageSampler(frameSlot, GAME_DEPTH_BINDING, depthSampler, gameDepthView,
                VK_IMAGE_LAYOUT_GENERAL);
        pipeline.writeBuffer(frameSlot, PARAMS_BINDING, paramBuffer.handle(), 0, PARAMS_BYTES);

        LodRenderBarriers.gameDepthWritesToSampledRead(commandBuffer);
        LodRenderBarriers.depthImageToGeneral(commandBuffer, target.image(), target.layoutBeforeUse(),
                LodRenderBarriers.LOD_DEPTH_USER_STAGES, LodRenderBarriers.LOD_DEPTH_USER_ACCESS,
                LodRenderBarriers.DEPTH_TEST_STAGES, LodRenderBarriers.DEPTH_ATTACHMENT_ACCESS);
        target.markUsed();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                    .pDepthAttachment(depth);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(target.width(), target.height());
            vkCmdBeginRendering(commandBuffer, rendering);
        }
        pipeline.bind(commandBuffer, frameSlot, 0, 0, target.width(), target.height());
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdEndRendering(commandBuffer);

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
