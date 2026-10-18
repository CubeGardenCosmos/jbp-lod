package cn.cubegarden.cosmos.jbp.client.lod.vk.caps;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_BASIC_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_CLUSTERED_BIT;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/** Immutable, per-device snapshot of usable core and optional Vulkan capabilities. */
public final class DeviceCaps {
    private static final int[] RELEVANT_FORMATS = {
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_B8G8R8A8_SRGB,
            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_FORMAT_R8_UNORM,
            VK_FORMAT_R32_UINT,
            VK_FORMAT_R32_SFLOAT,
            VK_FORMAT_D16_UNORM,
            VK_FORMAT_D32_SFLOAT,
            VK_FORMAT_D24_UNORM_S8_UINT,
            VK_FORMAT_D32_SFLOAT_S8_UINT
    };

    private final org.lwjgl.vulkan.VkPhysicalDevice physicalDevice;
    private final String deviceName;
    private final int apiVersion;
    private final int vendorId;
    private final int driverVersion;
    private final int graphicsQueueFamily;
    private final int computeQueueFamily;
    private final int transferQueueFamily;
    private final boolean drawIndirectFirstInstanceSupported;
    private final boolean drawIndirectFirstInstance;
    private final boolean multiDrawIndirectSupported;
    private final boolean multiDrawIndirect;
    private final boolean drawIndirectCountSupported;
    private final boolean drawIndirectCount;
    private final boolean shaderInt64Supported;
    private final boolean shaderInt64;
    private final boolean subgroupArithmetic;
    private final boolean subgroupClustered;
    private final int subgroupSize;
    private final int subgroupSupportedOperations;
    private final int subgroupSupportedStages;
    private final long maxStorageBufferRange;
    private final long maxUniformBufferRange;
    private final int maxPushConstantsSize;
    private final int maxDrawIndirectCount;
    private final int maxComputeWorkGroupInvocations;
    private final int maxComputeWorkGroupSizeX;
    private final int maxComputeWorkGroupSizeY;
    private final int maxComputeWorkGroupSizeZ;
    private final int maxComputeWorkGroupCountX;
    private final int maxComputeWorkGroupCountY;
    private final int maxComputeWorkGroupCountZ;
    private final Map<Integer, Integer> optimalFormatFeatures;

    private DeviceCaps(org.lwjgl.vulkan.VkPhysicalDevice physicalDevice,
                       String deviceName, int apiVersion, int vendorId, int driverVersion,
                       int graphicsQueueFamily, int computeQueueFamily, int transferQueueFamily,
                       boolean drawIndirectFirstInstanceSupported, boolean drawIndirectFirstInstance,
                       boolean multiDrawIndirectSupported, boolean multiDrawIndirect,
                       boolean drawIndirectCountSupported, boolean drawIndirectCount,
                       boolean shaderInt64Supported, boolean shaderInt64,
                       boolean subgroupArithmetic, boolean subgroupClustered, int subgroupSize, int subgroupSupportedOperations,
                       int subgroupSupportedStages, long maxStorageBufferRange, long maxUniformBufferRange,
                       int maxPushConstantsSize, int maxDrawIndirectCount,
                       int maxComputeWorkGroupInvocations, int maxComputeWorkGroupSizeX,
                       int maxComputeWorkGroupSizeY, int maxComputeWorkGroupSizeZ,
                       int maxComputeWorkGroupCountX, int maxComputeWorkGroupCountY,
                       int maxComputeWorkGroupCountZ, Map<Integer, Integer> optimalFormatFeatures) {
        this.physicalDevice = physicalDevice;
        this.deviceName = deviceName;
        this.apiVersion = apiVersion;
        this.vendorId = vendorId;
        this.driverVersion = driverVersion;
        this.graphicsQueueFamily = graphicsQueueFamily;
        this.computeQueueFamily = computeQueueFamily;
        this.drawIndirectFirstInstanceSupported = drawIndirectFirstInstanceSupported;
        this.drawIndirectFirstInstance = drawIndirectFirstInstance;
        this.multiDrawIndirectSupported = multiDrawIndirectSupported;
        this.multiDrawIndirect = multiDrawIndirect;
        this.transferQueueFamily = transferQueueFamily;
        this.drawIndirectCountSupported = drawIndirectCountSupported;
        this.drawIndirectCount = drawIndirectCount;
        this.shaderInt64Supported = shaderInt64Supported;
        this.shaderInt64 = shaderInt64;
        this.subgroupArithmetic = subgroupArithmetic;
        this.subgroupClustered = subgroupClustered;
        this.subgroupSize = subgroupSize;
        this.subgroupSupportedOperations = subgroupSupportedOperations;
        this.subgroupSupportedStages = subgroupSupportedStages;
        this.maxStorageBufferRange = maxStorageBufferRange;
        this.maxUniformBufferRange = maxUniformBufferRange;
        this.maxPushConstantsSize = maxPushConstantsSize;
        this.maxDrawIndirectCount = maxDrawIndirectCount;
        this.maxComputeWorkGroupInvocations = maxComputeWorkGroupInvocations;
        this.maxComputeWorkGroupSizeX = maxComputeWorkGroupSizeX;
        this.maxComputeWorkGroupSizeY = maxComputeWorkGroupSizeY;
        this.maxComputeWorkGroupSizeZ = maxComputeWorkGroupSizeZ;
        this.maxComputeWorkGroupCountX = maxComputeWorkGroupCountX;
        this.maxComputeWorkGroupCountY = maxComputeWorkGroupCountY;
        this.maxComputeWorkGroupCountZ = maxComputeWorkGroupCountZ;
        this.optimalFormatFeatures = Map.copyOf(optimalFormatFeatures);
    }

