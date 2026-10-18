package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuCompletion;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuRetireQueue;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_DST_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_INT_OPAQUE_BLACK;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_LOGIC_OP_COPY;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_DYNAMIC_STATE_FRONT_FACE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;
import static org.lwjgl.vulkan.VK13.vkCmdSetFrontFace;

/**
 * Rasterises block-model faces on the game's Vulkan device and reads the six 16x16 faces back in the
 * upstream {@code ColourDepthTextureData} format. Only the rasteriser is replaced; the baked faces are
 * analysed on the CPU. Render-thread only; nothing here ever waits on the GPU.
 */
final class VulkanModelBaker implements AutoCloseable {
    /** Triangle-list vertices (24 B each); opaque vertices first, then translucent. */
    record ViewGeometry(byte[] vertices, int opaqueVertexCount, int translucentVertexCount) {
    }

    record Result(Object token, ColourDepthTextureData[] faces) {
    }

    private static final int VIEW_COUNT = 6;
    private static final int VERTEX_BYTES = 24;
    private static final int FACE = ModelAtlasLayout.FACE_SIZE;
    private static final int TARGET_WIDTH = ModelAtlasLayout.TILE_WIDTH;
    private static final int TARGET_HEIGHT = ModelAtlasLayout.TILE_HEIGHT;
    private static final int PLANE_BYTES = TARGET_WIDTH * TARGET_HEIGHT * 4;
    private static final int READBACK_BYTES = PLANE_BYTES * 3;
    private static final int INITIAL_VERTEX_BYTES = 64 * 1024;
    private static final int MAX_DEPTH_Q = (1 << 24) - 2;

    private static final Matrix4f[] VIEWS = createViews();

    private final GameVulkan.Snapshot game;
    private final Core core;
    private final Slot[] slots;
    private final ArrayDeque<Slot> free = new ArrayDeque<>();
    private final ArrayDeque<Slot> inFlight = new ArrayDeque<>();
    private boolean closed;
    private boolean destroyed;

