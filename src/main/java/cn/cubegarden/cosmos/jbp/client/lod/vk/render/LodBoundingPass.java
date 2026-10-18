package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Upstream near-zone bounding raster ({@code BoundRenderer} with {@code chunkoutline/outline.vsh|fsh}):
 * every frame, one 16^3 cube per built, in-range Sodium section is rasterised into a depth-only target
 * cleared to the inverse clear depth, keeping the farthest bound per pixel. {@code quads.frag} discards
 * LOD fragments that are not nearer than that bound, so LOD geometry never shows through the
 * loaded-chunk volume.
 *
 * <p>Deliberate difference: upstream rasterises back faces only. This pass uses no face culling, so the
 * farthest face of each section is the same back face; the front faces lie nearer and never win the
 * compare, so the stored depth is identical.</p>
 */
final class LodBoundingPass implements AutoCloseable {
    static final int UNIFORM_BINDING = 0;
    static final int SECTION_BINDING = 1;
    /** std140 {@code BoundingUniform}: mat4 MVP, ivec4 cameraBlockPos, vec4 negInnerBlock. */
    static final int UNIFORM_BYTES = 96;
    private static final int CAMERA_BLOCK_OFFSET = 64;
    private static final int NEG_INNER_OFFSET = 80;
    /** Vertices per section cube (12 triangles). */
    private static final int CUBE_VERTICES = 36;
    /** One packed {@code ivec2} per section. */
    private static final int SECTION_STRIDE = 8;
    private static final int INITIAL_SECTION_BYTES = 64 * SECTION_STRIDE;

    private final GameVulkan.Snapshot game;
    private final VulkanLodPipeline pipeline;
    private final LodDepthTarget[] targets;
    private final GpuBuffer[] uniforms;
    private final GpuBuffer[] sections;
    private final float clearDepth;

    LodBoundingPass(GameVulkan.Snapshot game, ShaderCompiler compiler, int frameSlots, boolean reverseZ,
                    int width, int height) {
        this.game = game;
        Map<String, String> defines = new HashMap<>();
        if (reverseZ) {
            defines.put("USE_REVERSE_Z", "1");
        }
        ShaderCompiler.CompiledShader vertex = compiler.compile("lod/bounding/bounding.vert", Map.copyOf(defines));
        ShaderCompiler.CompiledShader fragment = compiler.compile("lod/bounding/bounding.frag", Map.copyOf(defines));
        // Upstream furtherDepthCompare: the farthest bound wins.
        this.pipeline = VulkanLodPipeline.create(game, vertex, fragment,
                VulkanLodPipeline.State.of(VK_FORMAT_UNDEFINED, LodDepthTarget.DEPTH_ONLY_FORMAT, true, true,
                        reverseZ ? VK_COMPARE_OP_LESS : VK_COMPARE_OP_GREATER,
                        VulkanLodPipeline.BlendMode.OPAQUE), frameSlots);
        // Upstream inverseClearDepth.
        this.clearDepth = reverseZ ? 1.0f : 0.0f;
        this.targets = new LodDepthTarget[frameSlots];
        this.uniforms = new GpuBuffer[frameSlots];
        this.sections = new GpuBuffer[frameSlots];
        for (int i = 0; i < frameSlots; i++) {
            targets[i] = LodDepthTarget.create(game, width, height, LodDepthTarget.DEPTH_ONLY_FORMAT);
            uniforms[i] = GpuBuffer.allocate(game, UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
            sections[i] = GpuBuffer.allocate(game, INITIAL_SECTION_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        }
    }

    /** Depth view sampled by {@code quads.frag} for this frame slot (GENERAL layout). */
    long depthView(int frameSlot) {
        return targets[frameSlot].view();
    }

    /**
     * Records the bounding raster for this slot into the LOD pre-pass command buffer. The slot must have
     * retired (it was returned by {@code beginFrame}), so its section buffer may be replaced here.
     */
    void record(VkCommandBuffer commandBuffer, LodFrameBuffers slot, float[] mvp, double cameraX, double cameraY,
                double cameraZ, float renderDistanceBlocks, LodBoundingSectionStore store) {
        int frameSlot = slot.slotIndex();
        int count = store.count();
        GpuBuffer section = ensureSections(frameSlot, count);
        GpuBuffer uniform = uniforms[frameSlot];
        writeUniform(uniform.mappedBytes(), mvp, cameraX, cameraY, cameraZ, renderDistanceBlocks);
        uniform.flush(0, UNIFORM_BYTES);
        ByteBuffer sectionBytes = section.mappedBytes().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            long packed = store.get(i);
            sectionBytes.putInt(i * SECTION_STRIDE, (int) packed);
            sectionBytes.putInt(i * SECTION_STRIDE + Integer.BYTES, (int) (packed >>> 32));
        }
        if (count > 0) {
            section.flush(0, (long) count * SECTION_STRIDE);
        }
        pipeline.writeBuffer(frameSlot, UNIFORM_BINDING, uniform.handle(), 0, UNIFORM_BYTES);
        pipeline.writeBuffer(frameSlot, SECTION_BINDING, section.handle(), 0, section.capacity());

        LodDepthTarget target = targets[frameSlot];
        LodRenderBarriers.depthImageToGeneral(commandBuffer, target.image(), target.layoutBeforeUse(),
                target.aspectMask(), LodRenderBarriers.LOD_DEPTH_USER_STAGES, LodRenderBarriers.LOD_DEPTH_USER_ACCESS,
                LodRenderBarriers.DEPTH_TEST_STAGES, LodRenderBarriers.DEPTH_ATTACHMENT_ACCESS);
        target.markUsed();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue clear = VkClearValue.calloc(stack);
            clear.depthStencil().depth(clearDepth).stencil(0);
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.attachmentView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(clear);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                    .pDepthAttachment(depth);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(target.width(), target.height());
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
        }
        if (count > 0) {
            pipeline.bind(commandBuffer, frameSlot, 0, 0, target.width(), target.height());
            vkCmdDraw(commandBuffer, CUBE_VERTICES, count, 0, 0);
        }
        vkCmdEndRenderingKHR(commandBuffer);
        LodRenderBarriers.lodDepthWritesToReaders(commandBuffer);
    }

