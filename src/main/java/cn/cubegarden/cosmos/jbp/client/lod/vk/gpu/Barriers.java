package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;

import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;

/** Range-scoped Vulkan Synchronization2 buffer dependencies used by the LOD compute graph. */
public final class Barriers {
    private Barriers() {
    }

    public static void hostWriteToCompute(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                          long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    public static void transferWriteToComputeRead(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                  long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    public static void transferWriteToComputeWrite(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                   long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }
    public static void transferWriteToComputeVertexRead(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                        long offset, long size) {
        record(commandBuffer, buffer, offset, size, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }


    public static void computeWriteToComputeReadWrite(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                      long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    public static void computeWriteToDispatchIndirect(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                      long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
    }

    public static void hostWriteToVertexRead(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                             long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_WRITE_BIT,
                VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    public static void hostWriteToGraphicsShaderRead(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                      long offset, long size) {
        record(commandBuffer, buffer, offset, size, VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_WRITE_BIT,
                VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    public static void computeWriteToDrawIndirect(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                  long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
    }

    public static void computeWriteToVertexRead(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                                                long offset, long size) {
        record(commandBuffer, buffer, offset, size,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    public static void record(VkCommandBuffer commandBuffer, GpuBuffer buffer,
                              long offset, long size, long sourceStage, long sourceAccess,
                              long destinationStage, long destinationAccess) {
        if (offset < 0 || size <= 0 || offset > buffer.capacity() - size) {
            throw new IllegalArgumentException("barrier range is outside the GPU buffer");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier2.Buffer bufferBarrier = VkBufferMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer.handle())
                    .offset(offset)
                    .size(size);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pBufferMemoryBarriers(bufferBarrier);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }
}
