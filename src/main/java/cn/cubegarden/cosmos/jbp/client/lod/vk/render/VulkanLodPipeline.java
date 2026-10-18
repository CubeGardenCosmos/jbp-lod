package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStencilOpState;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ZERO;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_LOGIC_OP_COPY;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STENCIL_OP_KEEP;
import static org.lwjgl.vulkan.VK10.VK_STENCIL_OP_REPLACE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

/**
 * Device-owned dynamic-rendering graphics pipeline with reflected set-0 descriptors, explicit
 * fixed-function state, and one pre-allocated descriptor set per LOD frame slot.
 *
 * <p>The pipeline never owns a sampler, image, or buffer it is told to bind; descriptors are
 * rewritten for a frame slot only after that slot has retired (the {@code LodGpuBuffers} ring rule).</p>
 */
public final class VulkanLodPipeline implements AutoCloseable {
    private final VkDevice device;
    private final long deviceAddress;
    private final long pipeline;
    private final long layout;
    private final long descriptorPool;
    private final long[] descriptorSetLayouts;
    private final long[][] descriptorSets;
    private final Map<Integer, Binding> bindingsByNumber;
    private boolean closed;

    private VulkanLodPipeline(VkDevice device, long pipeline, long layout, long descriptorPool,
                              long[] descriptorSetLayouts, long[][] descriptorSets,
                              Map<Integer, Binding> bindingsByNumber) {
        this.device = device;
        this.deviceAddress = device.address();
        this.pipeline = pipeline;
        this.layout = layout;
        this.descriptorPool = descriptorPool;
        this.descriptorSetLayouts = descriptorSetLayouts;
        this.descriptorSets = descriptorSets;
        this.bindingsByNumber = Map.copyOf(bindingsByNumber);
    }

