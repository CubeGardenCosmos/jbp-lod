package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkGetFenceStatus;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;

/** Pollable same-graphics-queue completion point; never waits for a fence or device. */
public final class GpuCompletion implements AutoCloseable {
    private static final Object QUEUE_SUBMIT_LOCK = new Object();

    private final GameVulkan.Snapshot game;
    private long fence;
    private boolean closed;

    private GpuCompletion(GameVulkan.Snapshot game, long fence) {
        this.game = game;
        this.fence = fence;
    }

    /** Submits one command buffer to the borrowed game graphics queue and returns its fence token. */
    public static GpuCompletion submit(GameVulkan.Snapshot game, VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) {
            throw new NullPointerException("command buffer");
        }
        return submitInternal(game, commandBuffer);
    }

    /** Enqueues a fence-only marker after all earlier commands already submitted to this queue. */
    public static GpuCompletion submitMarker(GameVulkan.Snapshot game) {
        return submitInternal(game, null);
    }
    /** Submits without a fence; a later frame-slot marker provides the retirement fence. */
    static void submitUntracked(GameVulkan.Snapshot game, VkCommandBuffer commandBuffer) {
        if (game == null || commandBuffer == null) {
            throw new NullPointerException("game snapshot and command buffer are required");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            int result;
            synchronized (QUEUE_SUBMIT_LOCK) {
                result = vkQueueSubmit(game.graphicsQueue().vkQueue(), submit, 0);
            }
            check(result, "vkQueueSubmit");
        }
    }


    long gameDeviceAddress() {
        return game.device().address();
    }

    public boolean isComplete() {
        ensureOpen();
        int result = vkGetFenceStatus(game.device(), fence);
        if (result == VK_SUCCESS) {
            return true;
        }
        if (result == VK_NOT_READY) {
            return false;
        }
        throw new IllegalStateException("[JBP-LOD] vkGetFenceStatus failed with VkResult " + result);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!isComplete()) {
            throw new IllegalStateException("cannot destroy an in-flight Vulkan completion fence");
        }
        closed = true;
        vkDestroyFence(game.device(), fence, null);
        fence = 0;
    }

    private static GpuCompletion submitInternal(GameVulkan.Snapshot game, VkCommandBuffer commandBuffer) {
        if (game == null) {
            throw new NullPointerException("game Vulkan snapshot");
        }
        long fence;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            var pFence = stack.mallocLong(1);
            check(vkCreateFence(game.device(), fenceInfo, null, pFence), "vkCreateFence");
            fence = pFence.get(0);
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default();
            if (commandBuffer != null) {
                submit.pCommandBuffers(stack.pointers(commandBuffer.address()));
            }
            int result;
            synchronized (QUEUE_SUBMIT_LOCK) {
                result = vkQueueSubmit(game.graphicsQueue().vkQueue(), submit, fence);
            }
            if (result != VK_SUCCESS) {
                vkDestroyFence(game.device(), fence, null);
                check(result, "vkQueueSubmit");
            }
        }
        return new GpuCompletion(game, fence);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("GPU completion token is closed");
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
}