    VulkanModelBaker(GameVulkan.Snapshot game, ShaderCompiler compiler, VulkanGpuTextureView blockAtlasView,
                     int slotCount) {
        if (game == null || compiler == null || blockAtlasView == null) {
            throw new NullPointerException("game Vulkan device, shader compiler, and block atlas view are required");
        }
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be at least 1");
        }
        if (blockAtlasView.texture().getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("vanilla block atlas must be RGBA8_UNORM for model baking");
        }
        ModelVk.requireOptimalFeatures(game.physicalDevice(), VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT
                        | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT,
                "model bake colour target");
        ModelVk.requireOptimalFeatures(game.physicalDevice(), VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT,
                "model bake depth target");
        this.game = game;
        this.core = new Core(game, compiler, blockAtlasView.vkImageView());
        this.slots = new Slot[slotCount];
        try {
            for (int i = 0; i < slotCount; i++) {
                slots[i] = new Slot(game, core.commandPool);
                free.addLast(slots[i]);
            }
        } catch (RuntimeException | Error failure) {
            for (Slot slot : slots) {
                if (slot != null) slot.close();
            }
            core.close();
            throw failure;
        }
    }

    int freeSlots() {
        return free.size();
    }

    int inFlight() {
        return inFlight.size();
    }

    /** views.length==6; null or empty entries draw nothing for that view. Never blocks. */
    void submit(Object token, ViewGeometry[] views) {
        ensureOpen();
        if (views == null || views.length != VIEW_COUNT) {
            throw new IllegalArgumentException("exactly six view geometries are required");
        }
        IdentityHashMap<byte[], Integer> offsets = new IdentityHashMap<>();
        List<byte[]> distinct = new ArrayList<>();
        long totalBytes = 0;
        boolean anyGeometry = false;
        for (ViewGeometry view : views) {
            if (view == null) continue;
            byte[] vertices = view.vertices();
            int opaque = view.opaqueVertexCount();
            int translucent = view.translucentVertexCount();
            if (vertices == null || opaque < 0 || translucent < 0 || opaque % 3 != 0 || translucent % 3 != 0
                    || vertices.length != (long) (opaque + translucent) * VERTEX_BYTES
                    || (long) opaque + translucent > Integer.MAX_VALUE / VERTEX_BYTES) {
                throw new IllegalArgumentException("view geometry must hold whole triangles of 24-byte vertices");
            }
            if (opaque + translucent == 0) continue;
            anyGeometry = true;
            if (!offsets.containsKey(vertices)) {
                offsets.put(vertices, (int) totalBytes);
                distinct.add(vertices);
                totalBytes += vertices.length;
            }
        }
        if (!anyGeometry) {
            throw new IllegalArgumentException("all six views are empty; nothing to bake");
        }
        if (totalBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("model bake geometry is too large");
        }
        if (free.isEmpty()) {
            throw new IllegalStateException("no free model bake slot");
        }
        Slot slot = free.pollFirst();
        GpuCompletion completion;
        try {
            slot.ensureVertexCapacity(game, (int) totalBytes);
            ByteBuffer mapped = slot.vertices.mappedBytes();
            for (byte[] vertices : distinct) {
                mapped.put(offsets.get(vertices), vertices, 0, vertices.length);
            }
            slot.vertices.flush(0, totalBytes);
            slot.record(core, views, offsets);
            completion = GpuCompletion.submit(game, slot.command);
        } catch (RuntimeException | Error failure) {
            slot.resetCommand();
            free.addFirst(slot);
            throw failure;
        }
        slot.completion = completion;
        slot.token = token;
        inFlight.addLast(slot);
    }

    /** Non-blocking; finished bakes strictly in submission order. */
    List<Result> pollCompleted() {
        List<Result> out = new ArrayList<>();
        if (closed) {
            return out;
        }
        while (!inFlight.isEmpty()) {
            Slot slot = inFlight.peekFirst();
            if (!slot.completion.isComplete()) {
                break;
            }
            inFlight.pollFirst();
            Object token = slot.token;
            try {
                out.add(new Result(token, slot.readFaces()));
            } finally {
                slot.completion.close();
                slot.completion = null;
                slot.token = null;
                free.addLast(slot);
            }
        }
        return out;
    }

    /** Slots still in flight are retired behind a queue marker and destroyed after their fences. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        boolean busy = false;
        for (Slot slot : inFlight) {
            if (!slot.completion.isComplete()) {
                busy = true;
                break;
            }
        }
        if (!busy) {
            destroyAll();
        } else {
            GpuRetireQueue.retire(GpuCompletion.submitMarker(game), this::destroyAll);
        }
    }

    private void destroyAll() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        for (Slot slot : slots) {
            slot.close();
        }
        free.clear();
        inFlight.clear();
        core.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vulkan model baker is closed");
        }
    }

    private static final class Slot {
        private final VkDevice device;
        private final VmaImage colour;
        private final VmaImage info;
        private final VmaImage depth;
        private final ReadbackBuffer readback;
        private final VkCommandBuffer command;
        private GpuBuffer vertices;
        private GpuCompletion completion;
        private Object token;
        private boolean closed;

        private Slot(GameVulkan.Snapshot game, long commandPool) {
            this.device = game.device();
            VmaImage c = null;
            VmaImage i = null;
            VmaImage d = null;
            ReadbackBuffer r = null;
            GpuBuffer v = null;
            try {
                c = VmaImage.create(game, TARGET_WIDTH, TARGET_HEIGHT, 1, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT, "model bake colour");
                i = VmaImage.create(game, TARGET_WIDTH, TARGET_HEIGHT, 1, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT, "model bake info");
                d = VmaImage.create(game, TARGET_WIDTH, TARGET_HEIGHT, 1, VK_FORMAT_D32_SFLOAT,
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        VK_IMAGE_ASPECT_DEPTH_BIT, "model bake depth");
                r = ReadbackBuffer.allocate(game, READBACK_BYTES);
                v = GpuBuffer.allocate(game, INITIAL_VERTEX_BYTES, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
                this.command = ModelVk.allocateCommandBuffer(game.device(), commandPool, "model bake");
            } catch (RuntimeException | Error failure) {
                if (v != null) v.close();
                if (r != null) r.close();
                if (d != null) d.close();
                if (i != null) i.close();
                if (c != null) c.close();
                throw failure;
            }
            this.colour = c;
            this.info = i;
            this.depth = d;
            this.readback = r;
            this.vertices = v;
        }

        /** Only called while this slot's previous submission (if any) has completed. */
        private void ensureVertexCapacity(GameVulkan.Snapshot game, int bytes) {
            long capacity = vertices.capacity();
            if (bytes <= capacity) {
                return;
            }
            long grown = Math.max((long) bytes, Math.min(capacity * 2, Integer.MAX_VALUE));
            GpuBuffer replacement = GpuBuffer.allocate(game, grown, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
            vertices.close();
            vertices = replacement;
        }

        private void resetCommand() {
            vkResetCommandBuffer(command, 0);
        }

        private void record(Core core, ViewGeometry[] views, IdentityHashMap<byte[], Integer> offsets) {
            ModelVk.beginOneTime(command, "model bake");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Make earlier game writes to the vanilla block atlas visible to our sampling.
                VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)
                        .dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memory));

                long colourStages = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
                long colourAccess = VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
                ModelVk.imageBarrier(command, colour, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_NONE, 0,
                        colourStages, colourAccess);
                ModelVk.imageBarrier(command, info, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_NONE, 0,
                        colourStages, colourAccess);
                ModelVk.imageBarrier(command, depth, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_NONE, 0,
                        VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

                VkRenderingAttachmentInfo.Buffer colours = VkRenderingAttachmentInfo.calloc(2, stack);
                for (int i = 0; i < 2; i++) {
                    VkRenderingAttachmentInfo attachment = colours.get(i).sType$Default()
                            .imageView(i == 0 ? colour.view() : info.view())
                            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                    attachment.clearValue().color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
                }
                VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(depth.view()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                depthAttachment.clearValue().depthStencil().depth(1.0f).stencil(0);
                VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                        .pColorAttachments(colours).pDepthAttachment(depthAttachment);
                rendering.renderArea().offset().set(0, 0);
                rendering.renderArea().extent().set(TARGET_WIDTH, TARGET_HEIGHT);
                vkCmdBeginRendering(command, rendering);

                vkCmdBindVertexBuffers(command, 0, stack.longs(vertices.handle()), stack.longs(0L));
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, core.pipelineLayout, 0,
                        stack.longs(core.descriptorSet), null);
                ByteBuffer matrix = stack.malloc(64).order(ByteOrder.nativeOrder());
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                for (int face = 0; face < VIEW_COUNT; face++) {
                    ViewGeometry view = views[face];
                    if (view == null || view.opaqueVertexCount() + view.translucentVertexCount() == 0) continue;
                    int x = (face >>> 1) * FACE;
                    int y = (face & 1) * FACE;
                    viewport.x(x).y(y).width(FACE).height(FACE).minDepth(0f).maxDepth(1f);
                    scissor.offset().set(x, y);
                    scissor.extent().set(FACE, FACE);
                    vkCmdSetViewport(command, 0, viewport);
                    vkCmdSetScissor(command, 0, scissor);
                    // Views 1, 2, 4 are mirrored; upstream keeps CW-in-NDC there and CCW-in-NDC elsewhere.
                    // Vulkan's framebuffer is y-down, so NDC-CCW is Vulkan-CLOCKWISE.
                    boolean mirrored = face == 1 || face == 2 || face == 4;
                    vkCmdSetFrontFace(command, mirrored ? VK_FRONT_FACE_COUNTER_CLOCKWISE : VK_FRONT_FACE_CLOCKWISE);
                    VIEWS[face].get(0, matrix);
                    vkCmdPushConstants(command, core.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, matrix);
                    int first = offsets.get(view.vertices()) / VERTEX_BYTES;
                    if (view.opaqueVertexCount() > 0) {
                        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, core.opaquePipeline);
                        vkCmdDraw(command, view.opaqueVertexCount(), 1, first, 0);
                    }
                    if (view.translucentVertexCount() > 0) {
                        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, core.translucentPipeline);
                        vkCmdDraw(command, view.translucentVertexCount(), 1, first + view.opaqueVertexCount(), 0);
                    }
                }
                vkCmdEndRendering(command);

                ModelVk.imageBarrier(command, colour, 0, 1, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT);
                ModelVk.imageBarrier(command, info, 0, 1, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT);
                ModelVk.imageBarrier(command, depth, 0, 1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT);

                copyToReadback(stack, colour, 0);
                copyToReadback(stack, info, PLANE_BYTES);
                copyToReadback(stack, depth, 2 * PLANE_BYTES);

                VkBufferMemoryBarrier2.Buffer toHost = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(readback.handle()).offset(0).size(READBACK_BYTES);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default()
                        .pBufferMemoryBarriers(toHost));
                ModelVk.check(vkEndCommandBuffer(command), "vkEndCommandBuffer(model bake)");
            }
        }

        private void copyToReadback(MemoryStack stack, VmaImage image, long bufferOffset) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(bufferOffset).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().set(image.aspect(), 0, 0, 1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(TARGET_WIDTH, TARGET_HEIGHT, 1);
            vkCmdCopyImageToBuffer(command, image.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    readback.handle(), region);
        }

        private ColourDepthTextureData[] readFaces() {
            ByteBuffer data = readback.read();
            ColourDepthTextureData[] faces = new ColourDepthTextureData[VIEW_COUNT];
            for (int face = 0; face < VIEW_COUNT; face++) {
                int fx = (face >>> 1) * FACE;
                int fy = (face & 1) * FACE;
                int[] colours = new int[FACE * FACE];
                int[] depths = new int[FACE * FACE];
                for (int py = 0; py < FACE; py++) {
                    for (int px = 0; px < FACE; px++) {
                        int byteOffset = ((fy + py) * TARGET_WIDTH + fx + px) * 4;
                        int index = px + py * FACE;
                        boolean written = data.get(PLANE_BYTES + byteOffset) != 0;
                        if (!written) {
                            colours[index] = 0;
                            depths[index] = 0xFFFFFF << 8;
                            continue;
                        }
                        boolean tint = data.get(PLANE_BYTES + byteOffset + 1) != 0;
                        float z = data.getFloat(2 * PLANE_BYTES + byteOffset);
                        int q = (int) ((double) z * ((1 << 24) - 1));
                        q = Math.clamp(q, 0, MAX_DEPTH_Q);
                        colours[index] = data.getInt(byteOffset);
                        depths[index] = (q << 8) | 1 | (tint ? 0x80 : 0);
                    }
                }
                faces[face] = new ColourDepthTextureData(colours, depths, FACE, FACE);
            }
            return faces;
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (completion != null) {
                completion.close();
                completion = null;
            }
            vertices.close();
            readback.close();
            depth.close();
            info.close();
            colour.close();
        }
    }

    /** Pipelines, sampler and descriptors shared by every slot; one descriptor set (read-only). */
    private static final class Core {
        private final VkDevice device;
        private long commandPool;
        private long sampler;
        private long setLayout;
        private long descriptorPool;
        private long descriptorSet;
        private long pipelineLayout;
        private long opaquePipeline;
        private long translucentPipeline;
        private boolean closed;

        private Core(GameVulkan.Snapshot game, ShaderCompiler compiler, long sourceView) {
            this.device = game.device();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                commandPool = ModelVk.createCommandPool(game, "model bake");

                VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0).maxLod(0).maxAnisotropy(1)
                        .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK).unnormalizedCoordinates(false);
                ModelVk.check(vkCreateSampler(device, samplerInfo, null, handle), "vkCreateSampler(model bake)");
                sampler = handle.get(0);

                VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                        .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
                VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default().pBindings(binding);
                ModelVk.check(vkCreateDescriptorSetLayout(device, layoutInfo, null, handle),
                        "vkCreateDescriptorSetLayout(model bake)");
                setLayout = handle.get(0);

                VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .maxSets(1).pPoolSizes(poolSize);
                ModelVk.check(vkCreateDescriptorPool(device, poolInfo, null, handle),
                        "vkCreateDescriptorPool(model bake)");
                descriptorPool = handle.get(0);

                VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout));
                LongBuffer pSet = stack.mallocLong(1);
                ModelVk.check(vkAllocateDescriptorSets(device, allocate, pSet), "vkAllocateDescriptorSets(model bake)");
                descriptorSet = pSet.get(0);
                VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(sampler).imageView(sourceView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                        .dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(image);
                vkUpdateDescriptorSets(device, write, null);

                VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64);
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
                ModelVk.check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, handle),
                        "vkCreatePipelineLayout(model bake)");
                pipelineLayout = handle.get(0);

                createPipelines(compiler, stack, handle);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void createPipelines(ShaderCompiler compiler, MemoryStack stack, LongBuffer handle) {
            ShaderCompiler.CompiledShader vertex = compiler.compile("model/bake.vert", Map.of());
            ShaderCompiler.CompiledShader fragment = compiler.compile("model/bake.frag", Map.of());
            long vertexModule = 0;
            long fragmentModule = 0;
            try {
                vertexModule = createShaderModule(vertex.spirv(), stack, handle);
                fragmentModule = createShaderModule(fragment.spirv(), stack, handle);
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule)
                        .pName(stack.UTF8("main"));
                stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule)
                        .pName(stack.UTF8("main"));
                VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack)
                        .binding(0).stride(VERTEX_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributes =
                        VkVertexInputAttributeDescription.calloc(3, stack);
                attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(16);
                attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32_UINT).offset(12);
                VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType$Default().pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes);
                VkPipelineInputAssemblyStateCreateInfo input = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
                VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType$Default().viewportCount(1).scissorCount(1);
                VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType$Default().depthClampEnable(false).rasterizerDiscardEnable(false)
                        .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_BACK_BIT)
                        .frontFace(VK_FRONT_FACE_CLOCKWISE).depthBiasEnable(false).lineWidth(1f);
                VkPipelineMultisampleStateCreateInfo samples = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false);
                VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType$Default().depthTestEnable(true).depthWriteEnable(true)
                        .depthCompareOp(VK_COMPARE_OP_LESS).depthBoundsTestEnable(false).stencilTestEnable(false);
                VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,
                                VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_FRONT_FACE));
                VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                        .pColorAttachmentFormats(stack.ints(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM))
                        .depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);
                VkPipelineColorBlendAttachmentState.Buffer attachments =
                        VkPipelineColorBlendAttachmentState.calloc(2, stack);
                for (int i = 0; i < 2; i++) {
                    attachments.get(i).blendEnable(false).colorWriteMask(VK_COLOR_COMPONENT_R_BIT
                            | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
                }
                VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType$Default().logicOpEnable(false).logicOp(VK_LOGIC_OP_COPY).pAttachments(attachments);
                VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType$Default().pNext(rendering.address()).pStages(stages)
                        .pVertexInputState(vertexInput).pInputAssemblyState(input).pViewportState(viewport)
                        .pRasterizationState(raster).pMultisampleState(samples).pDepthStencilState(depth)
                        .pColorBlendState(blend).pDynamicState(dynamic).layout(pipelineLayout)
                        .renderPass(0L).subpass(0);
                ModelVk.check(vkCreateGraphicsPipelines(device, 0L, info, null, handle),
                        "vkCreateGraphicsPipelines(model opaque)");
                opaquePipeline = handle.get(0);
                // Upstream blend: glBlendFuncSeparatei(0, ONE_MINUS_DST_ALPHA, DST_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)
                attachments.get(0).blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_DST_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
                ModelVk.check(vkCreateGraphicsPipelines(device, 0L, info, null, handle),
                        "vkCreateGraphicsPipelines(model translucent)");
                translucentPipeline = handle.get(0);
            } finally {
                if (fragmentModule != 0) vkDestroyShaderModule(device, fragmentModule, null);
                if (vertexModule != 0) vkDestroyShaderModule(device, vertexModule, null);
            }
        }

        private long createShaderModule(byte[] spirv, MemoryStack stack, LongBuffer handle) {
            ByteBuffer code = stack.malloc(spirv.length);
            code.put(spirv).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            ModelVk.check(vkCreateShaderModule(device, info, null, handle), "vkCreateShaderModule(model bake)");
            return handle.get(0);
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (translucentPipeline != 0) vkDestroyPipeline(device, translucentPipeline, null);
            if (opaquePipeline != 0) vkDestroyPipeline(device, opaquePipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
        }
    }

    private static Matrix4f[] createViews() {
        Matrix4f[] views = new Matrix4f[VIEW_COUNT];
        addView(views, 0, -90, 0, 0, 0);
        addView(views, 1, 90, 0, 0, 0b100);
        addView(views, 2, 0, 180, 0, 0b001);
        addView(views, 3, 0, 0, 0, 0);
        addView(views, 4, 0, 90, 270, 0b100);
        addView(views, 5, 0, 270, 270, 0);
        return views;
    }

    private static void addView(Matrix4f[] views, int index, float pitch, float yaw, float rotation, int flip) {
        PoseStack stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.rotate(axisQuaternion(new Vector3f(0, 0, 1), rotation));
        stack.rotate(axisQuaternion(new Vector3f(1, 0, 0), pitch));
        stack.rotate(axisQuaternion(new Vector3f(0, 1, 0), yaw));
        stack.mulPose(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        Matrix4f view = new Matrix4f(stack.last().pose());
        views[index] = new Matrix4f().set(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, -2, 0, -1, -1, 1, 1).mul(view);
    }

    private static Quaternionf axisQuaternion(Vector3f axis, float degrees) {
        float half = (float) Math.toRadians(degrees) * 0.5f;
        float sine = (float) Math.sin(half);
        float inverseLength = (float) (1.0 / Math.sqrt(axis.lengthSquared()));
        return new Quaternionf(axis.x * inverseLength * sine, axis.y * inverseLength * sine,
                axis.z * inverseLength * sine, Math.cos(half));
    }
}
