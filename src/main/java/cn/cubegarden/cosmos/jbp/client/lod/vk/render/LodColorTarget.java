package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** A frame-slot-owned color attachment, sampled by the game's final composite. */
final class LodColorTarget implements AutoCloseable {
    private final VkDevice device;
    private final long allocator;
    private final long image;
    private final long allocation;
    private final long view;
    private boolean initialized;
    private boolean closed;

    LodColorTarget(GameVulkan.Snapshot game, int format, int width, int height) {
        device = game.device();
        allocator = game.vmaAllocator();
        long createdImage = 0, createdAllocation = 0, createdView = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            LongBuffer result = stack.mallocLong(1);
            PointerBuffer allocationResult = stack.mallocPointer(1);
            int status = vmaCreateImage(allocator, info, VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE), result, allocationResult, (VmaAllocationInfo) null);
            if (status != VK_SUCCESS) throw new IllegalStateException("LOD color image allocation failed: " + status);
            createdImage = result.get(0);
            createdAllocation = allocationResult.get(0);
            createdView = LodImageViews.create(device, createdImage, format, VK_IMAGE_ASPECT_COLOR_BIT, stack, result);
        } catch (RuntimeException | Error failure) {
            if (createdView != 0) vkDestroyImageView(device, createdView, null);
            if (createdImage != 0) vmaDestroyImage(allocator, createdImage, createdAllocation);
            throw failure;
        }
        image = createdImage;
        allocation = createdAllocation;
        view = createdView;
    }

    long view() { return view; }

    void prepare(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(initialized ? VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_2_NONE)
                    .srcAccessMask(initialized ? VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT : 0)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .oldLayout(initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL).image(image)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier2(commandBuffer, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        }
        initialized = true;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        vkDestroyImageView(device, view, null);
        vmaDestroyImage(allocator, image, allocation);
    }
}
