package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Fullscreen reduction blit into one single-level R32_SFLOAT view with dynamic rendering.
 *
 * <p>Derived from MCRcortex/voxy 263: {@code HiZBuffer2.java} {@code hizInitial}
 * ({@code hiz/blit.vsh} + {@code hiz/blit.fsh}, {@code OUTPUT_COLOUR}, {@code GL_TRIANGLE_FAN} of 4
 * vertices, depth test disabled, viewport = destination mip extent). The same pass is reused for mip levels
 * above 0 by {@link HizLayout}'s blit-only schedule (upstream {@code HiZBuffer.buildMipChain}).
 */
final class HizBlitPipeline implements AutoCloseable {
    private static final String VERTEX = "hiz/blit.vert";
    private static final String FRAGMENT = "hiz/blit.frag";

    private final VkDevice device;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean closed;

    HizBlitPipeline(GameVulkan.Snapshot game, ShaderCompiler compiler, boolean reverseZ) {
        Objects.requireNonNull(compiler, "compiler");
        this.device = game.device();
        Map<String, String> reverse = reverseZ
                ? Map.of("USE_REVERSE_Z", "1", "USE_ZERO_ONE_DEPTH", "1")
                : Map.of();
        Map<String, String> vertexDefines = reverse;
        Map<String, String> fragmentDefines = reverseZ
                ? Map.of("OUTPUT_COLOUR", "1", "USE_REVERSE_Z", "1", "USE_ZERO_ONE_DEPTH", "1")
                : Map.of("OUTPUT_COLOUR", "1");
        ShaderCompiler.CompiledShader vertexShader = compiler.compile(VERTEX, vertexDefines);
        ShaderCompiler.CompiledShader fragmentShader = compiler.compile(FRAGMENT, fragmentDefines);

        long createdSetLayout = 0;
        long createdPipelineLayout = 0;
        long vertexModule = 0;
        long fragmentModule = 0;
        long createdPipeline = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binding), null, handle), "vkCreateDescriptorSetLayout");
            createdSetLayout = handle.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(createdSetLayout)), null, handle),
                    "vkCreatePipelineLayout");
            createdPipelineLayout = handle.get(0);
            vertexModule = createModule(vertexShader.spirv(), stack, handle);
            fragmentModule = createModule(fragmentShader.spirv(), stack, handle);
            createdPipeline = createPipeline(vertexModule, fragmentModule, createdPipelineLayout, stack, handle);
        } catch (RuntimeException | Error failure) {
            if (createdPipeline != 0) vkDestroyPipeline(device, createdPipeline, null);
            if (vertexModule != 0) vkDestroyShaderModule(device, vertexModule, null);
            if (fragmentModule != 0) vkDestroyShaderModule(device, fragmentModule, null);
            if (createdPipelineLayout != 0) vkDestroyPipelineLayout(device, createdPipelineLayout, null);
            if (createdSetLayout != 0) vkDestroyDescriptorSetLayout(device, createdSetLayout, null);
            throw failure;
        }
        vkDestroyShaderModule(device, vertexModule, null);
        vkDestroyShaderModule(device, fragmentModule, null);
        this.setLayout = createdSetLayout;
        this.pipelineLayout = createdPipelineLayout;
        this.pipeline = createdPipeline;
    }

    /** Descriptor sets (one combined sampler each) plus the pool that owns them. */
    static final class Sets implements AutoCloseable {
        private final VkDevice device;
        private final long pool;
        private final long[] sets;

        private Sets(VkDevice device, long pool, long[] sets) {
            this.device = device;
            this.pool = pool;
            this.sets = sets;
        }

        long get(int index) {
            return sets[index];
        }

        int count() {
            return sets.length;
        }

        @Override
        public void close() {
            vkDestroyDescriptorPool(device, pool, null);
        }
    }

    Sets allocateSets(int count) {
        ensureOpen();
        if (count <= 0) {
            throw new IllegalArgumentException("descriptor set count must be positive");
        }
        long pool = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(count);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(count).pPoolSizes(size), null, handle), "vkCreateDescriptorPool");
            pool = handle.get(0);
            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) {
                layouts.put(i, setLayout);
            }
            LongBuffer allocated = stack.mallocLong(count);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(layouts), allocated),
                    "vkAllocateDescriptorSets");
            long[] sets = new long[count];
            allocated.get(sets);
            return new Sets(device, pool, sets);
        } catch (RuntimeException | Error failure) {
            if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
            throw failure;
        }
    }

    void writeSampler(long set, long sampler, long imageView, int imageLayout) {
        ensureOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(imageView).imageLayout(imageLayout);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(image);
            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /**
     * Records one dynamic-rendering pass writing {@code colorView}; the caller has already transitioned the
     * target subresource to COLOR_ATTACHMENT_OPTIMAL. MUST NOT be called inside an open rendering scope.
     */
    void draw(VkCommandBuffer commandBuffer, long set, long colorView, int width, int height) {
        ensureOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfo.Buffer attachment = VkRenderingAttachmentInfo.calloc(1, stack)
                    .sType$Default().imageView(colorView)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default()
                    .layerCount(1).pColorAttachments(attachment);
            rendering.renderArea().offset().set(0, 0);
            rendering.renderArea().extent().set(width, height);
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                    stack.longs(set), null);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetScissor(commandBuffer, 0, scissor);
            vkCmdDraw(commandBuffer, 4, 1, 0, 0);
            vkCmdEndRenderingKHR(commandBuffer);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, setLayout, null);
    }

    private long createModule(byte[] spirv, MemoryStack stack, LongBuffer handle) {
        ByteBuffer code = stack.malloc(spirv.length);
        code.put(spirv).flip();
        check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(code), null, handle), "vkCreateShaderModule");
        return handle.get(0);
    }

    private long createPipeline(long vertexModule, long fragmentModule, long layout,
                                MemoryStack stack, LongBuffer handle) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule)
                .pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule)
                .pName(stack.UTF8("main"));
        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default();
        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN).primitiveRestartEnable(false);
        VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default().viewportCount(1).scissorCount(1);
        VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default().depthClampEnable(false).rasterizerDiscardEnable(false)
                .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(false).lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .blendEnable(false).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default().logicOpEnable(false).pAttachments(blendAttachment);
        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
        VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType$Default().colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(VK_FORMAT_R32_SFLOAT));
        VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly).pViewportState(viewport).pRasterizationState(rasterization)
                .pMultisampleState(multisample).pColorBlendState(blend).pDynamicState(dynamic)
                .layout(layout).renderPass(VK_NULL_HANDLE).subpass(0);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, handle), "vkCreateGraphicsPipelines");
        return handle.get(0);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("HiZ blit pipeline is closed");
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
}
