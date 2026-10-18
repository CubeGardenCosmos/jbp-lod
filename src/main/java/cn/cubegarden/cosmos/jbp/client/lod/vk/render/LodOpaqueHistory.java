package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Queue-ordered previous-frame opaque commands and their matching section positions. */
final class LodOpaqueHistory implements AutoCloseable {
    final GpuBuffer commands;
    final GpuBuffer count;
    final GpuBuffer positions;
    boolean valid;

    LodOpaqueHistory(GameVulkan.Snapshot game) {
        int usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        commands = GpuBuffer.allocate(game, (long) LodFrameBuffers.OPAQUE_COMMAND_CAPACITY * 20,
                usage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, false);
        GpuBuffer createdCount = null;
        try {
            createdCount = GpuBuffer.allocate(game, 4, usage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, false);
            positions = GpuBuffer.allocate(game, (long) LodFrameBuffers.DEFAULT_SECTION_CAPACITY * 8,
                    usage | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            count = createdCount;
        } catch (RuntimeException | Error failure) {
            if (createdCount != null) createdCount.close();
            commands.close();
            throw failure;
        }
    }

    void capture(VkCommandBuffer cmd, LodFrameBuffers slot) {
        LodRenderBarriers.memory(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT);
        copy(cmd, slot.drawCommands(), 0, commands, commands.capacity());
        copy(cmd, slot.drawControl(), LodFrameBuffers.drawCountByteOffset(LodFrameBuffers.DrawRegion.OPAQUE), count, 4);
        copy(cmd, slot.positionScratch(), 0, positions, slot.positionScratch().capacity());
        LodRenderBarriers.memory(cmd, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        valid = true;
    }

    private static void copy(VkCommandBuffer cmd, GpuBuffer source, long offset, GpuBuffer target, long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdCopyBuffer(cmd, source.handle(), target.handle(), VkBufferCopy.calloc(1, stack)
                    .srcOffset(offset).dstOffset(0).size(bytes));
        }
    }

    @Override public void close() {
        positions.close();
        count.close();
        commands.close();
    }
}
