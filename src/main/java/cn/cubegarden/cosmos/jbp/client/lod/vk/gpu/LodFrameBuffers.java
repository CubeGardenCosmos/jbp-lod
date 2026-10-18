package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.data.LodSceneUniform;
import cn.cubegarden.cosmos.jbp.client.lod.data.TraversalSceneUniform;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;

/** Frame-in-flight output and scratch allocations, plus its reusable pre-cutout command buffer. */
public final class LodFrameBuffers implements AutoCloseable {
    public static final int DEFAULT_SECTION_CAPACITY = 200_000;
    public static final int MAX_DRAW_COMMANDS = 600_000;
    public static final int OPAQUE_COMMAND_CAPACITY = 400_000;
    public static final int TRANSLUCENT_COMMAND_CAPACITY = 100_000;
    public static final int TEMPORAL_COMMAND_CAPACITY = 100_000;
    public static final int TRANSLUCENT_DISTANCE_BINS = 1024;
    public static final int REQUEST_QUEUE_CAPACITY = 50;
    public static final int LOD_LEVEL_COUNT = 5;

    private final GameVulkan.Snapshot game;
    private final long commandPool;
    private final VkCommandBuffer commandBuffer;
    private final int slotIndex;
    private final int sectionCapacity;
    private final GpuBuffer drawCommands;
    private final GpuBuffer drawControl;
    private final GpuBuffer positionScratch;
    private final GpuBuffer translucentScratch;
    private final GpuBuffer indirectSectionLookup;
    private final GpuBuffer visibility;
    private final GpuBuffer sceneUniform;
    private final GpuBuffer traversalUniform;
    private final GpuBuffer nodeQueueMetadata;
    private final GpuBuffer nodeQueueA;
    private final GpuBuffer nodeQueueB;
    private final GpuBuffer requestQueue;
    private final GpuBuffer renderTracker;
    private final GpuBuffer prefixScratch;
    private final List<AutoCloseable> retirementActions = new ArrayList<>();
    private boolean recording;
    private boolean submitted;
    private boolean closed;
    private boolean initialized;

    public LodFrameBuffers(GameVulkan.Snapshot game, int sectionCapacity) {
        this(game, sectionCapacity, 0);
    }

