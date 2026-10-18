package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Renderer-owned D32 depth image that holds the game's previous-frame depth re-expressed in the
 * LOD projection's depth range. It feeds the HiZ pyramid and the occlusion-test raster, and always
 * stays in GENERAL layout (one layout for sampled, attachment-read and attachment-write use).
 */
final class LodDepthTarget implements AutoCloseable {
    static final int FORMAT = VK_FORMAT_D32_SFLOAT;

    private final VkDevice device;
    private final long allocator;
    private final long image;
    private final long allocation;
    private final long view;
    private final int width;
    private final int height;
    private boolean initialized;
    private boolean closed;

    private LodDepthTarget(VkDevice device, long allocator, long image, long allocation, long view,
                           int width, int height) {
        this.device = device;
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
    }

    static LodDepthTarget create(GameVulkan.Snapshot game, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("depth target extent must be positive");
        }
        if (game.vmaAllocator() == 0) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VMA allocator handle is null.");
        }
        VkDevice device = game.device();
        long image = 0;
        long allocation = 0;
        long view = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(FORMAT).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            check(vmaCreateImage(game.vmaAllocator(), imageInfo, allocationInfo, pImage, pAllocation,
                    (VmaAllocationInfo) null), "vmaCreateImage");
            image = pImage.get(0);
            allocation = pAllocation.get(0);
            LongBuffer pView = stack.mallocLong(1);
            view = LodImageViews.create(device, image, FORMAT, VK_IMAGE_ASPECT_DEPTH_BIT, stack, pView);
            return new LodDepthTarget(device, game.vmaAllocator(), image, allocation, view, width, height);
        } catch (RuntimeException | Error failure) {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (image != 0) vmaDestroyImage(game.vmaAllocator(), image, allocation);
            throw failure;
        }
    }

    long image() {
        return image;
    }

    long view() {
        return view;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Layout before the first recorded use: UNDEFINED once, GENERAL afterwards. */
    int layoutBeforeUse() {
        return initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    }

    void markUsed() {
        initialized = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        vkDestroyImageView(device, view, null);
        vmaDestroyImage(allocator, image, allocation);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
}
