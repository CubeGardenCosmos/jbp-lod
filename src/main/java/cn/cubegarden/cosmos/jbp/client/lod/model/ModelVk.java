package cn.cubegarden.cosmos.jbp.client.lod.model;

import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;

/** Small Vulkan helpers shared by the model atlas and baker; render-thread only. */
final class ModelVk {
    private ModelVk() {
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }

    /** Resettable command pool on the game's graphics queue family. All use is render-thread only. */
    static long createCommandPool(GameVulkan.Snapshot game, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(game.graphicsQueue().queueFamilyIndex());
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateCommandPool(game.device(), info, null, handle), "vkCreateCommandPool(" + label + ")");
            return handle.get(0);
        }
    }

    static VkCommandBuffer allocateCommandBuffer(VkDevice device, long pool, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, info, handle), "vkAllocateCommandBuffers(" + label + ")");
            return new VkCommandBuffer(handle.get(0), device);
        }
    }

    static void beginOneTime(VkCommandBuffer command, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(command, begin), "vkBeginCommandBuffer(" + label + ")");
        }
    }

    static void imageBarrier(VkCommandBuffer command, VmaImage image, int baseMip, int mipCount,
                             int oldLayout, int newLayout, long srcStage, long srcAccess,
                             long dstStage, long dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(srcStage).srcAccessMask(srcAccess)
                    .dstStageMask(dstStage).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image.image());
            barrier.subresourceRange().aspectMask(image.aspect()).baseMipLevel(baseMip).levelCount(mipCount)
                    .baseArrayLayer(0).layerCount(1);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2KHR(command, dependency);
        }
    }

    static void requireOptimalFeatures(VkPhysicalDevice physicalDevice, int format, int features, String purpose) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, properties);
            int missing = features & ~properties.optimalTilingFeatures();
            if (missing != 0) {
                throw new IllegalStateException("[JBP-LOD] " + purpose + " requires Vulkan format " + format
                        + " optimal-tiling feature bits 0x" + Integer.toHexString(missing)
                        + " which the game device does not report");
            }
        }
    }
}
