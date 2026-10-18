package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** Single-aspect 2D image views created on the game device. */
final class LodImageViews {
    private LodImageViews() {
    }

    static long create(VkDevice device, long image, int format, int aspectMask,
                       MemoryStack stack, LongBuffer handle) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
        info.components().r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY);
        info.subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        int result = vkCreateImageView(device, info, null, handle);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] vkCreateImageView failed with VkResult " + result);
        }
        return handle.get(0);
    }

    /** Creates a depth-aspect-only sampled view of a borrowed image and returns an owner that destroys it. */
    static Owned borrowDepthView(VkDevice device, long image, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long view = create(device, image, format, VK_IMAGE_ASPECT_DEPTH_BIT, stack, stack.mallocLong(1));
            return new Owned(device, view);
        }
    }

    /** One image view destroyed after the frame slot that used it retires. */
    record Owned(VkDevice device, long view) implements AutoCloseable {
        @Override
        public void close() {
            vkDestroyImageView(device, view, null);
        }
    }
}
