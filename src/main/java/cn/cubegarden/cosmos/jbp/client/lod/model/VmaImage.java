package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/** VMA-backed 2D image plus one full-resource view on the game's device. */
final class VmaImage implements AutoCloseable {
    private final VkDevice device;
    private final long allocator;
    private final long image;
    private final long allocation;
    private final long view;
    private final int aspect;
    private boolean closed;

    private VmaImage(VkDevice device, long allocator, long image, long allocation, long view, int aspect) {
        this.device = device;
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.aspect = aspect;
    }

    static VmaImage create(GameVulkan.Snapshot game, int width, int height, int mipLevels,
                           int format, int usage, int aspect, String label) {
        if (game.vmaAllocator() == 0) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VMA allocator handle is null.");
        }
        long image = 0;
        long allocation = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(mipLevels).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            ModelVk.check(vmaCreateImage(game.vmaAllocator(), imageInfo, allocationInfo, pImage, pAllocation,
                    (VmaAllocationInfo) null), "vmaCreateImage(" + label + ")");
            image = pImage.get(0);
            allocation = pAllocation.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.components().r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY);
            viewInfo.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mipLevels)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            ModelVk.check(vkCreateImageView(game.device(), viewInfo, null, pView), "vkCreateImageView(" + label + ")");
            return new VmaImage(game.device(), game.vmaAllocator(), image, allocation, pView.get(0), aspect);
        } catch (RuntimeException | Error failure) {
            if (image != 0) {
                vmaDestroyImage(game.vmaAllocator(), image, allocation);
            }
            throw failure;
        }
    }

    long image() {
        return image;
    }

    long view() {
        return view;
    }

    int aspect() {
        return aspect;
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
}