    /** Queries physical capabilities and intersects them with features enabled on the borrowed game VkDevice. */
    public static DeviceCaps inspect(GameVulkan.Snapshot game) {
        Objects.requireNonNull(game, "game");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(vulkan12.address());
            vkGetPhysicalDeviceFeatures2(game.physicalDevice(), features);

            VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(subgroup.address());
            vkGetPhysicalDeviceProperties2(game.physicalDevice(), properties2);
            var properties = properties2.properties();
            var limits = properties.limits();

            boolean drawIndirectFirstInstanceSupported = features.features().drawIndirectFirstInstance();
            boolean drawIndirectFirstInstance = drawIndirectFirstInstanceSupported
                    && enabled(game.enabledFeatures(), "drawIndirectFirstInstance");
            boolean multiDrawIndirectSupported = features.features().multiDrawIndirect();
            boolean multiDrawIndirect = multiDrawIndirectSupported
                    && enabled(game.enabledFeatures(), "multiDrawIndirect");
            boolean shaderInt64Supported = features.features().shaderInt64();
            boolean shaderInt64 = shaderInt64Supported && enabled(game.enabledFeatures(), "shaderInt64");
            boolean drawIndirectCountSupported = apiVersionAtLeast(properties.apiVersion(), 1, 2)
                    && vulkan12.drawIndirectCount();
            boolean drawIndirectCount = drawIndirectCountSupported
                    && enabled(game.enabledFeatures(), "drawIndirectCount");
            boolean subgroupBasicSupported = (subgroup.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0
                    && (subgroup.supportedOperations() & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0;
            boolean subgroupArithmetic = subgroupBasicSupported
                    && (subgroup.supportedOperations() & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;
            boolean subgroupClustered = subgroupBasicSupported
                    && (subgroup.supportedOperations() & VK_SUBGROUP_FEATURE_CLUSTERED_BIT) != 0;

            Map<Integer, Integer> formats = new LinkedHashMap<>();
            VkFormatProperties formatProperties = VkFormatProperties.calloc(stack);
            for (int format : RELEVANT_FORMATS) {
                vkGetPhysicalDeviceFormatProperties(game.physicalDevice(), format, formatProperties);
                formats.put(format, formatProperties.optimalTilingFeatures());
            }

            return new DeviceCaps(game.physicalDevice(), properties.deviceNameString(), properties.apiVersion(),
                    properties.vendorID(), properties.driverVersion(), family(game.graphicsQueue()),
                    family(game.computeQueue()), family(game.transferQueue()),
                    drawIndirectFirstInstanceSupported, drawIndirectFirstInstance,
                    multiDrawIndirectSupported, multiDrawIndirect,
                    drawIndirectCountSupported, drawIndirectCount, shaderInt64Supported, shaderInt64,
                    subgroupArithmetic, subgroupClustered, subgroup.subgroupSize(),
                    subgroup.supportedOperations(), subgroup.supportedStages(),
                    Integer.toUnsignedLong(limits.maxStorageBufferRange()),
                    Integer.toUnsignedLong(limits.maxUniformBufferRange()),
                    limits.maxPushConstantsSize(), limits.maxDrawIndirectCount(),
                    limits.maxComputeWorkGroupInvocations(),
                    limits.maxComputeWorkGroupSize(0), limits.maxComputeWorkGroupSize(1),
                    limits.maxComputeWorkGroupSize(2), limits.maxComputeWorkGroupCount(0),
                    limits.maxComputeWorkGroupCount(1), limits.maxComputeWorkGroupCount(2), formats);
        }
    }

    private static int family(com.mojang.renderpearl.backend.vulkan.VulkanQueue queue) {
        return queue == null ? VK_QUEUE_FAMILY_IGNORED : queue.queueFamilyIndex();
    }

    private static boolean enabled(Set<String> enabledFeatures, String featureName) {
        String expected = normalize(featureName);
        return enabledFeatures.stream().map(DeviceCaps::normalize)
                .anyMatch(value -> value.equals(expected) || value.endsWith(expected));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean apiVersionAtLeast(int version, int major, int minor) {
        int versionMajor = version >>> 22;
        int versionMinor = (version >>> 12) & 0x3ff;
        return versionMajor > major || (versionMajor == major && versionMinor >= minor);
    }

    /** Lists missing mandatory baseline requirements; optional fast paths are not included. */
    public List<String> missingCoreCapabilities() {
        List<String> missing = new ArrayList<>();
        if (!apiVersionAtLeast(apiVersion, 1, 3)) {
            missing.add("Vulkan 1.3 (device reports " + apiVersionString(apiVersion) + ")");
        }
        if (graphicsQueueFamily == VK_QUEUE_FAMILY_IGNORED) {
            missing.add("game graphics queue family");
        }
        if (computeQueueFamily == VK_QUEUE_FAMILY_IGNORED) {
            missing.add("game compute queue family");
        }
        if (!drawIndirectFirstInstance) {
            missing.add("drawIndirectFirstInstance (supported=" + drawIndirectFirstInstanceSupported
                    + ", enabled=" + drawIndirectFirstInstance
                    + "; required for nonzero Vulkan indirect firstInstance)");
        }
        int hizFormatRequiredFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
        int hizFormatFeatures = formatFeatures(VK_FORMAT_R32_SFLOAT);
        if ((hizFormatFeatures & hizFormatRequiredFeatures) != hizFormatRequiredFeatures) {
            missing.add("VK_FORMAT_R32_SFLOAT sampled/storage/color-attachment optimal-tiling support"
                    + " (missing feature bits 0x" + Integer.toHexString(hizFormatRequiredFeatures & ~hizFormatFeatures) + ")");
        }
        if (maxStorageBufferRange < 16) {
            missing.add("storage-buffer range >= 16 bytes (reported " + maxStorageBufferRange + ")");
        }
        if (maxComputeWorkGroupInvocations < 256 || maxComputeWorkGroupSizeX < 256) {
            missing.add("compute workgroup size >= 256 invocations in X (reported "
                    + maxComputeWorkGroupInvocations + " invocations, X=" + maxComputeWorkGroupSizeX + ")");
        }
        return List.copyOf(missing);
    }

    public boolean coreRequirementsMet() {
        return missingCoreCapabilities().isEmpty();
    }

    public void requireCore() {
        List<String> missing = missingCoreCapabilities();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("[JBP-LOD] Vulkan device '" + deviceName
                    + "' lacks core capabilities: " + String.join("; ", missing)
                    + ". OpenGL fallback is not available.");
        }
    }

    /** Returns the optimal-tiling feature mask captured for common color/depth/storage formats. */
    public int formatFeatures(int vkFormat) {
        Integer known = optimalFormatFeatures.get(vkFormat);
        if (known != null) {
            return known;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physicalDevice, vkFormat, properties);
            return properties.optimalTilingFeatures();
        }
    }

    public boolean supportsFormat(int vkFormat, int requiredOptimalTilingFeatures) {
        int actual = formatFeatures(vkFormat);
        return (actual & requiredOptimalTilingFeatures) == requiredOptimalTilingFeatures;
    }

    public String formatFeaturesDescription(int vkFormat) {
        int features = formatFeatures(vkFormat);
        return "VkFormat=" + vkFormat + " optimalTilingFeatures=0x" + Integer.toHexString(features)
                + " sampled=" + hasFeature(features, VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)
                + " storage=" + hasFeature(features, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT)
                + " colorAttachment=" + hasFeature(features, VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT)
                + " depthStencilAttachment=" + hasFeature(features, VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT);
    }

    private static boolean hasFeature(int features, int bit) {
        return (features & bit) != 0;
    }

    private static String apiVersionString(int version) {
        return (version >>> 22) + "." + ((version >>> 12) & 0x3ff) + "." + (version & 0xfff);
    }

    public String deviceName() { return deviceName; }
    public int apiVersion() { return apiVersion; }
    public int vendorId() { return vendorId; }
    public int driverVersion() { return driverVersion; }
    public int graphicsQueueFamily() { return graphicsQueueFamily; }
    public int computeQueueFamily() { return computeQueueFamily; }
    public boolean drawIndirectFirstInstanceSupported() { return drawIndirectFirstInstanceSupported; }
    public boolean drawIndirectFirstInstance() { return drawIndirectFirstInstance; }
    public boolean multiDrawIndirectSupported() { return multiDrawIndirectSupported; }
    public boolean multiDrawIndirect() { return multiDrawIndirect; }
    public int transferQueueFamily() { return transferQueueFamily; }
    public boolean drawIndirectCountSupported() { return drawIndirectCountSupported; }
    public boolean drawIndirectCount() { return drawIndirectCount; }
    public boolean shaderInt64Supported() { return shaderInt64Supported; }
    public boolean shaderInt64() { return shaderInt64; }
    public boolean subgroupArithmetic() { return subgroupArithmetic; }
    public boolean subgroupClustered() { return subgroupClustered; }
    public boolean subgroupHizReductionSupported() {
        return subgroupArithmetic && subgroupClustered && subgroupSize >= 16;
    }
    public int subgroupSize() { return subgroupSize; }
    public int subgroupSupportedOperations() { return subgroupSupportedOperations; }
    public int subgroupSupportedStages() { return subgroupSupportedStages; }
    public long maxStorageBufferRange() { return maxStorageBufferRange; }
    public long maxUniformBufferRange() { return maxUniformBufferRange; }
    public int maxPushConstantsSize() { return maxPushConstantsSize; }
    public int maxDrawIndirectCount() { return maxDrawIndirectCount; }
    public int maxComputeWorkGroupInvocations() { return maxComputeWorkGroupInvocations; }
    public int maxComputeWorkGroupSizeX() { return maxComputeWorkGroupSizeX; }
    public int maxComputeWorkGroupSizeY() { return maxComputeWorkGroupSizeY; }
    public int maxComputeWorkGroupSizeZ() { return maxComputeWorkGroupSizeZ; }
    public int maxComputeWorkGroupCountX() { return maxComputeWorkGroupCountX; }
    public int maxComputeWorkGroupCountY() { return maxComputeWorkGroupCountY; }
    public int maxComputeWorkGroupCountZ() { return maxComputeWorkGroupCountZ; }
    public Map<Integer, Integer> optimalFormatFeatures() { return optimalFormatFeatures; }
    public String snapshotText() {
        return "device='" + deviceName + "' api=" + apiVersionString(apiVersion)
                + " vendorId=0x" + Integer.toHexString(vendorId)
                + " driverVersion=0x" + Integer.toHexString(driverVersion)
                + " queues={graphics:" + graphicsQueueFamily + ",compute:" + computeQueueFamily
                + ",transfer:" + transferQueueFamily + "}"
                + " core={drawIndirectFirstInstance:supported=" + drawIndirectFirstInstanceSupported
                + ",enabled=" + drawIndirectFirstInstance + "}"
                + " optional={multiDrawIndirect:supported=" + multiDrawIndirectSupported + ",enabled=" + multiDrawIndirect
                + ",drawIndirectCount:supported=" + drawIndirectCountSupported + ",enabled=" + drawIndirectCount
                + ",shaderInt64:supported=" + shaderInt64Supported + ",enabled=" + shaderInt64
                + ",subgroupArithmetic=" + subgroupArithmetic + ",subgroupClustered=" + subgroupClustered
                + ",subgroupHizReduction=" + subgroupHizReductionSupported() + ",subgroupSize=" + subgroupSize
                + ",subgroupOps=0x" + Integer.toHexString(subgroupSupportedOperations)
                + ",subgroupStages=0x" + Integer.toHexString(subgroupSupportedStages) + "}"
                + " limits={maxStorageBufferRange=" + maxStorageBufferRange
                + ",maxUniformBufferRange=" + maxUniformBufferRange
                + ",maxPushConstantsSize=" + maxPushConstantsSize
                + ",maxDrawIndirectCount=" + maxDrawIndirectCount
                + ",computeInvocations=" + maxComputeWorkGroupInvocations
                + ",computeSize=[" + maxComputeWorkGroupSizeX + "," + maxComputeWorkGroupSizeY + "," + maxComputeWorkGroupSizeZ
                + "],computeCount=[" + maxComputeWorkGroupCountX + "," + maxComputeWorkGroupCountY + "," + maxComputeWorkGroupCountZ
                + "]}"
                + " formats=" + optimalFormatFeatures;
    }

    @Override
    public String toString() {
        return "DeviceCaps{" + snapshotText() + "}";
    }
}
