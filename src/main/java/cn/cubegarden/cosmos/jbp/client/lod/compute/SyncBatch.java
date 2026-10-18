package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_UNIFORM_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

/**
 * One {@code vkCmdPipelineBarrier2} carrying several range-scoped Synchronization2 buffer barriers.
 * Every dependency is GPU-side (or host-to-GPU); nothing here waits on the CPU.
 */
final class SyncBatch {
    static final long COMPUTE = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    static final long VERTEX = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;
    static final long FRAGMENT = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
    static final long TRANSFER = VK_PIPELINE_STAGE_2_TRANSFER_BIT;
    static final long HOST = VK_PIPELINE_STAGE_2_HOST_BIT;
    static final long DRAW_INDIRECT = VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;

    static final long SSBO_READ = VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
    static final long SSBO_WRITE = VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
    static final long SSBO_RW = SSBO_READ | SSBO_WRITE;
    static final long UNIFORM_READ = VK_ACCESS_2_UNIFORM_READ_BIT;
    static final long INDIRECT_READ = VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
    static final long TRANSFER_WRITE = VK_ACCESS_2_TRANSFER_WRITE_BIT;
    static final long TRANSFER_RW = VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT;
    static final long HOST_WRITE = VK_ACCESS_2_HOST_WRITE_BIT;
    static final long HOST_READ = VK_ACCESS_2_HOST_READ_BIT;

    private record Entry(GpuBuffer buffer, long offset, long size,
                         long srcStage, long srcAccess, long dstStage, long dstAccess) {
    }

    private final List<Entry> entries = new ArrayList<>();

    SyncBatch add(GpuBuffer buffer, long offset, long size,
                  long srcStage, long srcAccess, long dstStage, long dstAccess) {
        if (offset < 0 || size <= 0 || offset > buffer.capacity() - size) {
            throw new IllegalArgumentException("barrier range is outside the GPU buffer");
        }
        entries.add(new Entry(buffer, offset, size, srcStage, srcAccess, dstStage, dstAccess));
        return this;
    }

    SyncBatch addWhole(GpuBuffer buffer, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        return add(buffer, 0, buffer.capacity(), srcStage, srcAccess, dstStage, dstAccess);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    void record(VkCommandBuffer commandBuffer) {
        if (entries.isEmpty()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier2.Buffer barriers = VkBufferMemoryBarrier2.calloc(entries.size(), stack);
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                barriers.get(i)
                        .sType$Default()
                        .srcStageMask(entry.srcStage)
                        .srcAccessMask(entry.srcAccess)
                        .dstStageMask(entry.dstStage)
                        .dstAccessMask(entry.dstAccess)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(entry.buffer.handle())
                        .offset(entry.offset)
                        .size(entry.size);
            }
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pBufferMemoryBarriers(barriers);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
        entries.clear();
    }
}
