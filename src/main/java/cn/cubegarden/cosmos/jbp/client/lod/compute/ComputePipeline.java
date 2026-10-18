package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdDispatchIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

/** One compute pipeline with an immutable layout and per-frame-in-flight descriptor sets. */
public final class ComputePipeline implements AutoCloseable {
    private final GameVulkan.Snapshot game;
    private final long pipeline;
    private final long pipelineLayout;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final int framesInFlight;
    private final int descriptorSetsPerFrame;
    private final int pushConstantBytes;
    private final Map<Integer, Integer> descriptorTypes;
    private final ShaderCompiler.CompilationTrace trace;
    private boolean closed;

    public ComputePipeline(GameVulkan.Snapshot game, ShaderCompiler compiler, String shaderPath,
                           Map<String, String> defines, List<Binding> bindings,
                           int framesInFlight, int pushConstantBytes) {
        this(game, compiler, shaderPath, defines, bindings, framesInFlight, 1, pushConstantBytes);
    }

    /** Allocates extra descriptor sets per frame for recorded passes with different queue bindings. */
    public ComputePipeline(GameVulkan.Snapshot game, ShaderCompiler compiler, String shaderPath,
                           Map<String, String> defines, List<Binding> bindings,
                           int framesInFlight, int descriptorSetsPerFrame, int pushConstantBytes) {
        this.game = Objects.requireNonNull(game, "game");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(shaderPath, "shaderPath");
        if (framesInFlight <= 0 || descriptorSetsPerFrame <= 0
                || pushConstantBytes < 0 || (pushConstantBytes & 3) != 0) {
            throw new IllegalArgumentException("invalid descriptor frame count or push-constant size");
        }
        this.framesInFlight = framesInFlight;
        this.descriptorSetsPerFrame = descriptorSetsPerFrame;
        int descriptorSetCount = Math.multiplyExact(framesInFlight, descriptorSetsPerFrame);
        ShaderCompiler.CompiledShader compiled = compiler.compile(shaderPath, defines);
        this.trace = compiled.trace();
        this.pushConstantBytes = pushConstantBytes;
        Map<Integer, Integer> types = new HashMap<>();
        for (Binding binding : bindings) {
            if (types.put(binding.binding(), binding.descriptorType()) != null) {
                throw new IllegalArgumentException("duplicate descriptor binding " + binding.binding());
            }
        }
        this.descriptorTypes = Map.copyOf(types);

        long createdSetLayout = 0;
        long createdPipelineLayout = 0;
        long createdPool = 0;
        long createdPipeline = 0;
        long[] createdSets = new long[descriptorSetCount];
        long shaderModule = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                Binding binding = bindings.get(i);
                layoutBindings.get(i).binding(binding.binding())
                        .descriptorType(binding.descriptorType())
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(layoutBindings);
            var pSetLayout = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(game.device(), setInfo, null, pSetLayout), "vkCreateDescriptorSetLayout");
            createdSetLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(createdSetLayout));
            if (pushConstantBytes > 0) {
                VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushConstantBytes);
                pipelineLayoutInfo.pPushConstantRanges(ranges);
            }
            var pPipelineLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(game.device(), pipelineLayoutInfo, null, pPipelineLayout), "vkCreatePipelineLayout");
            createdPipelineLayout = pPipelineLayout.get(0);

            Map<Integer, Integer> typeCounts = new HashMap<>();
            for (int descriptorType : types.values()) {
                typeCounts.merge(descriptorType, descriptorSetCount, Integer::sum);
            }
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(typeCounts.size(), stack);
            int poolIndex = 0;
            int descriptorCapacity = 0;
            for (Map.Entry<Integer, Integer> entry : typeCounts.entrySet()) {
                poolSizes.get(poolIndex++).type(entry.getKey()).descriptorCount(entry.getValue());
                descriptorCapacity += entry.getValue();
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(descriptorSetCount).pPoolSizes(poolSizes);
            var pPool = stack.mallocLong(1);
            check(vkCreateDescriptorPool(game.device(), poolInfo, null, pPool), "vkCreateDescriptorPool");
            createdPool = pPool.get(0);
            if (descriptorCapacity != 0) {
                LongBuffer pLayouts = stack.mallocLong(descriptorSetCount);
                for (int i = 0; i < descriptorSetCount; i++) {
                    pLayouts.put(i, createdSetLayout);
                }
                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default().descriptorPool(createdPool).pSetLayouts(pLayouts);
                LongBuffer pSets = stack.mallocLong(descriptorSetCount);
                check(vkAllocateDescriptorSets(game.device(), allocateInfo, pSets), "vkAllocateDescriptorSets");
                for (int i = 0; i < descriptorSetCount; i++) {
                    createdSets[i] = pSets.get(i);
                }
            }

            ByteBuffer spirv = stack.malloc(compiled.spirv().length);
            spirv.put(compiled.spirv()).flip();
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(spirv);
            var pShader = stack.mallocLong(1);
            check(vkCreateShaderModule(game.device(), shaderInfo, null, pShader), "vkCreateShaderModule");
            shaderModule = pShader.get(0);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer computeInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default().stage(stage).layout(createdPipelineLayout);
            var pPipeline = stack.mallocLong(1);
            check(vkCreateComputePipelines(game.device(), 0, computeInfo, null, pPipeline), "vkCreateComputePipelines");
            createdPipeline = pPipeline.get(0);
        } catch (RuntimeException | Error failure) {
            if (createdPipeline != 0) vkDestroyPipeline(game.device(), createdPipeline, null);
            if (shaderModule != 0) vkDestroyShaderModule(game.device(), shaderModule, null);
            if (createdPool != 0) vkDestroyDescriptorPool(game.device(), createdPool, null);
            if (createdPipelineLayout != 0) vkDestroyPipelineLayout(game.device(), createdPipelineLayout, null);
            if (createdSetLayout != 0) vkDestroyDescriptorSetLayout(game.device(), createdSetLayout, null);
            throw failure;
        }
        pipeline = createdPipeline;
        pipelineLayout = createdPipelineLayout;
        descriptorSetLayout = createdSetLayout;
        descriptorPool = createdPool;
        descriptorSets = createdSets;
        vkDestroyShaderModule(game.device(), shaderModule, null);
    }

    public ShaderCompiler.CompilationTrace trace() {
        return trace;
    }

    public int framesInFlight() {
        return framesInFlight;
    }

    public int descriptorSetIndex(int frameSlot, int subpass) {
        if (frameSlot < 0 || frameSlot >= framesInFlight
                || subpass < 0 || subpass >= descriptorSetsPerFrame) {
            throw new IndexOutOfBoundsException("invalid frame descriptor set coordinates");
        }
        return frameSlot * descriptorSetsPerFrame + subpass;
    }

    public void setBuffer(int frameSlot, int binding, GpuBuffer buffer, long offset, long range) {
        ensureOpen();
        requireBinding(frameSlot, binding, org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        if (offset < 0 || range <= 0 || offset > buffer.capacity() - range) {
            throw new IndexOutOfBoundsException("descriptor buffer range is outside its allocation");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.handle()).offset(offset).range(range);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default().dstSet(descriptorSets[frameSlot]).dstBinding(binding)
                    .descriptorCount(1).descriptorType(descriptorTypes.get(binding)).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(game.device(), write, null);
        }
    }

    public void setCombinedImageSampler(int frameSlot, int binding, long sampler, long imageView, int imageLayout) {
        ensureOpen();
        requireBinding(frameSlot, binding, org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(imageView).imageLayout(imageLayout);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default().dstSet(descriptorSets[frameSlot]).dstBinding(binding)
                    .descriptorCount(1).descriptorType(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(game.device(), write, null);
        }
    }

    /** Writes a storage-image descriptor (binding must be declared VK_DESCRIPTOR_TYPE_STORAGE_IMAGE). */
    public void setStorageImage(int setIndex, int binding, long imageView, int imageLayout) {
        ensureOpen();
        requireBinding(setIndex, binding, org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(0).imageView(imageView).imageLayout(imageLayout);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default().dstSet(descriptorSets[setIndex]).dstBinding(binding)
                    .descriptorCount(1).descriptorType(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(game.device(), write, null);
        }
    }

    /** Process-wide count of recorded {@code vkCmdDispatch*} calls; read for the first-frame diagnostic log. */
    private static final java.util.concurrent.atomic.AtomicLong RECORDED_DISPATCHES = new java.util.concurrent.atomic.AtomicLong();

    public static long recordedDispatches() {
        return RECORDED_DISPATCHES.get();
    }

    public void dispatch(VkCommandBuffer commandBuffer, int frameSlot, int groupCountX, int groupCountY,
                         int groupCountZ, ByteBuffer pushConstants) {
        bind(commandBuffer, frameSlot, pushConstants);
        vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ);
        RECORDED_DISPATCHES.incrementAndGet();
    }

    public void dispatchIndirect(VkCommandBuffer commandBuffer, int frameSlot, GpuBuffer indirectBuffer,
                                 long offset, ByteBuffer pushConstants) {
        if (offset < 0 || offset > indirectBuffer.capacity() - 12) {
            throw new IndexOutOfBoundsException("indirect dispatch arguments exceed buffer capacity");
        }
        bind(commandBuffer, frameSlot, pushConstants);
        vkCmdDispatchIndirect(commandBuffer, indirectBuffer.handle(), offset);
        RECORDED_DISPATCHES.incrementAndGet();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        vkDestroyPipeline(game.device(), pipeline, null);
        vkDestroyDescriptorPool(game.device(), descriptorPool, null);
        vkDestroyPipelineLayout(game.device(), pipelineLayout, null);
        vkDestroyDescriptorSetLayout(game.device(), descriptorSetLayout, null);
    }

    private void bind(VkCommandBuffer commandBuffer, int frameSlot, ByteBuffer pushConstants) {
        ensureOpen();
        if (frameSlot < 0 || frameSlot >= descriptorSets.length) {
            throw new IndexOutOfBoundsException("frame descriptor set index " + frameSlot);
        }
        vkCmdBindPipeline(commandBuffer, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        if (descriptorSets[frameSlot] != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(commandBuffer, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipelineLayout, 0, stack.longs(descriptorSets[frameSlot]), null);
            }
        }
        if (pushConstantBytes > 0) {
            if (pushConstants == null || pushConstants.remaining() != pushConstantBytes) {
                throw new IllegalArgumentException("pipeline requires exactly " + pushConstantBytes + " push-constant bytes");
            }
            vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);
        } else if (pushConstants != null && pushConstants.remaining() != 0) {
            throw new IllegalArgumentException("pipeline has no push-constant range");
        }
    }

    private void requireBinding(int frameSlot, int binding, int... acceptedTypes) {
        if (frameSlot < 0 || frameSlot >= descriptorSets.length) {
            throw new IndexOutOfBoundsException("frame descriptor set index " + frameSlot);
        }
        Integer actual = descriptorTypes.get(binding);
        if (actual == null) {
            throw new IllegalArgumentException("descriptor binding is not in pipeline layout: " + binding);
        }
        for (int accepted : acceptedTypes) {
            if (actual == accepted) return;
        }
        throw new IllegalArgumentException("descriptor binding " + binding + " has incompatible descriptor type " + actual);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("compute pipeline is closed");
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }

    public record Binding(int binding, int descriptorType) {
        public Binding {
            if (binding < 0) throw new IllegalArgumentException("descriptor binding must be non-negative");
        }
    }
}