    LodFrameBuffers(GameVulkan.Snapshot game, int sectionCapacity, int slotIndex) {
        if (sectionCapacity <= 0 || sectionCapacity > DEFAULT_SECTION_CAPACITY) {
            throw new IllegalArgumentException("section capacity must be 1.." + DEFAULT_SECTION_CAPACITY);
        }
        this.game = game;
        this.sectionCapacity = sectionCapacity;
        this.slotIndex = slotIndex;
        long createdCommandPool = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(game.graphicsQueue().queueFamilyIndex());
            var pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(game.device(), poolInfo, null, pPool), "vkCreateCommandPool");
            createdCommandPool = pPool.get(0);
            VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(createdCommandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommand = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(game.device(), allocateInfo, pCommand), "vkAllocateCommandBuffers");
            commandPool = createdCommandPool;
            commandBuffer = new VkCommandBuffer(pCommand.get(0), game.device());
        } catch (RuntimeException | Error failure) {
            if (createdCommandPool != 0) {
                vkDestroyCommandPool(game.device(), createdCommandPool, null);
            }
            throw failure;
        }

        long maxSections = sectionCapacity;
        int storageTransfer = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        GpuBuffer createdDrawCommands = null;
        GpuBuffer createdDrawControl = null;
        GpuBuffer createdPositionScratch = null;
        GpuBuffer createdTranslucentScratch = null;
        GpuBuffer createdLookup = null;
        GpuBuffer createdVisibility = null;
        GpuBuffer createdSceneUniform = null;
        GpuBuffer createdTraversalUniform = null;
        GpuBuffer createdQueueMetadata = null;
        GpuBuffer createdQueueA = null;
        GpuBuffer createdQueueB = null;
        GpuBuffer createdRequestQueue = null;
        GpuBuffer createdRenderTracker = null;
        GpuBuffer createdPrefixScratch = null;
        try {
            createdDrawCommands = buffer(game, (long) MAX_DRAW_COMMANDS * 20,
                    storageTransfer | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            createdDrawControl = buffer(game, 44,
                    storageTransfer | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            createdPositionScratch = buffer(game, maxSections * 8, storageTransfer | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            createdTranslucentScratch = buffer(game,
                    (long) (TRANSLUCENT_DISTANCE_BINS + TRANSLUCENT_COMMAND_CAPACITY) * Integer.BYTES,
                    storageTransfer);
            createdLookup = buffer(game, (maxSections + 1) * Integer.BYTES, storageTransfer);
            createdVisibility = buffer(game, maxSections * Integer.BYTES, storageTransfer);
            createdSceneUniform = GpuBuffer.allocate(game, LodSceneUniform.BYTE_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
            createdTraversalUniform = GpuBuffer.allocate(game, TraversalSceneUniform.BYTE_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
            createdQueueMetadata = GpuBuffer.allocate(game, (long) LOD_LEVEL_COUNT * 16, storageTransfer, true);
            createdQueueA = GpuBuffer.allocate(game, maxSections * Integer.BYTES, storageTransfer, true);
            createdQueueB = buffer(game, maxSections * Integer.BYTES, storageTransfer);
            createdRequestQueue = GpuBuffer.allocate(game,
                    8L + (long) REQUEST_QUEUE_CAPACITY * 8, storageTransfer, true);
            createdRenderTracker = buffer(game, maxSections * Integer.BYTES, storageTransfer);
            createdPrefixScratch = buffer(game, 256L * 16, storageTransfer);
        } catch (RuntimeException | Error failure) {
            close(createdPrefixScratch, failure);
            close(createdRenderTracker, failure);
            close(createdRequestQueue, failure);
            close(createdQueueB, failure);
            close(createdQueueA, failure);
            close(createdQueueMetadata, failure);
            close(createdTraversalUniform, failure);
            close(createdSceneUniform, failure);
            close(createdVisibility, failure);
            close(createdTranslucentScratch, failure);
            close(createdPositionScratch, failure);
            close(createdDrawControl, failure);
            close(createdDrawCommands, failure);
            vkDestroyCommandPool(game.device(), commandPool, null);
            throw failure;
        }
        drawCommands = createdDrawCommands;
        drawControl = createdDrawControl;
        positionScratch = createdPositionScratch;
        translucentScratch = createdTranslucentScratch;
        indirectSectionLookup = createdLookup;
        visibility = createdVisibility;
        sceneUniform = createdSceneUniform;
        traversalUniform = createdTraversalUniform;
        nodeQueueMetadata = createdQueueMetadata;
        nodeQueueA = createdQueueA;
        nodeQueueB = createdQueueB;
        requestQueue = createdRequestQueue;
        renderTracker = createdRenderTracker;
        prefixScratch = createdPrefixScratch;
    }

    public int sectionCapacity() { return sectionCapacity; }
    public GpuBuffer drawCommands() { ensureOpen(); return drawCommands; }
    public GpuBuffer drawControl() { ensureOpen(); return drawControl; }
    public GpuBuffer positionScratch() { ensureOpen(); return positionScratch; }
    public GpuBuffer translucentScratch() { ensureOpen(); return translucentScratch; }
    /** Shared traversal-render/cmdgen lookup allocation: count word followed by section IDs. */
    public GpuBuffer indirectSectionLookup() { ensureOpen(); return indirectSectionLookup; }
    public GpuBuffer visibility() { ensureOpen(); return visibility; }
    public GpuBuffer sceneUniform() { ensureOpen(); return sceneUniform; }
    public int slotIndex() { return slotIndex; }
    public GpuBuffer traversalUniform() { ensureOpen(); return traversalUniform; }
    public GpuBuffer nodeQueueMetadata() { ensureOpen(); return nodeQueueMetadata; }
    public GpuBuffer nodeQueueA() { ensureOpen(); return nodeQueueA; }
    public GpuBuffer nodeQueueB() { ensureOpen(); return nodeQueueB; }
    public GpuBuffer requestQueue() { ensureOpen(); return requestQueue; }
    public GpuBuffer renderTracker() { ensureOpen(); return renderTracker; }
    public GpuBuffer prefixScratch() { ensureOpen(); return prefixScratch; }
    public static long commandByteOffset(DrawRegion region) {
        return switch (region) {
            case OPAQUE -> 0;
            case TRANSLUCENT -> (long) OPAQUE_COMMAND_CAPACITY * 20;
            case TEMPORAL_OPAQUE -> (long) (OPAQUE_COMMAND_CAPACITY + TRANSLUCENT_COMMAND_CAPACITY) * 20;
        };
    }

    public static long drawCountByteOffset(DrawRegion region) {
        return switch (region) {
            case OPAQUE -> 12;
            case TRANSLUCENT -> 16;
            case TEMPORAL_OPAQUE -> 20;
        };
    }

    public static int drawCapacity(DrawRegion region) {
        return switch (region) {
            case OPAQUE -> OPAQUE_COMMAND_CAPACITY;
            case TRANSLUCENT -> TRANSLUCENT_COMMAND_CAPACITY;
            case TEMPORAL_OPAQUE -> TEMPORAL_COMMAND_CAPACITY;
        };
    }

    /** Begins this slot's command buffer after its prior frame has retired. */
    public VkCommandBuffer beginCommands() {
        ensureOpen();
        if (recording || submitted) {
            throw new IllegalStateException("frame command buffer is already recording or submitted");
        }
        check(vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");
        }
        recording = true;
        return commandBuffer;
    }

    public VkCommandBuffer commandBuffer() {
        ensureOpen();
        if (!recording) {
            throw new IllegalStateException("beginCommands must be called before recording");
        }
        return commandBuffer;
    }

    /** Ends the command buffer; caller then submits it to the borrowed game graphics queue. */
    public VkCommandBuffer endCommands() {
        ensureOpen();
        if (!recording) {
            throw new IllegalStateException("frame command buffer is not recording");
        }
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        recording = false;
        submitted = true;
        return commandBuffer;
    }

    /** Registers host resources (staging buffers, atlas generations) to close after frame retirement. */
    public void retireAfterUse(AutoCloseable resource) {
        ensureOpen();
        if (!submitted) {
            throw new IllegalStateException("resources may only be retained after this slot is submitted");
        }
        retirementActions.add(resource);
    }

    /** Clears stale draw/dispatch results before a fixed-count draw fallback consumes them. */
    public void clearDrawOutputs(VkCommandBuffer commandBuffer) {
        ensureOpen();
        if (!recording || commandBuffer.address() != this.commandBuffer.address()) {
            throw new IllegalArgumentException("clear must use this frame slot's active command buffer");
        }
        vkCmdFillBuffer(commandBuffer, drawCommands.handle(), 0, drawCommands.capacity(), 0);
        vkCmdFillBuffer(commandBuffer, drawControl.handle(), 0, drawControl.capacity(), 0);
        vkCmdFillBuffer(commandBuffer, translucentScratch.handle(), 0, translucentScratch.capacity(), 0);
        Barriers.transferWriteToComputeWrite(commandBuffer, drawCommands, 0, drawCommands.capacity());
        Barriers.transferWriteToComputeWrite(commandBuffer, drawControl, 0, drawControl.capacity());
        Barriers.transferWriteToComputeWrite(commandBuffer, translucentScratch, 0, translucentScratch.capacity());
    }

    /** Writes the 96-byte graphics-compatible prefix of the shared scene uniform. */
    public void writeSceneUniform(LodSceneUniform uniform) {
        ensureOpen();
        uniform.writeTo(sceneUniform.mappedBytes(), 0);
        sceneUniform.flush(0, LodSceneUniform.BYTE_SIZE);
    }

    /** Writes the full 208-byte traversal block into its independent uniform allocation. */
    public void writeTraversalSceneUniform(TraversalSceneUniform uniform) {
        ensureOpen();
        uniform.writeTo(traversalUniform.mappedBytes(), 0);
        traversalUniform.flush(0, TraversalSceneUniform.BYTE_SIZE);
    }
    /** Clears persistent renderer state exactly once for this frame slot. */
    public void recordInitialClears(VkCommandBuffer commandBuffer) {
        ensureRecordingCommand(commandBuffer);
        if (initialized) return;
        vkCmdFillBuffer(commandBuffer, renderTracker.handle(), 0, renderTracker.capacity(), 0);
        Barriers.transferWriteToComputeWrite(commandBuffer, renderTracker, 0, renderTracker.capacity());
        initialized = true;
    }

    /**
     * Seeds upstream node queue metadata and root IDs. Call before beginCommands(), then record
     * the host-write dependency using recordTraversalInputBarriers().
     */
    public int writeTraversalSeed(int[] topNodeIds) {
        ensureOpen();
        if (topNodeIds.length > sectionCapacity) {
            throw new IllegalArgumentException("top-level node count exceeds frame queue capacity");
        }
        java.nio.ByteBuffer roots = nodeQueueA.mappedBytes().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < topNodeIds.length; i++) {
            roots.putInt(i * Integer.BYTES, topNodeIds[i]);
        }
        nodeQueueA.flush(0, (long) topNodeIds.length * Integer.BYTES);
        java.nio.ByteBuffer metadata = nodeQueueMetadata.mappedBytes().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int firstDispatchGroups = (topNodeIds.length + 31) >>> 5;
        for (int i = 0; i < LOD_LEVEL_COUNT; i++) {
            int base = i * 16;
            metadata.putInt(base, i == 0 ? firstDispatchGroups : 0);
            metadata.putInt(base + 4, 1);
            metadata.putInt(base + 8, 1);
            metadata.putInt(base + 12, i == 0 ? topNodeIds.length : 0);
        }
        nodeQueueMetadata.flush(0, nodeQueueMetadata.capacity());
        java.nio.ByteBuffer requests = requestQueue.mappedBytes().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        requests.putInt(0, 0);
        requests.putInt(4, 0);
        requestQueue.flush(0, 8);
        return firstDispatchGroups;
    }

    public void recordTraversalInputBarriers(VkCommandBuffer commandBuffer, int rootCount) {
        ensureRecordingCommand(commandBuffer);
        Barriers.hostWriteToCompute(commandBuffer, nodeQueueA, 0, (long) rootCount * Integer.BYTES);
        Barriers.hostWriteToCompute(commandBuffer, nodeQueueMetadata, 0, nodeQueueMetadata.capacity());
        Barriers.hostWriteToCompute(commandBuffer, requestQueue, 0, 8);
        Barriers.hostWriteToCompute(commandBuffer, traversalUniform, 0, TraversalSceneUniform.BYTE_SIZE);
    }

    public void clearTraversalRenderCount(VkCommandBuffer commandBuffer) {
        ensureRecordingCommand(commandBuffer);
        vkCmdFillBuffer(commandBuffer, indirectSectionLookup.handle(), 0, Integer.BYTES, 0);
        Barriers.transferWriteToComputeWrite(commandBuffer, indirectSectionLookup, 0, Integer.BYTES);
    }

    boolean isRecording() {
        return recording;
    }

    void markRetired() {
        ensureOpen();
        submitted = false;
        for (AutoCloseable resource : retirementActions) {
            try {
                resource.close();
            } catch (Exception failure) {
                throw new IllegalStateException("failed closing a retired frame resource", failure);
            }
        }
        retirementActions.clear();
    }

    boolean isSubmitted() {
        return submitted;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (recording || submitted) {
            throw new IllegalStateException("cannot close a frame slot before its submitted work retires");
        }
        closed = true;
        close(prefixScratch);
        close(renderTracker);
        close(requestQueue);
        close(nodeQueueB);
        close(nodeQueueA);
        close(nodeQueueMetadata);
        close(sceneUniform);
        close(traversalUniform);
        close(indirectSectionLookup);
        close(translucentScratch);
        close(positionScratch);
        close(drawControl);
        close(drawCommands);
        vkDestroyCommandPool(game.device(), commandPool, null);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("frame buffers are closed");
        }
    }
    private void ensureRecordingCommand(VkCommandBuffer candidate) {
        ensureOpen();
        if (!recording || candidate == null || candidate.address() != commandBuffer.address()) {
            throw new IllegalArgumentException("operation requires this frame slot's active command buffer");
        }
    }

    private static GpuBuffer buffer(GameVulkan.Snapshot game, long bytes, int usage) {
        return GpuBuffer.allocate(game, bytes, usage, false);
    }

    private static void close(AutoCloseable resource, Throwable original) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static void close(GpuBuffer buffer) {
        if (buffer != null) buffer.close();
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
    public enum DrawRegion {
        OPAQUE,
        TRANSLUCENT,
        TEMPORAL_OPAQUE
    }
}
