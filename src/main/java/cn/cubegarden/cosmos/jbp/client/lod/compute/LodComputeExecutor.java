// Derived from MCRcortex/voxy 263 (https://github.com/MCRcortex/voxy):
//   me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser (doTraversal/traverseInternal:
//     render-list clear, queue-metadata seeding, MAX_ITERATIONS ping-pong dispatches, request queue reset)
//   me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner (tick/updateIds: sorter + result transformer +
//     batch visibility set)
//   me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer (buildDrawCalls: prep, occlusion
//     test raster, cmdgen, prefix sum, translucent build; renderOpaque/renderTranslucent/renderTemporal ranges)
// JBP-LOD adaptation: the OpenGL glMemoryBarrier/bind sequence becomes Vulkan compute dispatches recorded into a
// caller-provided game-compatible command buffer with range-scoped Synchronization2 buffer barriers.
package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.data.LodSceneUniform;
import cn.cubegarden.cosmos.jbp.client.lod.data.TraversalSceneUniform;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.caps.DeviceCaps;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.COMPUTE;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.DRAW_INDIRECT;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.FRAGMENT;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.HOST;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.HOST_READ;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.HOST_WRITE;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.INDIRECT_READ;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.SSBO_READ;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.SSBO_RW;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.SSBO_WRITE;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.TRANSFER;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.TRANSFER_RW;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.TRANSFER_WRITE;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.UNIFORM_READ;
import static cn.cubegarden.cosmos.jbp.client.lod.compute.SyncBatch.VERTEX;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

/**
 * Records the complete V3 compute pass graph for one frame slot: visibility maintenance, hierarchical
 * traversal, section preparation, occlusion-test visibility, command generation, translucent sorting and the
 * optional node cleaner. All work is recorded into the caller's command buffer (outside any render scope);
 * nothing here submits, waits, or reads GPU results on the CPU.
 */
public final class LodComputeExecutor implements AutoCloseable {
    public static final int MAX_VISIBILITY_OPS = LodComputeLayout.MAX_VISIBILITY_OPS;
    public static final int CLEANER_OUTPUT_COUNT = LodComputeLayout.CLEANER_OUTPUT_COUNT;
    /** Byte offset of the 256 raw {@code uvec2} node positions inside {@link #cleanerOutput(int)}. */
    public static final long CLEANER_RESULT_OFFSET = LodComputeLayout.CLEANER_SCRATCH_BYTES;
    /** First draw-control cull command (VkDrawIndexedIndirectCommand {36, sectionCount, 786432, 0, 0}). */
    public static final long CULL_COMMAND_OFFSET = LodComputeLayout.DRAW_CONTROL_CULL_COMMAND_OFFSET;

    private static final int VENDOR_AMD = 0x1002;
    private static final int BINDING_SLOTS = 16;
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod-compute");
    /** Process-wide once-only flag for the first-frame compute diagnostic (render thread only). */
    private static boolean computeFrameLogged;

    private final int framesInFlight;
    private final int sectionCapacity;
    private final int nodeCapacity;
    private final boolean reverseZ;
    private final Bound traversal;
    private final Bound prep;
    private final Bound cmdgen;
    private final Bound prefix;
    private final Bound translucent;
    private final Bound sorter;
    private final Bound transformer;
    private final Bound visibilitySet;
    private final GpuBuffer visibility;
    private final GpuBuffer sectionVisibility;
    private final GpuBuffer[] queueMetadata;
    private final GpuBuffer[] visibilityOps;
    private final GpuBuffer[] cleanerOutput;
    private boolean visibilityInitialized;
    private boolean closed;

