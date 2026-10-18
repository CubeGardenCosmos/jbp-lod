package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan.Snapshot;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.FrontendRenderPassAccessor;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.VulkanRenderPassAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp;
import static org.lwjgl.vulkan.VK10.vkCmdClearAttachments;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
import static org.lwjgl.vulkan.VK10.vkGetFenceStatus;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;

/** Opt-in ordering probe; records a tiny magenta clear marker inside the active CUTOUT render scope. */
public final class VulkanBridgeProbe {
    public static final String ENABLE_PROPERTY = "jbp.lod.vkProbe";
    public static final String INTERVAL_PROPERTY = "jbp.lod.vkProbeInterval";

    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod-vk-bridge");
    private static final int SLOT_COUNT = 3;
    private static final int QUERIES_PER_SLOT = 2;
    private static final int QUERY_COUNT = SLOT_COUNT * QUERIES_PER_SLOT;
    private static final AtomicLong PASS_FRAME = new AtomicLong();
    private static final AtomicLong SAMPLE_SEQUENCE = new AtomicLong();
    private static final Object RESOURCE_LOCK = new Object();
    private static volatile Resources resources;
    private static volatile Set<String> availableLayers;

    private VulkanBridgeProbe() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    /** Called at DefaultChunkRenderer.render HEAD, before its Vulkan draw commands. */
    public static Frame beforeCutout(ChunkRenderMatrices matrices, TerrainRenderPass terrainPass,
                                    RenderPass renderPass) {
        long frameNumber = PASS_FRAME.incrementAndGet();
        int interval = sampleInterval();
        if (frameNumber % interval != 0) {
            return null;
        }
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("[JBP-LOD] Vulkan bridge probe must run on the RenderSystem render thread.");
        }
        long sampleNumber = SAMPLE_SEQUENCE.getAndIncrement();

