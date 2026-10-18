package cn.cubegarden.cosmos.jbp.client.lod.runtime;

import cn.cubegarden.cosmos.jbp.client.lod.compute.LodComputeExecutor;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelProvider;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionDelta;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.caps.DeviceCaps;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuCompletion;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuRetireQueue;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodGpuBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodNodeGpuBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;

/**
 * One scope's complete LOD runtime: the CPU {@link LodSession} plus the GPU resources and compute executor
 * created lazily on the render thread against the game's existing Vulkan device.
 *
 * <p>Thread contract: everything except {@link #session()}, {@link #generation()} and {@link #isGpuReady()}
 * is render-thread only. Teardown order is enforced by {@link #close()}: GPU resources are queued for
 * fence-gated destruction first, then storage is closed on the I/O executor (releasing the scope lock),
 * without any queue/device wait.
 *
 * <p>Per-frame order (matches the draw hook): {@code beginFrame → beginCommands → recordUploads →
 * executor.recordFrame(... nodeInputs(), takeVisibilityOps(), requestQueueSize() ...) → submitCommands →
 * afterSubmit(slot)}; after a slot retires, feed its host-visible queues to {@link #onTraversalRequests}
 * and {@link #onCleanerOutput}.
 */
public final class LodRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    public static final int FRAMES_IN_FLIGHT = 3;
    /** Upstream {@code MAX_REQUEST_QUEUE_SIZE}. */
    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    /** Upstream mesh backlog at which request throttling reaches zero. */
    private static final double TARGET_TASK_COUNT = 4000;

    private final LodSession session;
    private final IntArrayList visibilityBacklog = new IntArrayList();

    private GameVulkan.Snapshot game;
    private LodGpuBuffers frameBuffers;
    private LodNodeGpuBuffers nodeBuffers;
    private LodComputeExecutor executor;
    private volatile boolean gpuReady;
    private boolean closed;

    public LodRuntime(LodSession session) {
        this.session = session;
    }

    public LodSession session() {
        return session;
    }

    /**
     * Per-scope model bakery (render thread): call {@code tick()} then {@code acquire()} each frame; a
     * changed {@code epoch()} means model ids were reassigned and geometry must be re-ingested.
     */
    public ModelProvider models() {
        return session.models();
    }

    /** Unique per scope session; changes when the storage/world scope is replaced. */
    public long generation() {
        return session.generation();
    }

    public boolean isGpuReady() {
        return gpuReady;
    }

    /**
     * Creates the GPU buffers and compute executor on first use. Throws if a different device is supplied
     * later or when the device lacks the core compute requirements; on failure nothing is left allocated.
     */
    public void ensureGpu(GameVulkan.Snapshot snapshot, boolean reverseZ) {
        if (closed) {
            throw new IllegalStateException("LOD runtime is closed");
        }
        if (gpuReady) {
            if (snapshot.device().address() != game.device().address()) {
                throw new IllegalStateException("[JBP-LOD] Vulkan device changed; recreate the LOD runtime");
            }
            return;
        }
        DeviceCaps caps = DeviceCaps.inspect(snapshot);
        caps.requireCore();
        LodComputeExecutor createdExecutor = null;
        LodGpuBuffers createdFrames = null;
        LodNodeGpuBuffers createdNodes = null;
        try {
            createdExecutor = LodComputeExecutor.create(snapshot, caps, new ShaderCompiler(), FRAMES_IN_FLIGHT,
                    LodSession.SECTION_CAPACITY, LodSession.NODE_CAPACITY, reverseZ);
            createdFrames = new LodGpuBuffers(snapshot, FRAMES_IN_FLIGHT, LodSession.SECTION_CAPACITY);
            createdNodes = new LodNodeGpuBuffers(snapshot, LodSession.NODE_CAPACITY, LodSession.SECTION_CAPACITY,
                    LodSession.GEOMETRY_CAPACITY_BYTES);
        } catch (RuntimeException | Error failure) {
            //Nothing was submitted yet, so immediate destruction is safe
            if (createdFrames != null) createdFrames.close();
            if (createdExecutor != null) createdExecutor.close();
            if (createdNodes != null) createdNodes.retireAndClose(snapshot);
            throw failure;
        }
        this.game = snapshot;
        this.executor = createdExecutor;
        this.frameBuffers = createdFrames;
        this.nodeBuffers = createdNodes;
        this.gpuReady = true;
    }

    public LodGpuBuffers frameBuffers() {
        requireGpu();
        return frameBuffers;
    }

    public LodComputeExecutor executor() {
        requireGpu();
        return executor;
    }

    /** Slot-addressed geometry/metadata/node/top-level buffers. */
    public LodNodeGpuBuffers nodeBuffers() {
        requireGpu();
        return nodeBuffers;
    }

    /** Alias of {@link #nodeBuffers()}: the section source buffers bound by the draw pass. */
    public LodNodeGpuBuffers sectionBuffers() {
        return nodeBuffers();
    }

    /** Executor inputs for the node/top-level buffers; valid after {@link #recordUploads} this frame. */
    public LodComputeExecutor.NodeInputs nodeInputs() {
        requireGpu();
        return new LodComputeExecutor.NodeInputs(nodeBuffers.nodes(), nodeBuffers.topLevelIds(),
                nodeBuffers.topLevelCount());
    }

    /** Highest allocated node id for the cleaner's {@code cleanerMaxNodeId}. */
    public int cleanerMaxNodeId() {
        requireGpu();
        return Math.max(0, nodeBuffers.maxNodeId());
    }

    //==================================================================================================================
    // Per-frame

    /**
     * Takes the changes the node worker has published since the previous call (or null) without blocking,
     * remembers its visibility-reset/clear operations for {@link #takeVisibilityOps()}, and returns the
     * delta for {@code nodeBuffers().recordUploads}. Only call after a frame slot was acquired.
     */
    public SectionDelta pollDelta() {
        SectionDelta delta = session.nodes().poll();
        if (delta != null && !delta.cleanerOps().isEmpty()) {
            visibilityBacklog.addAll(delta.cleanerOps());
        }
        return delta;
    }

    /**
     * Polls the node worker and records all buffer uploads into {@code commandBuffer}. Returns whether any
     * command was recorded. Equivalent to {@code nodeBuffers().recordUploads(game, cb, pollDelta())}.
     */
    public boolean recordUploads(VkCommandBuffer commandBuffer) {
        requireGpu();
        return nodeBuffers.recordUploads(game, commandBuffer, pollDelta());
    }

    /**
     * Registers this frame's staging buffers with the slot; call after
     * {@code frameBuffers().submitCommands(slot)}.
     */
    public void afterSubmit(LodFrameBuffers slot) {
        requireGpu();
        AutoCloseable staging = nodeBuffers.detachPendingUploads();
        if (staging != null) {
            slot.retireAfterUse(staging);
        }
    }

    /**
     * Visibility operations for {@code FrameInputs.visibilityOps}: at most
     * {@link LodComputeExecutor#MAX_VISIBILITY_OPS} per frame, the remainder stays queued. Encoding:
     * {@code id | 1<<31} = set visibility to the current frame, plain id = clear.
     */
    public int[] takeVisibilityOps() {
        int count = Math.min(visibilityBacklog.size(), LodComputeExecutor.MAX_VISIBILITY_OPS);
        if (count == 0) {
            return new int[0];
        }
        int[] ops = new int[count];
        visibilityBacklog.getElements(0, ops, 0, count);
        visibilityBacklog.removeElements(0, count);
        return ops;
    }

    /** Operations still waiting for later frames. */
    public int pendingVisibilityOps() {
        return visibilityBacklog.size();
    }

    /** Render distance in 512-block top-level sections for {@code FrameInputs.renderDistanceSections}. */
    public int renderDistanceSections() {
        return session.renderDistanceSections();
    }

    /**
     * Traversal request budget for {@code FrameInputs.requestQueueSize}: upstream's squared falloff
     * against the outstanding mesh backlog, clamped to {@code 0..MAX_REQUEST_QUEUE_SIZE}.
     */
    public int requestQueueSize() {
        double fillness = Math.max(0, (TARGET_TASK_COUNT - session.sections().pendingTaskCount()) / TARGET_TASK_COUNT);
        fillness = fillness * fillness;
        int size = (int) Math.ceil(fillness * MAX_REQUEST_QUEUE_SIZE);
        return Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, size));
    }

    /** Feeds the camera position (blocks) to the top-level node tracker. */
    public void updateCamera(double x, double z) {
        session.updateCamera(x, z);
    }

    //==================================================================================================================
    // GPU result decoding (call only after the producing slot retired)

    /**
     * Decodes a retired slot's traversal request queue ({@code requestQueue()} mapped bytes):
     * word0 = count (clamped to 50), then {@code uvec2} positions at byte {@code 8 + 8*i}, high word first.
     */
    public void onTraversalRequests(ByteBuffer mapped) {
        ByteBuffer in = mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int count = in.getInt(0);
        if (count < 0 || count > 50_000) {
            LOGGER.error("[JBP-LOD] Traversal request count has unexpected value {}; ignoring batch", count);
            return;
        }
        count = Math.min(count, MAX_REQUEST_QUEUE_SIZE);
        if (count == 0) {
            return;
        }
        long[] positions = new long[count];
        for (int i = 0; i < count; i++) {
            positions[i] = decodePosition(in, 8 + 8 * i);
        }
        session.nodes().submitRequestBatch(positions);
    }

    /**
     * Decodes a retired slot's cleaner output ({@code executor.cleanerOutput(slot)} mapped bytes): 256
     * {@code uvec2} node positions at {@link LodComputeExecutor#CLEANER_RESULT_OFFSET}.
     */
    public void onCleanerOutput(ByteBuffer mapped) {
        ByteBuffer in = mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = Math.toIntExact(LodComputeExecutor.CLEANER_RESULT_OFFSET);
        long[] positions = new long[LodComputeExecutor.CLEANER_OUTPUT_COUNT];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = decodePosition(in, base + 8 * i);
        }
        session.nodes().submitRemoveBatch(positions);
    }

    private static long decodePosition(ByteBuffer in, int offset) {
        return (((long) in.getInt(offset)) << 32) | Integer.toUnsignedLong(in.getInt(offset + 4));
    }

    //==================================================================================================================

    /**
     * Fence-gated GPU teardown followed by asynchronous storage/scope release. Returns the future that
     * completes when the scope lock is released. Idempotent.
     */
    public CompletableFuture<Void> close() {
        if (closed) {
            return session.closeAsync();
        }
        closed = true;
        if (gpuReady) {
            gpuReady = false;
            try {
                frameBuffers.close();
                GpuRetireQueue.retire(GpuCompletion.submitMarker(game), executor);
                nodeBuffers.retireAndClose(game);
            } catch (RuntimeException | Error failure) {
                LOGGER.error("[JBP-LOD] GPU teardown failed; continuing with storage release.", failure);
            }
            frameBuffers = null;
            executor = null;
            nodeBuffers = null;
        }
        try {
            session.models().close();
        } catch (RuntimeException | Error failure) {
            LOGGER.error("[JBP-LOD] Model bakery teardown failed; continuing with storage release.", failure);
        }
        return session.closeAsync();
    }

    private void requireGpu() {
        if (!gpuReady) {
            throw new IllegalStateException("[JBP-LOD] GPU resources are not created; call ensureGpu on the render thread");
        }
    }
}