    /** Grows this slot's section buffer when needed; the slot has retired, so the old buffer is idle. */
    private GpuBuffer ensureSections(int frameSlot, int count) {
        GpuBuffer current = sections[frameSlot];
        long needed = (long) count * SECTION_STRIDE;
        if (current.capacity() >= needed) {
            return current;
        }
        long capacity = Math.max(needed, current.capacity() * 2L);
        GpuBuffer replacement = GpuBuffer.allocate(game, capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        sections[frameSlot] = replacement;
        current.close();
        return replacement;
    }

    private static void writeUniform(ByteBuffer target, float[] mvp, double cameraX, double cameraY, double cameraZ,
                                     float renderDistanceBlocks) {
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            bytes.putFloat(i * Float.BYTES, mvp[i]);
        }
        int blockX = (int) Math.floor(cameraX);
        int blockY = (int) Math.floor(cameraY);
        int blockZ = (int) Math.floor(cameraZ);
        bytes.putInt(CAMERA_BLOCK_OFFSET, blockX);
        bytes.putInt(CAMERA_BLOCK_OFFSET + 4, blockY);
        bytes.putInt(CAMERA_BLOCK_OFFSET + 8, blockZ);
        bytes.putInt(CAMERA_BLOCK_OFFSET + 12, 0);
        bytes.putFloat(NEG_INNER_OFFSET, (float) (cameraX - blockX));
        bytes.putFloat(NEG_INNER_OFFSET + 4, (float) (cameraY - blockY));
        bytes.putFloat(NEG_INNER_OFFSET + 8, (float) (cameraZ - blockZ));
        bytes.putFloat(NEG_INNER_OFFSET + 12, renderDistanceBlocks);
    }

    @Override
    public void close() {
        pipeline.close();
        for (int i = 0; i < targets.length; i++) {
            targets[i].close();
            uniforms[i].close();
            sections[i].close();
        }
    }
}