    public static VulkanLodPipeline create(GameVulkan.Snapshot game,
                                           ShaderCompiler.CompiledShader vertex,
                                           ShaderCompiler.CompiledShader fragment,
                                           State state, int frameSlots) {
        if (frameSlots <= 0) {
            throw new IllegalArgumentException("frameSlots must be positive");
        }
        if (vertex.trace().stage() != ShaderCompiler.Stage.VERTEX
                || fragment.trace().stage() != ShaderCompiler.Stage.FRAGMENT) {
            throw new IllegalArgumentException("LOD graphics pipeline requires vertex and fragment SPIR-V");
        }

        VkDevice device = game.device();
        long vertexModule = 0;
        long fragmentModule = 0;
        long[] setLayouts = new long[0];
        long pipelineLayout = 0;
        long descriptorPool = 0;
        long pipeline = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            vertexModule = createShaderModule(device, vertex.spirv(), stack, handle);
            fragmentModule = createShaderModule(device, fragment.spirv(), stack, handle);

            LayoutSpec spec = mergeDescriptors(vertex, fragment);
            setLayouts = createDescriptorLayouts(device, spec, stack, handle);
            pipelineLayout = createPipelineLayout(device, setLayouts, spec.pushConstantSize,
                    spec.pushConstantStageFlags, stack, handle);
            if (!spec.bindings.isEmpty()) {
                descriptorPool = createDescriptorPool(device, spec, frameSlots, stack, handle);
            }
            long[][] sets = allocateDescriptorSets(device, descriptorPool, setLayouts, frameSlots, stack);
            pipeline = createGraphicsPipeline(device, vertexModule, fragmentModule, pipelineLayout,
                    state, stack, handle);
            return new VulkanLodPipeline(device, pipeline, pipelineLayout, descriptorPool,
                    setLayouts, sets, spec.byNumber);
        } catch (RuntimeException | Error failure) {
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            for (long setLayout : setLayouts) {
                if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            }
            throw failure;
        } finally {
            if (vertexModule != 0) vkDestroyShaderModule(device, vertexModule, null);
            if (fragmentModule != 0) vkDestroyShaderModule(device, fragmentModule, null);
        }
    }

    /** Reflected set-0 binding numbers; every one must be written before a frame slot is drawn. */
    public Set<Integer> bindingNumbers() {
        return new TreeSet<>(bindingsByNumber.keySet());
    }

    public boolean hasBinding(int binding) {
        return bindingsByNumber.containsKey(binding);
    }

    public void writeBuffer(int frameSlot, int bindingNumber, long buffer, long offset, long range) {
        Binding binding = requireBinding(bindingNumber);
        if (binding.descriptorType != VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                && binding.descriptorType != VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            throw new IllegalArgumentException("Descriptor is not a buffer: binding " + bindingNumber);
        }
        if (buffer == 0 || offset < 0 || range <= 0) {
            throw new IllegalArgumentException("invalid Vulkan descriptor buffer range for binding " + bindingNumber);
        }
        long descriptorSet = descriptorSet(frameSlot, binding.set);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer).offset(offset).range(range);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default().dstSet(descriptorSet).dstBinding(binding.binding)
                    .descriptorCount(1).descriptorType(binding.descriptorType).pBufferInfo(info);
            vkUpdateDescriptorSets(device, write, null);
        }
    }

    public void writeCombinedImageSampler(int frameSlot, int bindingNumber, long sampler,
                                          long imageView, int imageLayout) {
        Binding binding = requireBinding(bindingNumber);
        if (binding.descriptorType != VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            throw new IllegalArgumentException("Descriptor is not a combined image sampler: binding " + bindingNumber);
        }
        if (imageView == 0 || sampler == 0) {
            throw new IllegalArgumentException("image view and sampler handles must be nonzero");
        }
        long descriptorSet = descriptorSet(frameSlot, binding.set);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(imageView).imageLayout(imageLayout);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default().dstSet(descriptorSet).dstBinding(binding.binding)
                    .descriptorCount(1).descriptorType(binding.descriptorType).pImageInfo(info);
            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /** Binds the pipeline, this slot's descriptor sets, and the dynamic viewport/scissor. */
    public void bind(VkCommandBuffer commandBuffer, int frameSlot, int x, int y, int width, int height) {
        ensureOpen();
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        if (descriptorSetLayouts.length > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer sets = stack.longs(descriptorSets[checkedFrame(frameSlot)]);
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, sets, null);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(x).y(y).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(x, y);
            scissor.get(0).extent().set(width, height);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (device.address() != deviceAddress) {
            throw new IllegalStateException("[JBP-LOD] Cannot destroy a V4 pipeline through a different VkDevice.");
        }
        closed = true;
        vkDestroyPipeline(device, pipeline, null);
        if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipelineLayout(device, layout, null);
        for (long setLayout : descriptorSetLayouts) {
            vkDestroyDescriptorSetLayout(device, setLayout, null);
        }
    }

    private Binding requireBinding(int binding) {
        ensureOpen();
        Binding found = bindingsByNumber.get(binding);
        if (found == null) {
            throw new IllegalArgumentException("Shader descriptor is not reflected: set 0 binding " + binding);
        }
        return found;
    }

    private long descriptorSet(int frameSlot, int set) {
        int checkedFrame = checkedFrame(frameSlot);
        if (set < 0 || set >= descriptorSets[checkedFrame].length) {
            throw new IllegalArgumentException("descriptor set is not allocated: " + set);
        }
        return descriptorSets[checkedFrame][set];
    }

    private int checkedFrame(int frameSlot) {
        ensureOpen();
        if (frameSlot < 0 || frameSlot >= descriptorSets.length) {
            throw new IllegalArgumentException("frame slot out of range: " + frameSlot);
        }
        return frameSlot;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("[JBP-LOD] Vulkan LOD pipeline is closed.");
        }
    }

    private static long createShaderModule(VkDevice device, byte[] spirv, MemoryStack stack, LongBuffer handle) {
        ByteBuffer code = stack.malloc(spirv.length).order(ByteOrder.LITTLE_ENDIAN);
        code.put(spirv).flip();
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(code);
        check(vkCreateShaderModule(device, info, null, handle), "vkCreateShaderModule");
        return handle.get(0);
    }

    private static LayoutSpec mergeDescriptors(ShaderCompiler.CompiledShader vertex,
                                               ShaderCompiler.CompiledShader fragment) {
        Map<Integer, Binding> byNumber = new LinkedHashMap<>();
        int pushConstantSize = Math.max(vertex.reflection().pushConstantSize(), fragment.reflection().pushConstantSize());
        int pushStageFlags = (vertex.reflection().pushConstantSize() > 0 ? VK_SHADER_STAGE_VERTEX_BIT : 0)
                | (fragment.reflection().pushConstantSize() > 0 ? VK_SHADER_STAGE_FRAGMENT_BIT : 0);
        addDescriptors(vertex, VK_SHADER_STAGE_VERTEX_BIT, byNumber);
        addDescriptors(fragment, VK_SHADER_STAGE_FRAGMENT_BIT, byNumber);
        return new LayoutSpec(byNumber.isEmpty() ? 0 : 1, List.copyOf(byNumber.values()), byNumber,
                pushConstantSize, pushStageFlags);
    }

    private static void addDescriptors(ShaderCompiler.CompiledShader shader, int stageFlags,
                                       Map<Integer, Binding> byNumber) {
        for (ShaderCompiler.Descriptor descriptor : shader.reflection().descriptors()) {
            if (descriptor.set() != 0 || descriptor.binding() < 0) {
                throw new IllegalArgumentException("LOD draw shaders must use explicit set-0 bindings: "
                        + descriptor.name());
            }
            int descriptorType = descriptorType(descriptor.kind());
            Binding current = byNumber.get(descriptor.binding());
            if (current == null) {
                byNumber.put(descriptor.binding(), new Binding(descriptor.name(), 0, descriptor.binding(),
                        descriptorType, stageFlags));
            } else {
                if (current.descriptorType != descriptorType) {
                    throw new IllegalArgumentException("Shader stages disagree on descriptor type at set=0 binding="
                            + descriptor.binding());
                }
                byNumber.put(descriptor.binding(), new Binding(current.name, 0, current.binding,
                        current.descriptorType, current.stageFlags | stageFlags));
            }
        }
    }

    private static int descriptorType(String kind) {
        return switch (kind) {
            case "uniform-buffer" -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case "storage-buffer" -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case "sampled-image" -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            default -> throw new IllegalArgumentException("Unsupported LOD draw descriptor kind: " + kind);
        };
    }

    private static long[] createDescriptorLayouts(VkDevice device, LayoutSpec spec,
                                                  MemoryStack stack, LongBuffer handle) {
        long[] layouts = new long[spec.setCount];
        try {
            for (int set = 0; set < spec.setCount; set++) {
                List<Binding> bindings = spec.bindings.stream()
                        .sorted(Comparator.comparingInt(Binding::binding)).toList();
                VkDescriptorSetLayoutBinding.Buffer layoutBindings =
                        VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
                for (int i = 0; i < bindings.size(); i++) {
                    Binding binding = bindings.get(i);
                    layoutBindings.get(i).binding(binding.binding).descriptorType(binding.descriptorType)
                            .descriptorCount(1).stageFlags(binding.stageFlags);
                }
                VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default().pBindings(layoutBindings);
                check(vkCreateDescriptorSetLayout(device, createInfo, null, handle), "vkCreateDescriptorSetLayout");
                layouts[set] = handle.get(0);
            }
            return layouts;
        } catch (RuntimeException | Error failure) {
            for (long setLayout : layouts) {
                if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            }
            throw failure;
        }
    }

    private static long createPipelineLayout(VkDevice device, long[] setLayouts, int pushConstantSize,
                                             int pushStageFlags, MemoryStack stack, LongBuffer handle) {
        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
        if (setLayouts.length > 0) {
            createInfo.pSetLayouts(stack.longs(setLayouts));
        }
        if (pushConstantSize > 0) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(pushStageFlags).offset(0).size(pushConstantSize);
            createInfo.pPushConstantRanges(range);
        }
        check(vkCreatePipelineLayout(device, createInfo, null, handle), "vkCreatePipelineLayout");
        return handle.get(0);
    }

    private static long createDescriptorPool(VkDevice device, LayoutSpec spec, int frameSlots,
                                             MemoryStack stack, LongBuffer handle) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Binding binding : spec.bindings) counts.merge(binding.descriptorType, frameSlots, Integer::sum);
        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
        int index = 0;
        for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
            poolSizes.get(index++).type(count.getKey()).descriptorCount(count.getValue());
        }
        VkDescriptorPoolCreateInfo createInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                .maxSets(frameSlots * spec.setCount).pPoolSizes(poolSizes);
        check(vkCreateDescriptorPool(device, createInfo, null, handle), "vkCreateDescriptorPool");
        return handle.get(0);
    }

    private static long[][] allocateDescriptorSets(VkDevice device, long descriptorPool, long[] setLayouts,
                                                   int frameSlots, MemoryStack stack) {
        long[][] result = new long[frameSlots][setLayouts.length];
        if (descriptorPool == 0 || setLayouts.length == 0) {
            return result;
        }
        LongBuffer layouts = stack.mallocLong(frameSlots * setLayouts.length);
        for (int frame = 0; frame < frameSlots; frame++) {
            for (long setLayout : setLayouts) {
                layouts.put(setLayout);
            }
        }
        layouts.flip();
        VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
        LongBuffer allocated = stack.mallocLong(frameSlots * setLayouts.length);
        check(vkAllocateDescriptorSets(device, allocateInfo, allocated), "vkAllocateDescriptorSets");
        for (int frame = 0; frame < frameSlots; frame++) {
            for (int set = 0; set < setLayouts.length; set++) {
                result[frame][set] = allocated.get(frame * setLayouts.length + set);
            }
        }
        return result;
    }

    private static long createGraphicsPipeline(VkDevice device, long vertexModule, long fragmentModule,
                                               long layout, State state, MemoryStack stack, LongBuffer handle) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));

        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default();
        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
        VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default().viewportCount(1).scissorCount(1);
        VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default().depthClampEnable(false).rasterizerDiscardEnable(false)
                .polygonMode(VK_POLYGON_MODE_FILL).cullMode(state.cullMode())
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(false).lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false)
                .alphaToCoverageEnable(false).alphaToOneEnable(false);
        VkStencilOpState stencilOp = VkStencilOpState.calloc(stack)
                .failOp(VK_STENCIL_OP_KEEP).passOp(state.stencil().passOp())
                .depthFailOp(VK_STENCIL_OP_KEEP).compareOp(state.stencil().compareOp())
                .compareMask(0xFF).writeMask(state.stencil().writeMask())
                .reference(state.stencil().reference());
        VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default().depthTestEnable(state.depthTest()).depthWriteEnable(state.depthWrite())
                .depthCompareOp(state.depthCompareOp()).depthBoundsTestEnable(false)
                .stencilTestEnable(state.stencil().test()).front(stencilOp).back(stencilOp)
                .minDepthBounds(0.0f).maxDepthBounds(1.0f);

        boolean hasColor = state.colorFormat() != VK_FORMAT_UNDEFINED;
        boolean blended = hasColor && state.blendMode() == BlendMode.ALPHA;
        VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default().logicOpEnable(false).logicOp(VK_LOGIC_OP_COPY);
        if (hasColor) {
            VkPipelineColorBlendAttachmentState.Buffer attachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .blendEnable(blended)
                    .srcColorBlendFactor(blended ? VK_BLEND_FACTOR_SRC_ALPHA : VK_BLEND_FACTOR_ONE)
                    .dstColorBlendFactor(blended ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(blended ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            colorBlend.pAttachments(attachment);
        }
        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

        VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType$Default().depthAttachmentFormat(state.depthFormat())
                .stencilAttachmentFormat(hasStencil(state.depthFormat()) ? state.depthFormat() : VK_FORMAT_UNDEFINED);
        if (hasColor) {
            IntBuffer colorFormats = stack.ints(state.colorFormat());
            rendering.colorAttachmentCount(1).pColorAttachmentFormats(colorFormats);
        }
        VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly).pViewportState(viewport).pRasterizationState(rasterization)
                .pMultisampleState(multisample).pDepthStencilState(depthStencil).pColorBlendState(colorBlend)
                .pDynamicState(dynamic).layout(layout).renderPass(VK_NULL_HANDLE).subpass(0);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, createInfo, null, handle),
                "vkCreateGraphicsPipelines");
        return handle.get(0);
    }

    /** True when the depth attachment format also carries a stencil aspect. */
    static boolean hasStencil(int depthFormat) {
        return depthFormat == VK_FORMAT_D24_UNORM_S8_UINT || depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT
                || depthFormat == VK_FORMAT_D16_UNORM_S8_UINT;
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }

    public enum BlendMode {
        OPAQUE,
        ALPHA
    }

    /**
     * Stencil test of one LOD pipeline. The compare mask is always 0xFF and the fail and depth-fail
     * ops are always KEEP.
     */
    public record Stencil(boolean test, int compareOp, int passOp, int reference, int writeMask) {
        /** Stencil test disabled. */
        public static final Stencil OFF = new Stencil(false, VK_COMPARE_OP_ALWAYS, VK_STENCIL_OP_KEEP, 0, 0);
        /** LOD draw gate, upstream GL_EQUAL 1: only stencil-1 (sky) pixels pass; stencil is not written. */
        public static final Stencil LOD_ON_SKY = new Stencil(true, VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, 1, 0);
        /** Seed pass: every pixel that survives the discard writes stencil 0 (upstream setup_stencil_depth). */
        public static final Stencil SEED_VANILLA = new Stencil(true, VK_COMPARE_OP_ALWAYS, VK_STENCIL_OP_REPLACE,
                0, 0xFF);
    }

    /**
     * Fixed-function state of one LOD pipeline. {@code colorFormat == VK_FORMAT_UNDEFINED} means
     * a depth-only scope (visibility raster / depth conversion).
     */
    public record State(int colorFormat, int depthFormat, boolean depthTest, boolean depthWrite,
                        int depthCompareOp, BlendMode blendMode, int cullMode, Stencil stencil) {
        public static State of(int colorFormat, int depthFormat, boolean depthTest, boolean depthWrite,
                               int depthCompareOp, BlendMode blendMode) {
            return new State(colorFormat, depthFormat, depthTest, depthWrite, depthCompareOp, blendMode,
                    VK_CULL_MODE_NONE, Stencil.OFF);
        }

        public State withStencil(Stencil value) {
            return new State(colorFormat, depthFormat, depthTest, depthWrite, depthCompareOp, blendMode,
                    cullMode, value);
        }
    }

    private record Binding(String name, int set, int binding, int descriptorType, int stageFlags) {
    }

    private record LayoutSpec(int setCount, List<Binding> bindings, Map<Integer, Binding> byNumber,
                              int pushConstantSize, int pushConstantStageFlags) {
    }
}
