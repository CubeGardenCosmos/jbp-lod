package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.compute.LodComputeExecutor;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * Upstream occlusion-test raster ({@code cull/raster.vert|frag}): one instanced AABB cube per
 * traversal-selected section, depth-tested (read-only) against the LOD depth target; fragments
 * that pass write the section's visibility word. Drawn from the GPU-generated cull command in the
 * draw-control buffer, inside its own dynamic-rendering scope in the LOD pre-pass command buffer.
 */
final class LodCullRasterPass implements AutoCloseable {
    static final int SCENE_BINDING = 0;
    static final int SECTION_METADATA_BINDING = 1;
    static final int VISIBILITY_BINDING = 2;
    static final int INDIRECT_LOOKUP_BINDING = 3;
    private static final int CULL_COMMAND_STRIDE = 20;

    private final VulkanLodPipeline pipeline;
    private final long indexBuffer;

    LodCullRasterPass(GameVulkan.Snapshot game, ShaderCompiler compiler, int frameSlots, boolean reverseZ,
                      long indexBuffer) {
        this.indexBuffer = indexBuffer;
        Map<String, String> defines = new HashMap<>();
        if (reverseZ) {
            defines.put("USE_REVERSE_Z", "1");
        }
        ShaderCompiler.CompiledShader vertex = compiler.compile("lod/gl46/cull/raster.vert", Map.copyOf(defines));
        ShaderCompiler.CompiledShader fragment = compiler.compile("lod/gl46/cull/raster.frag", Map.copyOf(defines));
        this.pipeline = VulkanLodPipeline.create(game, vertex, fragment,
                VulkanLodPipeline.State.of(VK_FORMAT_UNDEFINED, LodDepthTarget.FORMAT, true, false,
                        reverseZ ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL,
                        VulkanLodPipeline.BlendMode.OPAQUE), frameSlots);
    }

    void record(VkCommandBuffer commandBuffer, LodFrameBuffers slot, LodDepthTarget target,
                GpuBuffer sectionMetadata, GpuBuffer visibility) {
        int frameSlot = slot.slotIndex();
        GpuBuffer scene = slot.sceneUniform();
        GpuBuffer lookup = slot.indirectSectionLookup();
        pipeline.writeBuffer(frameSlot, SCENE_BINDING, scene.handle(), 0, scene.capacity());
        pipeline.writeBuffer(frameSlot, SECTION_METADATA_BINDING, sectionMetadata.handle(), 0,
                sectionMetadata.capacity());
        pipeline.writeBuffer(frameSlot, VISIBILITY_BINDING, visibility.handle(), 0, visibility.capacity());
        pipeline.writeBuffer(frameSlot, INDIRECT_LOOKUP_BINDING, lookup.handle(), 0, lookup.capacity());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                    .pDepthAttachment(depth);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(target.width(), target.height());
            vkCmdBeginRendering(commandBuffer, rendering);
        }
        pipeline.bind(commandBuffer, frameSlot, 0, 0, target.width(), target.height());
        vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexedIndirect(commandBuffer, slot.drawControl().handle(),
                LodComputeExecutor.CULL_COMMAND_OFFSET, 1, CULL_COMMAND_STRIDE);
        vkCmdEndRendering(commandBuffer);
    }

    @Override
    public void close() {
        pipeline.close();
    }
}
