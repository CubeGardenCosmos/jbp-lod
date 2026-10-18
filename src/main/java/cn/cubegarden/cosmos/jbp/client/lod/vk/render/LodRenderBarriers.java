package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;

/**
 * Synchronization2 barriers for the depth hand-off between the game's depth buffer, the LOD depth
 * target, the HiZ build, and the occlusion raster. All images stay in GENERAL, so these are memory
 * barriers (a combined depth/stencil game image cannot be barriered per-aspect without
 * separateDepthStencilLayouts); only the first use of the renderer-owned D32 target needs a transition.
 * Barriers recorded in one submission also scope over later submissions on the same queue, which is
 * how the write-after-read dependency on the game depth reaches the game's own command buffer.
 */
final class LodRenderBarriers {
    static final long DEPTH_TEST_STAGES = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
            | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    static final long DEPTH_ATTACHMENT_ACCESS = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT
            | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;

    private LodRenderBarriers() {
    }

    static void memory(VkCommandBuffer commandBuffer, long srcStages, long srcAccess,
                       long dstStages, long dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(srcStages).srcAccessMask(srcAccess)
                    .dstStageMask(dstStages).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier2KHR(commandBuffer, VkDependencyInfo.calloc(stack).sType$Default()
                    .pMemoryBarriers(barrier));
        }
    }

    /** Transitions an old-layout depth(/stencil) image to GENERAL ahead of its next attachment write. */
    static void depthImageToGeneral(VkCommandBuffer commandBuffer, long image, int oldLayout, int aspectMask,
                                    long srcStages, long srcAccess, long dstStages, long dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(srcStages).srcAccessMask(srcAccess)
                    .dstStageMask(dstStages).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            barrier.subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier2KHR(commandBuffer, VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(barrier));
        }
    }

    /** Game depth writes of earlier submissions -> sampled reads in this command buffer. */
    static void gameDepthWritesToSampledRead(VkCommandBuffer commandBuffer) {
        memory(commandBuffer, DEPTH_TEST_STAGES, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    /** Sampled reads here -> the game's later depth tests/writes (write-after-read; execution only matters). */
    static void sampledReadToGameDepthWrites(VkCommandBuffer commandBuffer) {
        memory(commandBuffer, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0L,
                DEPTH_TEST_STAGES, DEPTH_ATTACHMENT_ACCESS);
    }

    /** Anything that used the LOD depth in an earlier frame -> a new depth write. */
    static final long LOD_DEPTH_USER_STAGES = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | DEPTH_TEST_STAGES;
    static final long LOD_DEPTH_USER_ACCESS = VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | DEPTH_ATTACHMENT_ACCESS;

    /** Depth writes -> HiZ sampled reads and occlusion-raster depth tests. */
    static void lodDepthWritesToReaders(VkCommandBuffer commandBuffer) {
        memory(commandBuffer, DEPTH_TEST_STAGES, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                LOD_DEPTH_USER_STAGES, LOD_DEPTH_USER_ACCESS);
    }
}
