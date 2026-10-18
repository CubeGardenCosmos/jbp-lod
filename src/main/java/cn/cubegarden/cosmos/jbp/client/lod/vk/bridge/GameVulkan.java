package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.FrontendGpuDeviceAccessor;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.VulkanDeviceAccessor;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Borrow-only view of the Vulkan device currently owned by Minecraft/RenderPearl. */
public final class GameVulkan {
    private static long frameId;
    private static final List<Runnable> SUBMISSION_LISTENERS = new ArrayList<>();

    /** Render-thread epoch, advanced only after the game's graphics submission boundary. */
    public static long frameId() {
        return frameId;
    }

    public static void addSubmissionListener(Runnable listener) {
        SUBMISSION_LISTENERS.add(listener);
    }

    public static void removeSubmissionListener(Runnable listener) {
        SUBMISSION_LISTENERS.remove(listener);
    }

    /** Called only by the locked backend submission mixin, after game graphics work is queued. */
    public static void afterGameSubmission() {
        frameId++;
        pollSubmissionRetirements();
    }

    /** Polls after the backend's existing final graphics queue drain, without a new frame epoch. */
    public static void pollSubmissionRetirements() {
        // Reverse traversal permits a fully retired ring to unregister itself.
        for (int i = SUBMISSION_LISTENERS.size() - 1; i >= 0; i--) {
            SUBMISSION_LISTENERS.get(i).run();
        }
    }

    private GameVulkan() {
    }

    /**
     * Returns the active RenderPearl Vulkan handles without creating or taking ownership of a device.
     * This is intentionally fail-fast: JBP-LOD has no non-Vulkan graphics path.
     */
    public static Snapshot current() {
        GpuDevice gpuDevice = RenderSystem.getDevice();
        if (!(gpuDevice instanceof FrontendGpuDevice frontendDevice)) {
            throw new IllegalStateException("[JBP-LOD] RenderSystem.getDevice() is not a RenderPearl FrontendGpuDevice: "
                    + className(gpuDevice));
        }
        if (!(frontendDevice instanceof FrontendGpuDeviceAccessor accessor)) {
            throw new IllegalStateException("[JBP-LOD] FrontendGpuDevice backend accessor mixin is not applied.");
        }

        GpuDeviceBackend backend = accessor.jbpLod$getBackend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            throw new IllegalStateException("[JBP-LOD] Active RenderPearl backend is not Vulkan: " + className(backend));
        }
        if (!(vulkanDevice instanceof VulkanDeviceAccessor vulkanAccessor)) {
            throw new IllegalStateException("[JBP-LOD] VulkanDevice feature accessor mixin is not applied.");
        }

        VulkanInstance gameInstance = vulkanDevice.instance();
        VkInstance vkInstance = gameInstance.vkInstance();
        VkDevice vkDevice = vulkanDevice.vkDevice();
        VkPhysicalDevice physicalDevice = vkDevice.getPhysicalDevice();
        VulkanQueue graphicsQueue = vulkanDevice.graphicsQueue();
        VulkanQueue computeQueue = vulkanDevice.computeQueue();
        VulkanQueue transferQueue = vulkanDevice.transferQueue();
        FeatureSet enabledFeatures = vulkanAccessor.jbpLod$getEnabledFeatures();
        DeviceInfo deviceInfo = vulkanDevice.getDeviceInfo();

        if (physicalDevice == null) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VkDevice does not expose its VkPhysicalDevice.");
        }

        Set<String> featureNames = enabledFeatures.features().stream()
                .map(VulkanFeature::name)
                .collect(Collectors.toUnmodifiableSet());

        return new Snapshot(
                gameInstance,
                vkInstance,
                physicalDevice,
                vkDevice,
                graphicsQueue,
                computeQueue,
                transferQueue,
                vulkanDevice.vma(),
                Set.copyOf(gameInstance.getEnabledExtensions()),
                Set.copyOf(enabledFeatures.extensions()),
                featureNames,
                Set.copyOf(deviceInfo.underlyingExtensions()),
                deviceInfo,
                gameInstance.debug().enabled()
        );
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    public record Snapshot(
            VulkanInstance gameInstance,
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VulkanQueue graphicsQueue,
            VulkanQueue computeQueue,
            VulkanQueue transferQueue,
            long vmaAllocator,
            Set<String> enabledInstanceExtensions,
            Set<String> enabledDeviceExtensions,
            Set<String> enabledFeatures,
            Set<String> reportedDeviceExtensions,
            DeviceInfo deviceInfo,
            boolean debugUtilsEnabled
    ) {
    }
}
