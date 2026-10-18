package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaInvalidateAllocation;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.util.vma.Vma.vmaMapMemory;
import static org.lwjgl.util.vma.Vma.vmaUnmapMemory;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/** Owns one VMA allocation on the game's existing Vulkan device. */
public final class GpuBuffer implements AutoCloseable {
    private final VkDevice device;
    private final long allocator;
    private final long buffer;
    private final long allocation;
    private final long capacity;
    private final int usage;
    private final boolean hostWritable;
    private long mappedAddress;
    private boolean closed;

    private GpuBuffer(VkDevice device, long allocator, long buffer, long allocation,
                      long capacity, int usage, boolean hostWritable, long mappedAddress) {
        this.device = device;
        this.allocator = allocator;
        this.buffer = buffer;
        this.allocation = allocation;
        this.capacity = capacity;
        this.usage = usage;
        this.hostWritable = hostWritable;
        this.mappedAddress = mappedAddress;
    }

    public static GpuBuffer allocate(GameVulkan.Snapshot game, long capacity, int usage, boolean hostWritable) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("GPU buffer capacity must be positive");
        }
        if (game.vmaAllocator() == 0) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VMA allocator handle is null.");
        }
        if (hostWritable && capacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mapped buffers are limited to ByteBuffer's int capacity");
        }
        long buffer = 0;
        long allocation = 0;
        long mappedAddress = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(capacity)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (hostWritable) {
                allocationInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            }
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            int result = vmaCreateBuffer(game.vmaAllocator(), bufferInfo, allocationInfo,
                    pBuffer, pAllocation, (VmaAllocationInfo) null);
            check(result, "vmaCreateBuffer");
            buffer = pBuffer.get(0);
            allocation = pAllocation.get(0);
            if (hostWritable) {
                PointerBuffer pMappedAddress = stack.mallocPointer(1);
                check(vmaMapMemory(game.vmaAllocator(), allocation, pMappedAddress), "vmaMapMemory");
                mappedAddress = pMappedAddress.get(0);
                if (mappedAddress == 0) {
                    throw new IllegalStateException("[JBP-LOD] VMA returned a null mapped pointer.");
                }
            }
            return new GpuBuffer(game.device(), game.vmaAllocator(), buffer, allocation,
                    capacity, usage, hostWritable, mappedAddress);
        } catch (RuntimeException | Error failure) {
            if (mappedAddress != 0 && allocation != 0) {
                vmaUnmapMemory(game.vmaAllocator(), allocation);
            }
            if (buffer != 0 && allocation != 0) {
                vmaDestroyBuffer(game.vmaAllocator(), buffer, allocation);
            }
            throw failure;
        }
    }

    public long handle() {
        ensureOpen();
        return buffer;
    }

    public long capacity() {
        ensureOpen();
        return capacity;
    }

    public int usage() {
        ensureOpen();
        return usage;
    }

    public ByteBuffer mappedBytes() {
        ensureOpen();
        if (!hostWritable) {
            throw new IllegalStateException("GPU buffer was not allocated for host writes");
        }
        return memByteBuffer(mappedAddress, Math.toIntExact(capacity));
    }

    public void flush(long offset, long length) {
        ensureOpen();
        if (!hostWritable) {
            throw new IllegalStateException("GPU buffer was not allocated for host writes");
        }
        checkRange(offset, length);
        vmaFlushAllocation(allocator, allocation, offset, length);
    }

    /** Makes device writes to a mapped range visible to host reads (no-op on coherent memory). */
    public void invalidate(long offset, long length) {
        ensureOpen();
        if (!hostWritable) {
            throw new IllegalStateException("GPU buffer was not allocated mapped");
        }
        checkRange(offset, length);
        vmaInvalidateAllocation(allocator, allocation, offset, length);
    }


    /**
     * Allocates a replacement and records an optional device-side prefix copy. The old buffer
     * remains owned by the caller and MUST be retained until all prior submissions retire.
     */
    public GpuBuffer resized(GameVulkan.Snapshot game, long newCapacity,
                             VkCommandBuffer commandBuffer, long preserveBytes) {
        ensureOpen();
        if (device.address() != game.device().address() || allocator != game.vmaAllocator()) {
            throw new IllegalArgumentException("resized buffer must use the same game VkDevice and VMA allocator");
        }
        if (newCapacity <= 0 || preserveBytes < 0 || preserveBytes > Math.min(capacity, newCapacity)) {
            throw new IllegalArgumentException("invalid resized capacity or preserved byte count");
        }
        GpuBuffer replacement = allocate(game, newCapacity, usage, hostWritable);
        if (preserveBytes == 0) {
            return replacement;
        }
        if (commandBuffer == null || (usage & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0
                || (usage & VK_BUFFER_USAGE_TRANSFER_DST_BIT) == 0) {
            replacement.close();
            throw new IllegalArgumentException("preserving resize requires a command buffer and transfer src/dst usage");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(preserveBytes);
            vkCmdCopyBuffer(commandBuffer, buffer, replacement.buffer, copy);
        }
        return replacement;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mappedAddress != 0) {
            vmaUnmapMemory(allocator, allocation);
            mappedAddress = 0;
        }
        vmaDestroyBuffer(allocator, buffer, allocation);
    }

    private void checkRange(long offset, long length) {
        if (offset < 0 || length < 0 || offset > capacity - length) {
            throw new IndexOutOfBoundsException("buffer range exceeds capacity");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("GPU buffer has been closed");
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
}
