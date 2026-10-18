package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaInvalidateAllocation;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;

/** Persistently mapped host-random-access VMA buffer used as a transfer destination for CPU readback. */
final class ReadbackBuffer implements AutoCloseable {
    private final long allocator;
    private final long buffer;
    private final long allocation;
    private final int size;
    private final ByteBuffer mapped;
    private boolean closed;

    private ReadbackBuffer(long allocator, long buffer, long allocation, int size, ByteBuffer mapped) {
        this.allocator = allocator;
        this.buffer = buffer;
        this.allocation = allocation;
        this.size = size;
        this.mapped = mapped;
    }

    static ReadbackBuffer allocate(GameVulkan.Snapshot game, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("readback buffer size must be positive");
        }
        if (game.vmaAllocator() == 0) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VMA allocator handle is null.");
        }
        long buffer = 0;
        long allocation = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            ModelVk.check(vmaCreateBuffer(game.vmaAllocator(), bufferInfo, allocationInfo, pBuffer, pAllocation, info),
                    "vmaCreateBuffer(model readback)");
            buffer = pBuffer.get(0);
            allocation = pAllocation.get(0);
            long address = info.pMappedData();
            if (address == 0) {
                throw new IllegalStateException("[JBP-LOD] VMA returned a null mapped pointer for the model readback buffer.");
            }
            ByteBuffer mapped = memByteBuffer(address, size).order(ByteOrder.LITTLE_ENDIAN);
            return new ReadbackBuffer(game.vmaAllocator(), buffer, allocation, size, mapped);
        } catch (RuntimeException | Error failure) {
            if (buffer != 0) {
                vmaDestroyBuffer(game.vmaAllocator(), buffer, allocation);
            }
            throw failure;
        }
    }

    long handle() {
        return buffer;
    }

    int size() {
        return size;
    }

    /** Invalidates the host cache for the whole buffer and returns the little-endian mapped view. */
    ByteBuffer read() {
        if (closed) {
            throw new IllegalStateException("readback buffer is closed");
        }
        vmaInvalidateAllocation(allocator, allocation, 0, size);
        return mapped;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        vmaDestroyBuffer(allocator, buffer, allocation);
    }
}