        Snapshot game = GameVulkan.current();
        RenderPassBackend backend = backend(renderPass);
        if (!(backend instanceof VulkanRenderPass vulkanPass)) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT RenderPass backend is not Vulkan: "
                    + className(backend));
        }
        if (!(vulkanPass instanceof VulkanRenderPassAccessor passAccessor)) {
            throw new IllegalStateException("[JBP-LOD] VulkanRenderPass command-buffer accessor mixin is not applied.");
        }

        VkCommandBuffer commandBuffer = passAccessor.jbpLod$getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("[JBP-LOD] Sodium CUTOUT VulkanRenderPass has no command buffer.");
        }

        RenderTarget target = terrainPass.getTarget();
        ImageTarget color = imageTarget("color", target.getColorTextureView());
        ImageTarget depth = imageTarget("depth", target.getDepthTextureView());
        if (color == null) {
            throw new IllegalStateException("[JBP-LOD] Sodium CUTOUT target has no Vulkan color view.");
        }

        Resources active = resourcesFor(game);
        Frame frame = active.recordBeforePass(frameNumber, sampleNumber, game, commandBuffer, target, color, depth);
        if (frame != null) {
            logFrame(frame, active, matrices);
        }
        return frame;
    }

    /** Called immediately before Sodium closes its RenderPearl CUTOUT pass. */
    public static void insideCutout(TerrainRenderPass terrainPass, RenderPass renderPass, Frame frame) {
        RenderPassBackend backend = backend(renderPass);
        if (!(backend instanceof VulkanRenderPass vulkanPass)) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT RenderPass backend changed before end: "
                    + className(backend));
        }
        if (!(vulkanPass instanceof VulkanRenderPassAccessor passAccessor)) {
            throw new IllegalStateException("[JBP-LOD] VulkanRenderPass accessor mixin is not applied.");
        }

        VkCommandBuffer currentCommandBuffer = passAccessor.jbpLod$getCommandBuffer();
        if (currentCommandBuffer == null) {
            throw new IllegalStateException("[JBP-LOD] Sodium CUTOUT VulkanRenderPass has no command buffer.");
        }
        if (frame != null && currentCommandBuffer.address() != frame.graphicsCommandBufferAddress) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT VkCommandBuffer changed within one Sodium render call.");
        }

        RenderTarget target = frame == null ? terrainPass.getTarget() : frame.target;
        ImageTarget color = frame == null ? imageTarget("color", target.getColorTextureView()) : frame.color;
        if (color == null) {
            throw new IllegalStateException("[JBP-LOD] Sodium CUTOUT target has no Vulkan color view.");
        }
        RenderPass.RenderArea renderArea = passAccessor.jbpLod$getRenderArea();
        boolean visibleClearRecorded = recordVisibleClearMarker(currentCommandBuffer, color, renderArea);
        if (frame == null) {
            return;
        }

        Resources active = frame.owner;
        if (active.closed || active.device.address() != frame.game.device().address()) {
            throw new IllegalStateException("[JBP-LOD] Vulkan probe device changed during the borrowed CUTOUT pass.");
        }

        if (active.timestampSupported) {
            vkCmdWriteTimestamp(currentCommandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    active.queryPool, frame.queryIndex + 1);
        }
        if (frame.game.debugUtilsEnabled()) {
            frame.game.gameInstance().debug().beginDebugGroup(currentCommandBuffer,
                    () -> "JBP-LOD V1 in-pass marker; sampled CUTOUT frame " + frame.frameNumber);
            frame.game.gameInstance().debug().endDebugGroup(currentCommandBuffer);
        }

        frame.slot.markerRecorded = true;
        LOGGER.info("[JBP-LOD] Vulkan bridge marker frame={} pass=CUTOUT commandBuffer=0x{} "
                        + "insideDynamicRenderingScope=true queryTimestampRecorded={} debugUtilsLabelRecorded={} "
                        + "visibleClearRegionRecorded={} visibleClearColor=magenta clearRect={}x{}@{},{} "
                        + "markerBefore=ShaderChunkRenderer.end(TerrainRenderPass)",
                frame.frameNumber,
                Long.toHexString(frame.graphicsCommandBufferAddress),
                active.timestampSupported,
                frame.game.debugUtilsEnabled(),
                visibleClearRecorded,
                Math.min(8, renderArea.width()),
                Math.min(8, renderArea.height()),
                renderArea.x(),
                renderArea.y());
    }

    /** Called from the RenderPearl VulkanDevice close hook before its VkDevice is destroyed. */
    public static void close(VulkanDevice closingDevice) {
        synchronized (RESOURCE_LOCK) {
            Resources current = resources;
            if (current == null || current.device.address() != closingDevice.vkDevice().address()) {
                return;
            }
            current.destroy();
            resources = null;
        }
    }
    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static RenderPassBackend backend(RenderPass renderPass) {
        if (!(renderPass instanceof FrontendRenderPass frontendPass)) {
            throw new IllegalStateException("[JBP-LOD] Sodium CUTOUT pass is not a FrontendRenderPass: "
                    + className(renderPass));
        }
        if (!(frontendPass instanceof FrontendRenderPassAccessor accessor)) {
            throw new IllegalStateException("[JBP-LOD] FrontendRenderPass backend accessor mixin is not applied.");
        }
        return accessor.jbpLod$getBackend();
    }

    private static ImageTarget imageTarget(String role, GpuTextureView view) {
        if (view == null) {
            return null;
        }
        if (!(view instanceof VulkanGpuTextureView vulkanView)) {
            throw new IllegalStateException("[JBP-LOD] CUTOUT " + role + " target view is not Vulkan: "
                    + className(view));
        }
        VulkanGpuTexture texture = vulkanView.texture();
        return new ImageTarget(role, vulkanView.vkImageView(), texture.vkImage(), texture.getFormat(),
                VulkanConst.toVk(texture.getFormat()), view.getWidth(0), view.getHeight(0),
                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, 1);
    }
    private static boolean recordVisibleClearMarker(VkCommandBuffer commandBuffer, ImageTarget color,
                                                     RenderPass.RenderArea renderArea) {
        if (!supportsFloatColorClear(color.vkFormat)) {
            return false;
        }
        int width = Math.min(8, renderArea.width());
        int height = Math.min(8, renderArea.height());
        int x = renderArea.x();
        int y = renderArea.y();
        if (width <= 0 || height <= 0 || x < 0 || y < 0
                || x > color.width - width || y > color.height - height) {
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearAttachment.Buffer attachments = VkClearAttachment.calloc(1, stack);
            attachments.get(0)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .colorAttachment(0)
                    .clearValue().color()
                    .float32(0, 1.0f)
                    .float32(1, 0.0f)
                    .float32(2, 1.0f)
                    .float32(3, 1.0f);

            VkClearRect.Buffer rectangles = VkClearRect.calloc(1, stack);
            rectangles.get(0)
                    .rect(rect -> {
                        rect.offset().set(x, y);
                        rect.extent().set(width, height);
                    })
                    .baseArrayLayer(0)
                    .layerCount(1);
            vkCmdClearAttachments(commandBuffer, attachments, rectangles);
        }
        return true;
    }

    private static boolean supportsFloatColorClear(int vkFormat) {
        return vkFormat == VK_FORMAT_R8G8B8A8_UNORM
                || vkFormat == VK_FORMAT_R8G8B8A8_SRGB
                || vkFormat == VK_FORMAT_B8G8R8A8_UNORM
                || vkFormat == VK_FORMAT_B8G8R8A8_SRGB
                || vkFormat == VK_FORMAT_R16G16B16A16_SFLOAT;
    }

    private static Resources resourcesFor(Snapshot game) {
        synchronized (RESOURCE_LOCK) {
            Resources current = resources;
            if (current == null) {
                current = new Resources(game);
                resources = current;
            } else if (current.device.address() != game.device().address()) {
                throw new IllegalStateException("[JBP-LOD] Vulkan bridge probe cannot outlive the RenderPearl device "
                        + "it was created for; the old device close hook did not run.");
            }
            return current;
        }
    }

    private static int sampleInterval() {
        String configured = System.getProperty(INTERVAL_PROPERTY, "120");
        try {
            return Math.max(1, Integer.parseInt(configured));
        } catch (NumberFormatException e) {
            LOGGER.warn("[JBP-LOD] Invalid {}='{}'; using 120 CUTOUT passes.", INTERVAL_PROPERTY, configured);
            return 120;
        }
    }

    private static void logFrame(Frame frame, Resources active, ChunkRenderMatrices matrices) {
        Snapshot game = frame.game;
        LOGGER.info("[JBP-LOD] Vulkan bridge frame={} pass=CUTOUT backend={} device='{}' vendor='{}' driver='{}' "
                        + "vkInstance=0x{} physicalDevice=0x{} vkDevice=0x{} graphicsQueue=0x{} graphicsFamily={} "
                        + "computeQueue=0x{} computeFamily={} transferQueue=0x{} transferFamily={} vma=0x{}",
                frame.frameNumber,
                game.deviceInfo().backendName(),
                game.deviceInfo().name(),
                game.deviceInfo().vendorName(),
                game.deviceInfo().driverInfo(),
                Long.toHexString(game.instance().address()),
                Long.toHexString(game.physicalDevice().address()),
                Long.toHexString(game.device().address()),
                Long.toHexString(game.graphicsQueue().vkQueue().address()),
                game.graphicsQueue().queueFamilyIndex(),
                Long.toHexString(game.computeQueue().vkQueue().address()),
                game.computeQueue().queueFamilyIndex(),
                Long.toHexString(game.transferQueue().vkQueue().address()),
                game.transferQueue().queueFamilyIndex(),
                Long.toHexString(game.vmaAllocator()));
        LOGGER.info("[JBP-LOD] Vulkan bridge frame={} instanceExtensions={} enabledDeviceExtensions={} "
                        + "enabledFeatures={} reportedDeviceExtensions={} timestampPeriod={} debugUtilsEnabled={} "
                        + "validationLayerAvailable={} validationLayerRequestedByLoaderEnv={} "
                        + "validationLayerActivation=not-exposed-by-RenderPearl",
                frame.frameNumber,
                sorted(game.enabledInstanceExtensions()),
                sorted(game.enabledDeviceExtensions()),
                sorted(game.enabledFeatures()),
                sorted(game.reportedDeviceExtensions()),
                game.deviceInfo().timestampPeriod(),
                game.debugUtilsEnabled(),
                validationLayerAvailable(),
                validationLayerRequestedByEnvironment());
        LOGGER.info("[JBP-LOD] Vulkan bridge frame={} targetExtent={}x{} color={} depth={} layout=VK_IMAGE_LAYOUT_GENERAL(1) "
                        + "sampleCount=1 targetIdentity=borrowed-per-pass commandBuffer=0x{} renderScopeActive=true "
                        + "timestampSupported={} querySlot={}",
                frame.frameNumber,
                frame.target.width,
                frame.target.height,
                describe(frame.color),
                describe(frame.depth),
                Long.toHexString(frame.graphicsCommandBufferAddress),
                active.timestampSupported,
                frame.slot.index);
        LOGGER.info("[JBP-LOD] Vulkan bridge matrices frame={} projection={} modelView={}",
                frame.frameNumber,
                Arrays.toString(matrices.projection().get(new float[16])),
                Arrays.toString(matrices.modelView().get(new float[16])));
        logRuntimeModules(frame.frameNumber);
        LOGGER.info("[JBP-LOD] Vulkan ordering frame={} prepassSubmission=submitted-to-graphics-queue-before-CUTOUT "
                        + "graphicsCommandBufferWillBeSubmitted-later prepassQueueIsGraphics=true "
                        + "sameGraphicsComputeQueue={} sameGraphicsComputeFamily={} "
                        + "queueOwnershipTransfer=not-required memoryHazard=none(timestamp-only probe)",
                frame.frameNumber,
                game.graphicsQueue().vkQueue().address() == game.computeQueue().vkQueue().address(),
                game.graphicsQueue().queueFamilyIndex() == game.computeQueue().queueFamilyIndex());
    }

    private static String describe(ImageTarget target) {
        if (target == null) {
            return "<absent>";
        }
        return target.role + "{view=0x" + Long.toHexString(target.view)
                + ",image=0x" + Long.toHexString(target.image)
                + ",format=" + target.format + "/VkFormat=" + target.vkFormat
                + ",extent=" + target.width + "x" + target.height
                + ",layout=" + layoutName(target.layout)
                + ",samples=" + target.sampleCount + "}";
    }
    private static String layoutName(int layout) {
        if (layout == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL) {
            return "GENERAL";
        }
        if (layout == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            return "COLOR_ATTACHMENT_OPTIMAL";
        }
        if (layout == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
            return "DEPTH_STENCIL_ATTACHMENT_OPTIMAL";
        }
        return "UNKNOWN(" + layout + ")";
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static boolean validationLayerAvailable() {
        Set<String> layers = availableLayers;
        if (layers == null) {
            synchronized (RESOURCE_LOCK) {
                layers = availableLayers;
                if (layers == null) {
                    layers = enumerateInstanceLayers();
                    availableLayers = layers;
                }
            }
        }
        return layers.contains("VK_LAYER_KHRONOS_validation");
    }

    private static Set<String> enumerateInstanceLayers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int result = vkEnumerateInstanceLayerProperties(count, null);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[JBP-LOD] vkEnumerateInstanceLayerProperties(count) failed with VkResult {}.", result);
                return Set.of();
            }
            VkLayerProperties.Buffer properties = VkLayerProperties.calloc(count.get(0), stack);
            result = vkEnumerateInstanceLayerProperties(count, properties);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[JBP-LOD] vkEnumerateInstanceLayerProperties(list) failed with VkResult {}.", result);
                return Set.of();
            }
            Set<String> names = new java.util.HashSet<>();
            for (int index = 0; index < count.get(0); index++) {
                names.add(properties.get(index).layerNameString());
            }
            Set<String> immutableNames = Set.copyOf(names);
            LOGGER.info("[JBP-LOD] Vulkan instance layers available={}", sorted(immutableNames));
            return immutableNames;
        }
    }

    private static boolean validationLayerRequestedByEnvironment() {
        String requested = System.getenv("VK_INSTANCE_LAYERS");
        if (requested == null || requested.isBlank()) {
            return false;
        }
        for (String layer : requested.split("[:,;]")) {
            if (layer.trim().equals("VK_LAYER_KHRONOS_validation")) {
                return true;
            }
        }
        return false;
    }

    private static void logRuntimeModules(long frameNumber) {
        LOGGER.info("[JBP-LOD] Vulkan classpath frame={} lwjgl-vulkan={} lwjgl-shaderc={} lwjgl-spvc={} lwjgl-vma={}",
                frameNumber,
                moduleOrigin("org.lwjgl.vulkan.VK"),
                moduleOrigin("org.lwjgl.util.shaderc.Shaderc"),
                moduleOrigin("org.lwjgl.util.spvc.Spvc"),
                moduleOrigin("org.lwjgl.util.vma.Vma"));
    }

    private static String moduleOrigin(String className) {
        String resourceName = className.replace('.', '/') + ".class";
        var resource = VulkanBridgeProbe.class.getClassLoader().getResource(resourceName);
        return resource == null ? "absent" : "present(" + resource + ")";
    }

    private record ImageTarget(String role, long view, long image, GpuFormat format, int vkFormat,
                               int width, int height, int layout, int sampleCount) {
    }

    public static final class Frame {
        private final Resources owner;
        private final Slot slot;
        private final Snapshot game;
        private final long frameNumber;
        private final long graphicsCommandBufferAddress;
        private final RenderTarget target;
        private final ImageTarget color;
        private final ImageTarget depth;
        private final int queryIndex;

        private Frame(Resources owner, Slot slot, Snapshot game, long frameNumber, VkCommandBuffer graphicsCommandBuffer,
                      RenderTarget target, ImageTarget color, ImageTarget depth) {
            this.owner = owner;
            this.slot = slot;
            this.game = game;
            this.frameNumber = frameNumber;
            this.graphicsCommandBufferAddress = graphicsCommandBuffer.address();
            this.target = target;
            this.color = color;
            this.depth = depth;
            this.queryIndex = slot.index * QUERIES_PER_SLOT;
        }
    }

    private static final class Slot {
        private final int index;
        private final VkCommandBuffer commandBuffer;
        private final long fence;
        private boolean submitted;
        private boolean markerRecorded;
        private long frameNumber;

        private Slot(int index, VkCommandBuffer commandBuffer, long fence) {
            this.index = index;
            this.commandBuffer = commandBuffer;
            this.fence = fence;
        }
    }

    private static final class Resources {
        private final org.lwjgl.vulkan.VkDevice device;
        private final org.lwjgl.vulkan.VkQueue queue;
        private final long commandPool;
        private final long queryPool;
        private final Slot[] slots;
        private final int timestampValidBits;
        private final boolean timestampSupported;
        private boolean closed;

        private Resources(Snapshot game) {
            this.device = game.device();
            this.queue = game.graphicsQueue().vkQueue();
            this.timestampValidBits = timestampValidBits(game);
            this.timestampSupported = timestampValidBits > 0;
            if (!timestampSupported) {
                throw new IllegalStateException("[JBP-LOD] Graphics queue family "
                        + game.graphicsQueue().queueFamilyIndex() + " has timestampValidBits=0; "
                        + "the Vulkan bridge order probe requires timestamp support.");
            }

            long createdCommandPool = 0;
            long createdQueryPool = 0;
            long[] createdFences = new long[SLOT_COUNT];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                        .queueFamilyIndex(game.graphicsQueue().queueFamilyIndex());
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateCommandPool(device, commandPoolInfo, null, handle), "vkCreateCommandPool");
                createdCommandPool = handle.get(0);

                VkQueryPoolCreateInfo queryPoolInfo = VkQueryPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .queryType(VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(QUERY_COUNT);
                check(vkCreateQueryPool(device, queryPoolInfo, null, handle), "vkCreateQueryPool");
                createdQueryPool = handle.get(0);

                VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default()
                        .commandPool(createdCommandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(SLOT_COUNT);
                PointerBuffer commandBufferAddresses = stack.mallocPointer(SLOT_COUNT);
                check(vkAllocateCommandBuffers(device, allocateInfo, commandBufferAddresses),
                        "vkAllocateCommandBuffers");

                for (int index = 0; index < SLOT_COUNT; index++) {
                    VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                            .sType$Default();
                    check(vkCreateFence(device, fenceInfo, null, handle), "vkCreateFence");
                    createdFences[index] = handle.get(0);
                }

                this.commandPool = createdCommandPool;
                this.queryPool = createdQueryPool;
                this.slots = new Slot[SLOT_COUNT];
                for (int index = 0; index < SLOT_COUNT; index++) {
                    this.slots[index] = new Slot(index,
                            new VkCommandBuffer(commandBufferAddresses.get(index), device), createdFences[index]);
                }
            } catch (RuntimeException e) {
                for (long fence : createdFences) {
                    if (fence != 0) {
                        vkDestroyFence(device, fence, null);
                    }
                }
                if (createdQueryPool != 0) {
                    vkDestroyQueryPool(device, createdQueryPool, null);
                }
                if (createdCommandPool != 0) {
                    vkDestroyCommandPool(device, createdCommandPool, null);
                }
                throw e;
            }
        }

        private Frame recordBeforePass(long frameNumber, long sampleNumber, Snapshot game,
                                       VkCommandBuffer graphicsCommandBuffer, RenderTarget target,
                                       ImageTarget color, ImageTarget depth) {
            pollCompletedSamples();
            Slot slot = slots[(int) (sampleNumber % SLOT_COUNT)];
            if (slot.submitted && !slotAvailable(slot)) {
                LOGGER.warn("[JBP-LOD] Vulkan probe skipped frame={}: query slot {} is still in flight; "
                                + "no queue/device wait was issued.", frameNumber, slot.index);
                return null;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (slot.submitted) {
                    check(vkResetFences(device, stack.longs(slot.fence)), "vkResetFences");
                    check(vkResetCommandBuffer(slot.commandBuffer, 0), "vkResetCommandBuffer");
                }

                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(slot.commandBuffer, beginInfo), "vkBeginCommandBuffer");
                int queryIndex = slot.index * QUERIES_PER_SLOT;
                vkCmdResetQueryPool(slot.commandBuffer, queryPool, queryIndex, QUERIES_PER_SLOT);
                vkCmdWriteTimestamp(slot.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, queryIndex);
                check(vkEndCommandBuffer(slot.commandBuffer), "vkEndCommandBuffer");

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType$Default()
                        .pCommandBuffers(stack.pointers(slot.commandBuffer.address()));
                // This hook runs on the render thread, so this same-queue submit precedes the game's later submit.
                check(vkQueueSubmit(queue, submitInfo, slot.fence), "vkQueueSubmit(V1 pre-CUTOUT timestamp)");
            }

            slot.submitted = true;
            slot.markerRecorded = false;
            slot.frameNumber = frameNumber;
            return new Frame(this, slot, game, frameNumber, graphicsCommandBuffer, target, color, depth);
        }

        private void pollCompletedSamples() {
            for (Slot slot : slots) {
                if (!slot.submitted || !slot.markerRecorded) {
                    continue;
                }
                int fenceStatus = vkGetFenceStatus(device, slot.fence);
                if (fenceStatus == VK_NOT_READY) {
                    continue;
                }
                check(fenceStatus, "vkGetFenceStatus");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer values = stack.callocLong(QUERIES_PER_SLOT * 2);
                    int result = vkGetQueryPoolResults(device, queryPool, slot.index * QUERIES_PER_SLOT,
                            QUERIES_PER_SLOT, values, (long) values.capacity() * Long.BYTES,
                            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                    if (result == VK_NOT_READY) {
                        continue;
                    }
                    check(result, "vkGetQueryPoolResults");
                    if (values.get(1) == 0 || values.get(3) == 0) {
                        continue;
                    }
                    long before = values.get(0);
                    long inside = values.get(2);
                    long mask = timestampValidBits >= Long.SIZE ? -1L : (1L << timestampValidBits) - 1L;
                    long deltaTicks = (inside - before) & mask;
                    LOGGER.info("[JBP-LOD] Vulkan order timestamps frame={} prepassTopOfPipe={} "
                                    + "insideCutoutBottomOfPipe={} deltaTicks={} timestampOrderObserved={}",
                            slot.frameNumber, before, inside, deltaTicks, deltaTicks > 0);
                }
            }
        }

        private boolean slotAvailable(Slot slot) {
            int fenceStatus = vkGetFenceStatus(device, slot.fence);
            if (fenceStatus == VK_NOT_READY) {
                return false;
            }
            check(fenceStatus, "vkGetFenceStatus");
            if (!slot.markerRecorded) {
                return false;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer values = stack.callocLong(QUERIES_PER_SLOT * 2);
                int result = vkGetQueryPoolResults(device, queryPool, slot.index * QUERIES_PER_SLOT,
                        QUERIES_PER_SLOT, values, (long) values.capacity() * Long.BYTES,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (result == VK_NOT_READY) {
                    return false;
                }
                check(result, "vkGetQueryPoolResults");
                return values.get(1) != 0 && values.get(3) != 0;
            }
        }

        private void destroy() {
            if (closed) {
                return;
            }
            closed = true;
            int result = vkDeviceWaitIdle(device);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[JBP-LOD] vkDeviceWaitIdle during bridge-probe shutdown returned VkResult {}.", result);
            }
            for (Slot slot : slots) {
                vkDestroyFence(device, slot.fence, null);
            }
            vkDestroyQueryPool(device, queryPool, null);
            vkDestroyCommandPool(device, commandPool, null);
            LOGGER.info("[JBP-LOD] Vulkan bridge probe resources destroyed at RenderPearl device shutdown.");
        }
    }

    private static int timestampValidBits(Snapshot game) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(game.physicalDevice(), count, null);
            VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(game.physicalDevice(), count, properties);
            int family = game.graphicsQueue().queueFamilyIndex();
            if (family < 0 || family >= properties.limit()) {
                throw new IllegalStateException("[JBP-LOD] Graphics queue family index " + family
                        + " is outside the physical device queue-family list of " + properties.limit() + " entries.");
            }
            return properties.get(family).timestampValidBits();
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result + ".");
        }
    }
}