    /**
     * @param framesInFlight  must equal {@code LodGpuBuffers.framesInFlight()}
     * @param sectionCapacity the frame slot section capacity ({@code LodFrameBuffers.sectionCapacity()})
     * @param nodeCapacity    node-buffer element count (upstream maxNodeCount); sizes persistent visibility
     * @param reverseZ        whether the game depth convention is reverse-Z (shader depth defines, frustum)
     */
    public static LodComputeExecutor create(GameVulkan.Snapshot game, DeviceCaps caps, ShaderCompiler compiler,
                                            int framesInFlight, int sectionCapacity, int nodeCapacity,
                                            boolean reverseZ) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(caps, "caps");
        Objects.requireNonNull(compiler, "compiler");
        if (framesInFlight < 1 || framesInFlight > 4) {
            throw new IllegalArgumentException("frames in flight must be 1..4");
        }
        if (sectionCapacity <= 0 || sectionCapacity > LodFrameBuffers.DEFAULT_SECTION_CAPACITY) {
            throw new IllegalArgumentException("section capacity must be 1.." + LodFrameBuffers.DEFAULT_SECTION_CAPACITY);
        }
        if (nodeCapacity < 2) {
            throw new IllegalArgumentException("node capacity must be at least 2");
        }
        List<AutoCloseable> created = new ArrayList<>();
        try {
            return new LodComputeExecutor(game, caps, compiler, framesInFlight, sectionCapacity, nodeCapacity,
                    reverseZ, created);
        } catch (RuntimeException | Error failure) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try {
                    created.get(i).close();
                } catch (Exception suppressed) {
                    failure.addSuppressed(suppressed);
                }
            }
            throw failure;
        }
    }

    private LodComputeExecutor(GameVulkan.Snapshot game, DeviceCaps caps, ShaderCompiler compiler,
                               int framesInFlight, int sectionCapacity, int nodeCapacity, boolean reverseZ,
                               List<AutoCloseable> created) {
        this.framesInFlight = framesInFlight;
        this.sectionCapacity = sectionCapacity;
        this.nodeCapacity = nodeCapacity;
        this.reverseZ = reverseZ;

        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        long nodeVisibilityBytes = (long) nodeCapacity * Integer.BYTES;
        long sectionVisibilityBytes = (long) sectionCapacity * Integer.BYTES;
        visibility = track(created, GpuBuffer.allocate(game, nodeVisibilityBytes, storage, false));
        sectionVisibility = track(created, GpuBuffer.allocate(game, sectionVisibilityBytes,
                storage | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false));
        queueMetadata = new GpuBuffer[framesInFlight];
        visibilityOps = new GpuBuffer[framesInFlight];
        cleanerOutput = new GpuBuffer[framesInFlight];
        for (int i = 0; i < framesInFlight; i++) {
            queueMetadata[i] = track(created, GpuBuffer.allocate(game, LodComputeLayout.QUEUE_METADATA_BYTES,
                    storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, true));
            visibilityOps[i] = track(created, GpuBuffer.allocate(game,
                    (long) MAX_VISIBILITY_OPS * Integer.BYTES, storage, true));
            cleanerOutput[i] = track(created, GpuBuffer.allocate(game, LodComputeLayout.CLEANER_OUTPUT_BYTES,
                    storage, true));
        }

        Map<String, String> traversalDefines = new LinkedHashMap<>();
        traversalDefines.put("MAX_ITERATIONS", Integer.toString(LodComputeLayout.MAX_ITERATIONS));
        traversalDefines.put("LOCAL_SIZE_BITS", Integer.toString(LodComputeLayout.LOCAL_WORK_SIZE_BITS));
        traversalDefines.put("MAX_REQUEST_QUEUE_SIZE", Integer.toString(LodComputeLayout.MAX_REQUEST_QUEUE_SIZE));
        traversalDefines.put("HIZ_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_HIZ_BINDING));
        traversalDefines.put("SCENE_UNIFORM_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_SCENE_UNIFORM_BINDING));
        traversalDefines.put("REQUEST_QUEUE_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_REQUEST_QUEUE_BINDING));
        traversalDefines.put("RENDER_QUEUE_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_RENDER_QUEUE_BINDING));
        traversalDefines.put("NODE_DATA_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_NODE_DATA_BINDING));
        traversalDefines.put("NODE_QUEUE_META_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_NODE_QUEUE_META_BINDING));
        traversalDefines.put("NODE_QUEUE_SOURCE_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_NODE_QUEUE_SOURCE_BINDING));
        traversalDefines.put("NODE_QUEUE_SINK_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_NODE_QUEUE_SINK_BINDING));
        traversalDefines.put("RENDER_TRACKER_BINDING", Integer.toString(LodComputeLayout.TRAVERSAL_RENDER_TRACKER_BINDING));
        if (reverseZ) {
            traversalDefines.put("USE_REVERSE_Z", "1");
        }
        traversal = track(created, new Bound(new ComputePipeline(game, compiler,
                "lod/hierarchical/traversal_dev.comp", traversalDefines, List.of(
                new ComputePipeline.Binding(LodComputeLayout.TRAVERSAL_HIZ_BINDING, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new ComputePipeline.Binding(LodComputeLayout.TRAVERSAL_SCENE_UNIFORM_BINDING, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
                ssbo(LodComputeLayout.TRAVERSAL_REQUEST_QUEUE_BINDING),
                ssbo(LodComputeLayout.TRAVERSAL_RENDER_QUEUE_BINDING),
                ssbo(LodComputeLayout.TRAVERSAL_NODE_DATA_BINDING),
                ssbo(LodComputeLayout.TRAVERSAL_NODE_QUEUE_META_BINDING),
                ssbo(LodComputeLayout.TRAVERSAL_NODE_QUEUE_SOURCE_BINDING),
                ssbo(LodComputeLayout.TRAVERSAL_NODE_QUEUE_SINK_BINDING),
                ssbo(LodComputeLayout.TRAVERSAL_RENDER_TRACKER_BINDING)),
                framesInFlight, LodComputeLayout.MAX_ITERATIONS, Integer.BYTES), framesInFlight,
                LodComputeLayout.MAX_ITERATIONS));

        ComputePipeline.Binding sceneUniform = new ComputePipeline.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        prep = track(created, new Bound(new ComputePipeline(game, compiler, "lod/gl46/prep.comp", Map.of(),
                List.of(sceneUniform, ssbo(LodComputeLayout.PREP_DRAW_COUNT_BINDING),
                        ssbo(LodComputeLayout.PREP_LOOKUP_BINDING)), framesInFlight, 0), framesInFlight, 1));

        Map<String, String> cmdgenDefines = new LinkedHashMap<>();
        cmdgenDefines.put("TRANSLUCENT_WRITE_BASE", Integer.toString(LodComputeLayout.TRANSLUCENT_WRITE_BASE));
        cmdgenDefines.put("TEMPORAL_OFFSET", Integer.toString(LodComputeLayout.TEMPORAL_OFFSET));
        cmdgenDefines.put("TRANSLUCENT_OFFSET", Integer.toString(LodComputeLayout.TRANSLUCENT_OFFSET));
        cmdgenDefines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", Integer.toString(LodComputeLayout.CMDGEN_TRANSLUCENT_BINDING));
        cmdgenDefines.put("OPAQUE_CAPACITY", Integer.toString(LodFrameBuffers.OPAQUE_COMMAND_CAPACITY));
        cmdgenDefines.put("TRANSLUCENT_CAPACITY", Integer.toString(LodFrameBuffers.TRANSLUCENT_COMMAND_CAPACITY));
        cmdgenDefines.put("TEMPORAL_CAPACITY", Integer.toString(LodFrameBuffers.TEMPORAL_COMMAND_CAPACITY));
        cmdgen = track(created, new Bound(new ComputePipeline(game, compiler, "lod/gl46/cmdgen.comp", cmdgenDefines,
                List.of(sceneUniform, ssbo(LodComputeLayout.CMDGEN_DRAW_BINDING),
                        ssbo(LodComputeLayout.CMDGEN_DRAW_COUNT_BINDING),
                        ssbo(LodComputeLayout.CMDGEN_METADATA_BINDING),
                        ssbo(LodComputeLayout.CMDGEN_VISIBILITY_BINDING),
                        ssbo(LodComputeLayout.CMDGEN_LOOKUP_BINDING),
                        ssbo(LodComputeLayout.CMDGEN_POSITION_BINDING),
                        ssbo(LodComputeLayout.CMDGEN_TRANSLUCENT_BINDING)), framesInFlight, 0),
                framesInFlight, 1));

        Map<String, String> prefixDefines = new LinkedHashMap<>();
        prefixDefines.put("IO_BUFFER", "0");
        boolean computeStage = (caps.subgroupSupportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
        if (LodComputeLayout.useSubgroupPrefixSum(caps.subgroupArithmetic(), computeStage,
                caps.subgroupSize(), caps.vendorId())) {
            prefixDefines.put("USE_SUBGROUP", "1");
            if (caps.vendorId() == VENDOR_AMD
                    && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                prefixDefines.put("IS_WINDOWS", "1");
            }
        }
        prefix = track(created, new Bound(new ComputePipeline(game, compiler, "util/prefixsum/inital3.comp",
                prefixDefines, List.of(ssbo(0)), framesInFlight, 0), framesInFlight, 1));

        Map<String, String> translucentDefines = new LinkedHashMap<>();
        translucentDefines.put("TRANSLUCENT_WRITE_BASE", Integer.toString(LodComputeLayout.TRANSLUCENT_WRITE_BASE));
        translucentDefines.put("TRANSLUCENT_OFFSET", Integer.toString(LodComputeLayout.TRANSLUCENT_OFFSET));
        translucentDefines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", Integer.toString(LodComputeLayout.TRANSLUCENT_SCRATCH_BINDING));
        translucentDefines.put("TRANSLUCENT_CAPACITY", Integer.toString(LodFrameBuffers.TRANSLUCENT_COMMAND_CAPACITY));
        translucent = track(created, new Bound(new ComputePipeline(game, compiler, "lod/gl46/buildtranslucents.comp",
                translucentDefines, List.of(sceneUniform, ssbo(LodComputeLayout.TRANSLUCENT_DRAW_BINDING),
                ssbo(LodComputeLayout.TRANSLUCENT_DRAW_COUNT_BINDING),
                ssbo(LodComputeLayout.TRANSLUCENT_METADATA_BINDING),
                ssbo(LodComputeLayout.TRANSLUCENT_LOOKUP_BINDING),
                ssbo(LodComputeLayout.TRANSLUCENT_SCRATCH_BINDING)), framesInFlight, 0), framesInFlight, 1));

        Map<String, String> sorterDefines = new LinkedHashMap<>();
        sorterDefines.put("WORK_SIZE", Integer.toString(LodComputeLayout.CLEANER_WORK_SIZE));
        sorterDefines.put("ELEMS_PER_THREAD", Integer.toString(LodComputeLayout.CLEANER_ELEMS_PER_THREAD));
        sorterDefines.put("OUTPUT_SIZE", Integer.toString(LodComputeLayout.CLEANER_OUTPUT_COUNT));
        sorterDefines.put("VISIBILITY_BUFFER_BINDING", Integer.toString(LodComputeLayout.SORT_VISIBILITY_BINDING));
        sorterDefines.put("OUTPUT_BUFFER_BINDING", Integer.toString(LodComputeLayout.SORT_OUTPUT_BINDING));
        sorterDefines.put("NODE_DATA_BINDING", Integer.toString(LodComputeLayout.SORT_NODE_BINDING));
        sorter = track(created, new Bound(new ComputePipeline(game, compiler,
                "lod/hierarchical/cleaner/sort_visibility.comp", sorterDefines,
                List.of(ssbo(LodComputeLayout.SORT_VISIBILITY_BINDING), ssbo(LodComputeLayout.SORT_OUTPUT_BINDING),
                        ssbo(LodComputeLayout.SORT_NODE_BINDING)), framesInFlight, 0), framesInFlight, 1));

        Map<String, String> transformDefines = new LinkedHashMap<>();
        transformDefines.put("OUTPUT_SIZE", Integer.toString(LodComputeLayout.CLEANER_OUTPUT_COUNT));
        transformDefines.put("MIN_ID_BUFFER_BINDING", Integer.toString(LodComputeLayout.TRANSFORM_MIN_ID_BINDING));
        transformDefines.put("NODE_BUFFER_BINDING", Integer.toString(LodComputeLayout.TRANSFORM_NODE_BINDING));
        transformDefines.put("OUTPUT_BUFFER_BINDING", Integer.toString(LodComputeLayout.TRANSFORM_OUTPUT_BINDING));
        transformDefines.put("VISIBILITY_BUFFER_BINDING", Integer.toString(LodComputeLayout.TRANSFORM_VISIBILITY_BINDING));
        transformer = track(created, new Bound(new ComputePipeline(game, compiler,
                "lod/hierarchical/cleaner/result_transformer.comp", transformDefines,
                List.of(ssbo(LodComputeLayout.TRANSFORM_MIN_ID_BINDING), ssbo(LodComputeLayout.TRANSFORM_NODE_BINDING),
                        ssbo(LodComputeLayout.TRANSFORM_OUTPUT_BINDING),
                        ssbo(LodComputeLayout.TRANSFORM_VISIBILITY_BINDING)), framesInFlight, Integer.BYTES),
                framesInFlight, 1));

        Map<String, String> setDefines = new LinkedHashMap<>();
        setDefines.put("VISIBILITY_BUFFER_BINDING", Integer.toString(LodComputeLayout.VISIBILITY_SET_VISIBILITY_BINDING));
        setDefines.put("LIST_BUFFER_BINDING", Integer.toString(LodComputeLayout.VISIBILITY_SET_LIST_BINDING));
        visibilitySet = track(created, new Bound(new ComputePipeline(game, compiler,
                "lod/hierarchical/cleaner/batch_visibility_set.comp", setDefines,
                List.of(ssbo(LodComputeLayout.VISIBILITY_SET_VISIBILITY_BINDING),
                        ssbo(LodComputeLayout.VISIBILITY_SET_LIST_BINDING)), framesInFlight, 2 * Integer.BYTES),
                framesInFlight, 1));
    }

    /** Node-indexed visibility words (traversal render tracker, visibility ops, cleaner); initially 0xFFFFFFFF. */
    public GpuBuffer nodeVisibility() {
        ensureOpen();
        return visibility;
    }

    /** Section-indexed visibility words (cull raster writes, command generation reads); initially 0xFFFFFFFF. */
    public GpuBuffer sectionVisibility() {
        ensureOpen();
        return sectionVisibility;
    }

    /**
     * Cleaner result for {@code slotIndex}: bytes [0, 1024) are scratch minimum-visibility node ids and
     * [1024, 3072) are 256 {@code uvec2} raw node positions ({@code 0xFFFFFFFF,0xFFFFFFFF} means none).
     * Host reads are valid only after the slot's submission has retired (compute-to-host barrier recorded).
     */
    public GpuBuffer cleanerOutput(int slotIndex) {
        ensureOpen();
        return cleanerOutput[checkSlot(slotIndex)];
    }

    /** Frame-slot queue metadata (indirect dispatch arguments per traversal layer), for diagnostics. */
    public GpuBuffer queueMetadata(int slotIndex) {
        ensureOpen();
        return queueMetadata[checkSlot(slotIndex)];
    }

    /**
     * Records the full pass graph. The caller owns {@code commandBuffer} (which must be the active recording
     * command buffer of {@code slot}), has already recorded node/section uploads into it, and submits it.
     *
     * <p>Order: HiZ hook, uniforms/clears/host barriers, visibility ops, traversal layers, post-traversal hook,
     * prep, cull-visibility hook, command generation, prefix scan, translucent build, optional cleaner, final
     * producer-to-consumer barriers for draw (indirect/vertex) and for host readback of request/cleaner data.
     */
    public void recordFrame(VkCommandBuffer commandBuffer, LodFrameBuffers slot, FrameInputs in) {
        ensureOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(in, "in");
        if (commandBuffer.address() != slot.commandBuffer().address()) {
            throw new IllegalArgumentException("compute graph must be recorded into the slot's recording command buffer");
        }
        if (slot.sectionCapacity() != sectionCapacity) {
            throw new IllegalArgumentException("frame slot section capacity differs from the executor's");
        }
        int frame = checkSlot(slot.slotIndex());
        int frameId = LodComputeLayout.packFrameId(in.frameId());
        NodeInputs nodes = in.nodes();
        if (nodes.topLevelCount() > sectionCapacity
                || (long) nodes.topLevelCount() * Integer.BYTES > nodes.topLevelIds().capacity()) {
            throw new IllegalArgumentException("top-level node count exceeds the queue or its id buffer");
        }
        if (nodes.nodes().capacity() < (long) nodeCapacity * 16) {
            throw new IllegalArgumentException("node buffer is smaller than nodeCapacity * 16 bytes");
        }
        int[] ops = in.visibilityOps();
        int opCount = ops == null ? 0 : ops.length;
        Hooks hooks = in.hooks();

        long dispatchesBefore = ComputePipeline.recordedDispatches();
        HiZInputs hiz = Objects.requireNonNull(hooks.beforeTraversal(commandBuffer, slot),
                "Hooks.beforeTraversal must return the HiZ descriptor inputs");

        writeUniforms(slot, in, hiz, frameId);
        writeQueueMetadata(frame, nodes.topLevelCount());
        if (opCount > 0) {
            writeVisibilityOps(frame, ops);
        }
        writeDescriptors(frame, slot, in, hiz);

        recordFrameStart(commandBuffer, slot, frame, opCount);
        if (opCount > 0) {
            recordVisibilityOps(commandBuffer, frame, opCount, frameId);
        }
        recordTraversal(commandBuffer, slot, nodes.nodes(), frame, nodes.topLevelCount());
        hooks.afterTraversal(commandBuffer, slot);

        recordPrep(commandBuffer, slot, frame);
        hooks.drawCullVisibility(commandBuffer, slot);
        recordCommandGeneration(commandBuffer, slot, frame);

        boolean cleaned = in.runCleaner();
        if (cleaned) {
            recordCleaner(commandBuffer, nodes.nodes(), frame, frameId, in.cleanerMaxNodeId());
        }
        recordFinalBarriers(commandBuffer, slot, nodes.nodes(), frame, cleaned);
        visibilityInitialized = true;
        if (!computeFrameLogged) {
            computeFrameLogged = true;
            LOGGER.info("[JBP-LOD] V4 first compute frame recorded: dispatches={} cleaner={}",
                    ComputePipeline.recordedDispatches() - dispatchesBefore, cleaned);
        }
    }

    // ---------------------------------------------------------------- host-side inputs

    private void writeUniforms(LodFrameBuffers slot, FrameInputs in, HiZInputs hiz, int frameId) {
        int sx = LodComputeLayout.cameraSection(in.cameraX());
        int sy = LodComputeLayout.cameraSection(in.cameraY());
        int sz = LodComputeLayout.cameraSection(in.cameraZ());
        float fx = LodComputeLayout.cameraSubSection(in.cameraX());
        float fy = LodComputeLayout.cameraSubSection(in.cameraY());
        float fz = LodComputeLayout.cameraSubSection(in.cameraZ());
        slot.writeSceneUniform(new LodSceneUniform(LodComputeLayout.translateMvp(in.mvp(), fx, fy, fz),
                sx, sy, sz, frameId, fx, fy, fz));
        slot.writeTraversalSceneUniform(new TraversalSceneUniform(in.mvp(), sx, sy, sz, hiz.packedSize(),
                fx, fy, fz,
                LodComputeLayout.minimumScreenSpaceSize(in.subdivisionSize(), in.viewportWidth(), in.viewportHeight()),
                LodComputeLayout.frustumPlanes(in.mvp(), reverseZ), sectionCapacity, frameId,
                in.requestQueueSize(), LodComputeLayout.renderDistanceSquared(in.renderDistanceSections())));
    }

    private void writeQueueMetadata(int frame, int topLevelCount) {
        GpuBuffer metadata = queueMetadata[frame];
        ByteBuffer bytes = metadata.mappedBytes().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = LodComputeLayout.traversalQueueMetadata(topLevelCount);
        for (int i = 0; i < words.length; i++) {
            bytes.putInt(i * Integer.BYTES, words[i]);
        }
        metadata.flush(0, LodComputeLayout.QUEUE_METADATA_BYTES);
    }

    private void writeVisibilityOps(int frame, int[] ops) {
        GpuBuffer buffer = visibilityOps[frame];
        ByteBuffer bytes = buffer.mappedBytes().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ops.length; i++) {
            bytes.putInt(i * Integer.BYTES, ops[i]);
        }
        buffer.flush(0, (long) ops.length * Integer.BYTES);
    }

    // ---------------------------------------------------------------- descriptors

    private void writeDescriptors(int frame, LodFrameBuffers slot, FrameInputs in, HiZInputs hiz) {
        GpuBuffer nodes = in.nodes().nodes();
        GpuBuffer topLevel = in.nodes().topLevelIds();
        GpuBuffer queueA = slot.nodeQueueA();
        GpuBuffer queueB = slot.nodeQueueB();
        GpuBuffer lookup = slot.indirectSectionLookup();
        for (int iteration = 0; iteration < LodComputeLayout.MAX_ITERATIONS; iteration++) {
            int set = traversal.pipeline.descriptorSetIndex(frame, iteration);
            traversal.sampler(set, LodComputeLayout.TRAVERSAL_HIZ_BINDING, hiz.sampler(), hiz.imageView(),
                    hiz.imageLayout());
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_SCENE_UNIFORM_BINDING, slot.traversalUniform(),
                    0, TraversalSceneUniform.BYTE_SIZE);
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_REQUEST_QUEUE_BINDING, slot.requestQueue());
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_RENDER_QUEUE_BINDING, lookup);
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_NODE_DATA_BINDING, nodes);
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_NODE_QUEUE_META_BINDING, queueMetadata[frame]);
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_NODE_QUEUE_SOURCE_BINDING,
                    queue(LodComputeLayout.traversalSource(iteration), topLevel, queueA, queueB));
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_NODE_QUEUE_SINK_BINDING,
                    queue(LodComputeLayout.traversalSink(iteration), topLevel, queueA, queueB));
            traversal.buffer(set, LodComputeLayout.TRAVERSAL_RENDER_TRACKER_BINDING, visibility);
        }

        int set = prep.pipeline.descriptorSetIndex(frame, 0);
        prep.buffer(set, 0, slot.sceneUniform(), 0, LodSceneUniform.BYTE_SIZE);
        prep.buffer(set, LodComputeLayout.PREP_DRAW_COUNT_BINDING, slot.drawControl());
        prep.buffer(set, LodComputeLayout.PREP_LOOKUP_BINDING, lookup);

        set = cmdgen.pipeline.descriptorSetIndex(frame, 0);
        cmdgen.buffer(set, 0, slot.sceneUniform(), 0, LodSceneUniform.BYTE_SIZE);
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_DRAW_BINDING, slot.drawCommands());
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_DRAW_COUNT_BINDING, slot.drawControl());
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_METADATA_BINDING, in.sectionMetadata());
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_VISIBILITY_BINDING, sectionVisibility);
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_LOOKUP_BINDING, lookup);
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_POSITION_BINDING, slot.positionScratch());
        cmdgen.buffer(set, LodComputeLayout.CMDGEN_TRANSLUCENT_BINDING, slot.translucentScratch());

        set = prefix.pipeline.descriptorSetIndex(frame, 0);
        prefix.buffer(set, 0, slot.translucentScratch(), 0, LodComputeLayout.TRANSLUCENT_BIN_BYTES);

        set = translucent.pipeline.descriptorSetIndex(frame, 0);
        translucent.buffer(set, 0, slot.sceneUniform(), 0, LodSceneUniform.BYTE_SIZE);
        translucent.buffer(set, LodComputeLayout.TRANSLUCENT_DRAW_BINDING, slot.drawCommands());
        translucent.buffer(set, LodComputeLayout.TRANSLUCENT_DRAW_COUNT_BINDING, slot.drawControl());
        translucent.buffer(set, LodComputeLayout.TRANSLUCENT_METADATA_BINDING, in.sectionMetadata());
        translucent.buffer(set, LodComputeLayout.TRANSLUCENT_LOOKUP_BINDING, lookup);
        translucent.buffer(set, LodComputeLayout.TRANSLUCENT_SCRATCH_BINDING, slot.translucentScratch());

        set = visibilitySet.pipeline.descriptorSetIndex(frame, 0);
        visibilitySet.buffer(set, LodComputeLayout.VISIBILITY_SET_VISIBILITY_BINDING, visibility);
        visibilitySet.buffer(set, LodComputeLayout.VISIBILITY_SET_LIST_BINDING, visibilityOps[frame]);

        set = sorter.pipeline.descriptorSetIndex(frame, 0);
        sorter.buffer(set, LodComputeLayout.SORT_VISIBILITY_BINDING, visibility);
        sorter.buffer(set, LodComputeLayout.SORT_OUTPUT_BINDING, cleanerOutput[frame]);
        sorter.buffer(set, LodComputeLayout.SORT_NODE_BINDING, nodes);

        set = transformer.pipeline.descriptorSetIndex(frame, 0);
        transformer.buffer(set, LodComputeLayout.TRANSFORM_MIN_ID_BINDING, cleanerOutput[frame], 0,
                LodComputeLayout.CLEANER_SCRATCH_BYTES);
        transformer.buffer(set, LodComputeLayout.TRANSFORM_NODE_BINDING, nodes);
        transformer.buffer(set, LodComputeLayout.TRANSFORM_OUTPUT_BINDING, cleanerOutput[frame],
                LodComputeLayout.CLEANER_SCRATCH_BYTES, LodComputeLayout.CLEANER_RESULT_BYTES);
        transformer.buffer(set, LodComputeLayout.TRANSFORM_VISIBILITY_BINDING, visibility);
    }

    private static GpuBuffer queue(LodComputeLayout.QueueRole role, GpuBuffer topLevel, GpuBuffer a, GpuBuffer b) {
        return switch (role) {
            case TOP_LEVEL -> topLevel;
            case A -> a;
            case B -> b;
        };
    }

    // ---------------------------------------------------------------- recording

    private void recordFrameStart(VkCommandBuffer cb, LodFrameBuffers slot, int frame, int opCount) {
        GpuBuffer lookup = slot.indirectSectionLookup();
        GpuBuffer requests = slot.requestQueue();
        GpuBuffer bins = slot.translucentScratch();
        // Upstream: clear render-list counter; request queue count is reset after each consumer read;
        // distance bins are zeroed before cmdgen.
        vkCmdFillBuffer(cb, lookup.handle(), 0, Integer.BYTES, 0);
        vkCmdFillBuffer(cb, requests.handle(), 0, 2L * Integer.BYTES, 0);
        vkCmdFillBuffer(cb, bins.handle(), 0, LodComputeLayout.TRANSLUCENT_BIN_BYTES, 0);
        if (!visibilityInitialized) {
            vkCmdFillBuffer(cb, visibility.handle(), 0, visibility.capacity(), -1);
            vkCmdFillBuffer(cb, sectionVisibility.handle(), 0, sectionVisibility.capacity(), -1);
        }

        SyncBatch batch = new SyncBatch()
                .add(lookup, 0, Integer.BYTES, TRANSFER, TRANSFER_WRITE, COMPUTE, SSBO_RW)
                .add(requests, 0, 2L * Integer.BYTES, TRANSFER, TRANSFER_WRITE, COMPUTE, SSBO_RW)
                .add(bins, 0, LodComputeLayout.TRANSLUCENT_BIN_BYTES, TRANSFER, TRANSFER_WRITE, COMPUTE, SSBO_RW)
                // Node visibility is produced by the previous frame's compute writes; section visibility also by the
                // previous frame's cull fragment writes. First use gets the initial fill; later stages read/write both.
                .addWhole(visibility, TRANSFER | COMPUTE | FRAGMENT, TRANSFER_WRITE | SSBO_WRITE,
                        COMPUTE | VERTEX | FRAGMENT, SSBO_RW)
                .addWhole(sectionVisibility, TRANSFER | COMPUTE | FRAGMENT, TRANSFER_WRITE | SSBO_WRITE,
                        COMPUTE | VERTEX | FRAGMENT, SSBO_RW)
                .addWhole(queueMetadata[frame], HOST, HOST_WRITE, COMPUTE | DRAW_INDIRECT, SSBO_RW | INDIRECT_READ)
                .add(slot.traversalUniform(), 0, TraversalSceneUniform.BYTE_SIZE, HOST, HOST_WRITE, COMPUTE,
                        UNIFORM_READ)
                .add(slot.sceneUniform(), 0, LodSceneUniform.BYTE_SIZE, HOST, HOST_WRITE,
                        COMPUTE | VERTEX | FRAGMENT, UNIFORM_READ);
        if (opCount > 0) {
            batch.add(visibilityOps[frame], 0, (long) opCount * Integer.BYTES, HOST, HOST_WRITE, COMPUTE, SSBO_READ);
        }
        batch.record(cb);
    }

    private void recordVisibilityOps(VkCommandBuffer cb, int frame, int opCount, int frameId) {
        int set = visibilitySet.pipeline.descriptorSetIndex(frame, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(2 * Integer.BYTES);
            push.putInt(0, opCount).putInt(Integer.BYTES, frameId);
            visibilitySet.pipeline.dispatch(cb, set,
                    (opCount + LodComputeLayout.VISIBILITY_SET_WORK_SIZE - 1) / LodComputeLayout.VISIBILITY_SET_WORK_SIZE,
                    1, 1, push);
        }
        new SyncBatch()
                .addWhole(visibility, COMPUTE, SSBO_WRITE, COMPUTE | VERTEX | FRAGMENT, SSBO_RW)
                .record(cb);
    }

    private void recordTraversal(VkCommandBuffer cb, LodFrameBuffers slot, GpuBuffer nodes, int frame,
                                 int topLevelCount) {
        GpuBuffer lookup = slot.indirectSectionLookup();
        if (topLevelCount > 0) {
            int groups = LodComputeLayout.firstTraversalGroups(topLevelCount);
            dispatchTraversal(cb, frame, 0, groups, null);
            for (int iteration = 1; iteration < LodComputeLayout.MAX_ITERATIONS; iteration++) {
                // Queue sink/metadata/atomics of the previous layer feed the next layer (and its indirect size).
                traversalLayerBarrier(cb, slot, frame, lookup, nodes, iteration - 1);
                dispatchTraversal(cb, frame, iteration, 0, queueMetadata[frame]);
            }
            // Render list and visibility feed prep, the cull raster (vertex stage reads) and cmdgen.
            new SyncBatch()
                    .addWhole(lookup, COMPUTE, SSBO_WRITE, COMPUTE | VERTEX, SSBO_READ)
                    .addWhole(visibility, COMPUTE, SSBO_WRITE, COMPUTE | VERTEX | FRAGMENT, SSBO_RW)
                    .record(cb);
        }
    }

    private void dispatchTraversal(VkCommandBuffer cb, int frame, int iteration, int groups, GpuBuffer indirect) {
        int set = traversal.pipeline.descriptorSetIndex(frame, iteration);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(Integer.BYTES);
            push.putInt(0, iteration);
            if (indirect == null) {
                traversal.pipeline.dispatch(cb, set, groups, 1, 1, push);
            } else {
                traversal.pipeline.dispatchIndirect(cb, set, indirect,
                        LodComputeLayout.traversalDispatchOffset(iteration), push);
            }
        }
    }

    private void traversalLayerBarrier(VkCommandBuffer cb, LodFrameBuffers slot, int frame, GpuBuffer lookup,
                                       GpuBuffer nodes, int producedIteration) {
        GpuBuffer sink = queue(LodComputeLayout.traversalSink(producedIteration), null,
                slot.nodeQueueA(), slot.nodeQueueB());
        // Metadata holds the next layer's indirect dispatch arguments and atomically updated queue counters;
        // node flags (markRequested), request/render counters and visibility are updated by every layer.
        new SyncBatch()
                .addWhole(queueMetadata[frame], COMPUTE, SSBO_WRITE, COMPUTE | DRAW_INDIRECT,
                        SSBO_RW | INDIRECT_READ)
                .addWhole(sink, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_READ)
                .addWhole(nodes, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .addWhole(slot.requestQueue(), COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .addWhole(lookup, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .addWhole(visibility, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .record(cb);
    }

    private void recordPrep(VkCommandBuffer cb, LodFrameBuffers slot, int frame) {
        int set = prep.pipeline.descriptorSetIndex(frame, 0);
        prep.pipeline.dispatch(cb, set, 1, 1, 1, null);
        GpuBuffer control = slot.drawControl();
        // prep writes the dispatch args, counts and cull command that the indirect dispatch (cmdgen,
        // buildtranslucents), the cull raster's indirect draw and the later atomics consume.
        new SyncBatch()
                .addWhole(control, COMPUTE, SSBO_WRITE, COMPUTE | DRAW_INDIRECT, SSBO_RW | INDIRECT_READ)
                .record(cb);
    }

    private void recordCommandGeneration(VkCommandBuffer cb, LodFrameBuffers slot, int frame) {
        // The cull raster's fragment visibility writes must be visible to cmdgen's reads.
        new SyncBatch()
                .addWhole(sectionVisibility, COMPUTE | FRAGMENT, SSBO_WRITE, COMPUTE, SSBO_RW)
                .record(cb);

        GpuBuffer control = slot.drawControl();
        GpuBuffer bins = slot.translucentScratch();
        cmdgen.pipeline.dispatchIndirect(cb, cmdgen.pipeline.descriptorSetIndex(frame, 0), control,
                LodComputeLayout.DRAW_CONTROL_DISPATCH_OFFSET, null);
        new SyncBatch()
                .addWhole(control, COMPUTE, SSBO_WRITE, COMPUTE | DRAW_INDIRECT, SSBO_RW | INDIRECT_READ)
                .addWhole(bins, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .addWhole(slot.drawCommands(), COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .record(cb);

        prefix.pipeline.dispatch(cb, prefix.pipeline.descriptorSetIndex(frame, 0), 1, 1, 1, null);
        new SyncBatch()
                .add(bins, 0, LodComputeLayout.TRANSLUCENT_BIN_BYTES, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .record(cb);

        translucent.pipeline.dispatchIndirect(cb, translucent.pipeline.descriptorSetIndex(frame, 0), control,
                LodComputeLayout.DRAW_CONTROL_DISPATCH_OFFSET, null);
    }

    private void recordCleaner(VkCommandBuffer cb, GpuBuffer nodes, int frame, int frameId, int currentMaxNodeId) {
        int groups = LodComputeLayout.cleanerGroups(currentMaxNodeId);
        if (groups == 0) {
            return;
        }
        GpuBuffer output = cleanerOutput[frame];
        // Upstream: outputBuffer.fill(maxNodeCount - 2) before the sorter.
        vkCmdFillBuffer(cb, output.handle(), 0, LodComputeLayout.CLEANER_OUTPUT_BYTES, nodeCapacity - 2);
        new SyncBatch()
                .addWhole(output, TRANSFER, TRANSFER_WRITE, COMPUTE, SSBO_RW)
                .addWhole(visibility, COMPUTE | FRAGMENT, SSBO_WRITE, COMPUTE, SSBO_RW)
                .addWhole(nodes, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .record(cb);
        sorter.pipeline.dispatch(cb, sorter.pipeline.descriptorSetIndex(frame, 0), groups, 1, 1, null);
        new SyncBatch()
                .addWhole(output, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .addWhole(visibility, COMPUTE, SSBO_WRITE, COMPUTE, SSBO_RW)
                .record(cb);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(Integer.BYTES);
            push.putInt(0, frameId);
            transformer.pipeline.dispatch(cb, transformer.pipeline.descriptorSetIndex(frame, 0), 1, 1, 1, push);
        }
    }

    private void recordFinalBarriers(VkCommandBuffer cb, LodFrameBuffers slot, GpuBuffer nodes, int frame,
                                     boolean cleaned) {
        SyncBatch batch = new SyncBatch()
                .addWhole(slot.drawCommands(), COMPUTE, SSBO_WRITE, DRAW_INDIRECT, INDIRECT_READ)
                .addWhole(slot.drawControl(), COMPUTE, SSBO_WRITE, DRAW_INDIRECT, INDIRECT_READ)
                .addWhole(slot.positionScratch(), COMPUTE, SSBO_WRITE, VERTEX | FRAGMENT, SSBO_READ)
                .addWhole(slot.requestQueue(), COMPUTE, SSBO_WRITE, HOST, HOST_READ)
                // Node flags are written by traversal and the next frame's uploads/traversal follow in queue order.
                .addWhole(nodes, COMPUTE, SSBO_WRITE, TRANSFER | COMPUTE, TRANSFER_RW | SSBO_RW);
        if (cleaned) {
            batch.addWhole(cleanerOutput[frame], COMPUTE, SSBO_WRITE, HOST, HOST_READ);
        }
        batch.record(cb);
    }

    // ---------------------------------------------------------------- lifecycle & types

    private int checkSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= framesInFlight) {
            throw new IndexOutOfBoundsException("frame slot " + slotIndex + " outside executor's " + framesInFlight);
        }
        return slotIndex;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("compute executor is closed");
        }
    }

    /** Destroys pipelines and executor-owned buffers; callers MUST first prove all submitted frames retired. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        visibilitySet.close();
        transformer.close();
        sorter.close();
        translucent.close();
        prefix.close();
        cmdgen.close();
        prep.close();
        traversal.close();
        for (int i = 0; i < framesInFlight; i++) {
            cleanerOutput[i].close();
            visibilityOps[i].close();
            queueMetadata[i].close();
        }
        visibility.close();
        sectionVisibility.close();
    }

    private static ComputePipeline.Binding ssbo(int binding) {
        return new ComputePipeline.Binding(binding, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    private static <T extends AutoCloseable> T track(List<AutoCloseable> created, T resource) {
        created.add(resource);
        return resource;
    }

    /** Node-related GPU inputs owned by the node/session layer. */
    public record NodeInputs(GpuBuffer nodes, GpuBuffer topLevelIds, int topLevelCount) {
        public NodeInputs {
            Objects.requireNonNull(nodes, "nodes");
            Objects.requireNonNull(topLevelIds, "topLevelIds");
            if (topLevelCount < 0) {
                throw new IllegalArgumentException("topLevelCount must be non-negative");
            }
        }
    }

    /** HiZ pyramid descriptor values (combined image sampler at traversal binding 0). */
    public record HiZInputs(long imageView, long sampler, int imageLayout, int packedSize) {
        public HiZInputs {
            if (imageView == 0 || sampler == 0) {
                throw new IllegalArgumentException("HiZ image view and sampler must be nonzero");
            }
        }
    }

    /**
     * Graphics/compute work interleaved into the same command buffer. Hooks run outside any other render
     * scope; a hook that needs dynamic rendering opens and closes its own.
     */
    public interface Hooks {
        /** Builds/transitions the HiZ pyramid and returns its descriptor values for traversal. */
        HiZInputs beforeTraversal(VkCommandBuffer commandBuffer, LodFrameBuffers slot);

        /** After traversal barriers (e.g. temporal draws or HiZ rebuild); default does nothing. */
        default void afterTraversal(VkCommandBuffer commandBuffer, LodFrameBuffers slot) {
        }

        /**
         * Records the occlusion-test raster (cull/raster.vert + raster.frag) as an indirect indexed draw of
         * {@code slot.drawControl()} at {@link #CULL_COMMAND_OFFSET} inside its own render scope, which it
         * MUST close before returning. The executor has made prep results visible to DRAW_INDIRECT and the
         * vertex stage, and orders the fragment visibility writes before command generation.
         */
        void drawCullVisibility(VkCommandBuffer commandBuffer, LodFrameBuffers slot);
    }

    /**
     * Per-frame inputs. {@code mvp} is the 16-float column-major projection*modelView (camera-relative,
     * rotation-only view, Vulkan zero-to-one depth). {@code visibilityOps} (nullable) are upstream batch
     * visibility ids: bit 31 set stores the frame id, clear stores 0xFFFFFFFF. {@code frameId} is the
     * recorded-LOD-frame visibility id (already packed by {@link LodComputeLayout#packFrameId}; masking is
     * idempotent) and must advance by exactly one per recorded frame, because the shaders classify temporal
     * additions as {@code storedFrameId == frameId - 1}.
     */
    public record FrameInputs(long frameId, float[] mvp, double cameraX, double cameraY, double cameraZ,
                              int viewportWidth, int viewportHeight, float subdivisionSize,
                              int renderDistanceSections, int requestQueueSize,
                              NodeInputs nodes, GpuBuffer sectionMetadata,
                              int[] visibilityOps, boolean runCleaner, int cleanerMaxNodeId, Hooks hooks) {
        public FrameInputs {
            if (frameId < 0) {
                throw new IllegalArgumentException("frameId must be non-negative");
            }
            if (mvp == null || mvp.length != 16) {
                throw new IllegalArgumentException("mvp must contain sixteen column-major floats");
            }
            if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
                throw new IllegalArgumentException("camera position must be finite");
            }
            if (viewportWidth <= 0 || viewportHeight <= 0 || !(subdivisionSize > 0)) {
                throw new IllegalArgumentException("viewport and subdivision size must be positive");
            }
            if (requestQueueSize < 0 || requestQueueSize > LodComputeLayout.MAX_REQUEST_QUEUE_SIZE) {
                throw new IllegalArgumentException("requestQueueSize must be 0.." + LodComputeLayout.MAX_REQUEST_QUEUE_SIZE);
            }
            Objects.requireNonNull(nodes, "nodes");
            Objects.requireNonNull(sectionMetadata, "sectionMetadata");
            Objects.requireNonNull(hooks, "hooks");
            if (visibilityOps != null && visibilityOps.length > MAX_VISIBILITY_OPS) {
                throw new IllegalArgumentException("at most " + MAX_VISIBILITY_OPS + " visibility ops per frame");
            }
            if (runCleaner && cleanerMaxNodeId < 0) {
                throw new IllegalArgumentException("cleanerMaxNodeId must be non-negative");
            }
        }
    }

    /** A compute pipeline plus a write-skipping descriptor cache (sets are only rewritten when values change). */
    private static final class Bound implements AutoCloseable {
        final ComputePipeline pipeline;
        private final long[] cache;

        Bound(ComputePipeline pipeline, int framesInFlight, int setsPerFrame) {
            this.pipeline = pipeline;
            this.cache = new long[framesInFlight * setsPerFrame * BINDING_SLOTS * 4];
        }

        void buffer(int set, int binding, GpuBuffer buffer) {
            buffer(set, binding, buffer, 0, buffer.capacity());
        }

        void buffer(int set, int binding, GpuBuffer buffer, long offset, long range) {
            int base = (set * BINDING_SLOTS + binding) * 4;
            long handle = buffer.handle();
            if (cache[base] == handle && cache[base + 1] == offset && cache[base + 2] == range
                    && cache[base + 3] == 1) {
                return;
            }
            pipeline.setBuffer(set, binding, buffer, offset, range);
            cache[base] = handle;
            cache[base + 1] = offset;
            cache[base + 2] = range;
            cache[base + 3] = 1;
        }

        void sampler(int set, int binding, long sampler, long imageView, int layout) {
            int base = (set * BINDING_SLOTS + binding) * 4;
            long key = sampler * 31 + layout;
            if (cache[base] == imageView && cache[base + 1] == key && cache[base + 3] == 2) {
                return;
            }
            pipeline.setCombinedImageSampler(set, binding, sampler, imageView, layout);
            cache[base] = imageView;
            cache[base + 1] = key;
            cache[base + 3] = 2;
        }

        @Override
        public void close() {
            pipeline.close();
        }
    }
}
