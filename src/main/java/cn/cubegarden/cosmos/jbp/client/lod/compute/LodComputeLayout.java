// Derived from MCRcortex/voxy 263 (https://github.com/MCRcortex/voxy):
//   me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser (traversal binding order,
//     queue metadata seeding, uniform layout, iteration ping-pong, MAX_ITERATIONS/LOCAL_WORK_SIZE_BITS)
//   me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner (sorter/transformer sizing and dispatch width)
//   me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer (draw regions, offsets, defines)
//   me.cortex.voxy.client.core.rendering.Viewport (camera section / inner translation, frustum from MVP)
// JBP-LOD adaptation: pure layout/dispatch arithmetic shared by the Vulkan executor and its unit tests.
package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;

/** Binding numbers, shader defines and CPU-side arithmetic of the V3 compute pass graph. */
public final class LodComputeLayout {
    private LodComputeLayout() {
    }

    /** Upstream {@code WorldEngine.MAX_LOD_LAYER + 1}: traversal dispatches one layer per LoD level. */
    public static final int MAX_ITERATIONS = LodFrameBuffers.LOD_LEVEL_COUNT;
    public static final int LOCAL_WORK_SIZE_BITS = 5;
    public static final int LOCAL_WORK_SIZE = 1 << LOCAL_WORK_SIZE_BITS;
    public static final int MAX_REQUEST_QUEUE_SIZE = LodFrameBuffers.REQUEST_QUEUE_CAPACITY;
    public static final int QUEUE_METADATA_BYTES = MAX_ITERATIONS * 16;

    /** Upstream {@code TRANSLUCENT_WRITE_BASE}: the distance-bin count, also the draw-ID array base. */
    public static final int TRANSLUCENT_WRITE_BASE = LodFrameBuffers.TRANSLUCENT_DISTANCE_BINS;
    public static final long TRANSLUCENT_BIN_BYTES = (long) TRANSLUCENT_WRITE_BASE * Integer.BYTES;
    /** Draw-command indices, not bytes (MDICSectionRenderer.TRANSLUCENT_OFFSET / TEMPORAL_OFFSET). */
    public static final int TRANSLUCENT_OFFSET = LodFrameBuffers.OPAQUE_COMMAND_CAPACITY;
    public static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + LodFrameBuffers.TRANSLUCENT_COMMAND_CAPACITY;

    public static final int SECTION_DISPATCH_WIDTH = 128;

    /** Draw-control record offsets (std430, see V3-contract.md). */
    public static final long DRAW_CONTROL_DISPATCH_OFFSET = 0;
    public static final long DRAW_CONTROL_DISPATCH_BYTES = 12;
    public static final long DRAW_CONTROL_CULL_COMMAND_OFFSET = 24;
    public static final long DRAW_CONTROL_CULL_COMMAND_BYTES = 20;

    public static final int CLEANER_OUTPUT_COUNT = 256;
    public static final int CLEANER_WORK_SIZE = 64;
    public static final int CLEANER_ELEMS_PER_THREAD = 8;
    public static final int CLEANER_ELEMS_PER_GROUP = CLEANER_WORK_SIZE * CLEANER_ELEMS_PER_THREAD;
    public static final long CLEANER_SCRATCH_BYTES = (long) CLEANER_OUTPUT_COUNT * Integer.BYTES;
    public static final long CLEANER_RESULT_BYTES = (long) CLEANER_OUTPUT_COUNT * 2 * Integer.BYTES;
    public static final long CLEANER_OUTPUT_BYTES = CLEANER_SCRATCH_BYTES + CLEANER_RESULT_BYTES;
    public static final int MAX_VISIBILITY_OPS = 65_536;
    public static final int VISIBILITY_SET_WORK_SIZE = 128;
    public static final int PREFIX_SUM_VALUES = 1024;

    /** Traversal descriptor bindings (upstream BINDING_COUNTER order; 5 was the GL uniform slot of the queue index). */
    public static final int TRAVERSAL_HIZ_BINDING = 0;
    public static final int TRAVERSAL_SCENE_UNIFORM_BINDING = 1;
    public static final int TRAVERSAL_REQUEST_QUEUE_BINDING = 2;
    public static final int TRAVERSAL_RENDER_QUEUE_BINDING = 3;
    public static final int TRAVERSAL_NODE_DATA_BINDING = 4;
    public static final int TRAVERSAL_NODE_QUEUE_META_BINDING = 6;
    public static final int TRAVERSAL_NODE_QUEUE_SOURCE_BINDING = 7;
    public static final int TRAVERSAL_NODE_QUEUE_SINK_BINDING = 8;
    public static final int TRAVERSAL_RENDER_TRACKER_BINDING = 9;

    /** prep.comp bindings (binding 0 is the scene uniform declared by bindings.glsl). */
    public static final int PREP_DRAW_COUNT_BINDING = 1;
    public static final int PREP_LOOKUP_BINDING = 2;

    /** cmdgen.comp bindings. */
    public static final int CMDGEN_DRAW_BINDING = 1;
    public static final int CMDGEN_DRAW_COUNT_BINDING = 2;
    public static final int CMDGEN_METADATA_BINDING = 3;
    public static final int CMDGEN_VISIBILITY_BINDING = 4;
    public static final int CMDGEN_LOOKUP_BINDING = 5;
    public static final int CMDGEN_POSITION_BINDING = 6;
    public static final int CMDGEN_TRANSLUCENT_BINDING = 7;

    /** buildtranslucents.comp bindings. */
    public static final int TRANSLUCENT_DRAW_BINDING = 1;
    public static final int TRANSLUCENT_DRAW_COUNT_BINDING = 2;
    public static final int TRANSLUCENT_METADATA_BINDING = 3;
    public static final int TRANSLUCENT_LOOKUP_BINDING = 4;
    public static final int TRANSLUCENT_SCRATCH_BINDING = 5;

    /** Cleaner bindings (upstream NodeCleaner). */
    public static final int SORT_VISIBILITY_BINDING = 1;
    public static final int SORT_OUTPUT_BINDING = 2;
    public static final int SORT_NODE_BINDING = 3;
    public static final int TRANSFORM_MIN_ID_BINDING = 0;
    public static final int TRANSFORM_NODE_BINDING = 1;
    public static final int TRANSFORM_OUTPUT_BINDING = 2;
    public static final int TRANSFORM_VISIBILITY_BINDING = 3;
    public static final int VISIBILITY_SET_VISIBILITY_BINDING = 0;
    public static final int VISIBILITY_SET_LIST_BINDING = 1;

    /** Number of 128-thread groups covering {@code sectionCount} sections (prep.comp's dispatch X). */
    public static int sectionGroups(int sectionCount) {
        return (int) ((Integer.toUnsignedLong(sectionCount) + SECTION_DISPATCH_WIDTH - 1) / SECTION_DISPATCH_WIDTH);
    }

    /** Work groups of the first traversal iteration, one thread per top-level node. */
    public static int firstTraversalGroups(int topLevelCount) {
        if (topLevelCount < 0) {
            throw new IllegalArgumentException("top level node count must be non-negative");
        }
        return (topLevelCount + LOCAL_WORK_SIZE - 1) >>> LOCAL_WORK_SIZE_BITS;
    }

    /**
     * The {@code 16 * MAX_ITERATIONS} byte seed of the traversal queue metadata: {@code uvec4(groupsX, 1, 1,
     * queueSize)} per layer; only layer 0 holds work.
     */
    public static int[] traversalQueueMetadata(int topLevelCount) {
        int[] words = new int[MAX_ITERATIONS * 4];
        words[0] = firstTraversalGroups(topLevelCount);
        words[1] = 1;
        words[2] = 1;
        words[3] = topLevelCount;
        for (int i = 1; i < MAX_ITERATIONS; i++) {
            words[i * 4 + 1] = 1;
            words[i * 4 + 2] = 1;
        }
        return words;
    }

    /** Byte offset of layer {@code iteration}'s indirect dispatch arguments inside the queue metadata. */
    public static long traversalDispatchOffset(int iteration) {
        if (iteration < 1 || iteration >= MAX_ITERATIONS) {
            throw new IllegalArgumentException("indirect traversal iteration out of range: " + iteration);
        }
        return (long) iteration * 16;
    }

    /** Queue ping-pong: iteration 0 reads the top-level ids and writes B; then B->A, A->B, ... */
    public static QueueRole traversalSource(int iteration) {
        checkIteration(iteration);
        return iteration == 0 ? QueueRole.TOP_LEVEL : ((iteration & 1) == 0 ? QueueRole.A : QueueRole.B);
    }

    public static QueueRole traversalSink(int iteration) {
        checkIteration(iteration);
        return iteration == 0 ? QueueRole.B : ((iteration & 1) == 0 ? QueueRole.B : QueueRole.A);
    }

    public enum QueueRole { TOP_LEVEL, A, B }

    private static void checkIteration(int iteration) {
        if (iteration < 0 || iteration >= MAX_ITERATIONS) {
            throw new IllegalArgumentException("traversal iteration out of range: " + iteration);
        }
    }

    /** Frame ids are 31-bit: bit 31 of a visibility word encodes "visible last frame". */
    public static int packFrameId(long frameId) {
        if (frameId < 0) {
            throw new IllegalArgumentException("frame id must be non-negative");
        }
        return (int) (frameId & 0x7fffffffL);
    }

    /** Low 31 bits of a section visibility word: the frame id in which the section was last drawn. */
    public static final int VISIBILITY_FRAME_MASK = 0x7fffffff;
    /** Bit 31 of a section visibility word: set when the section was drawn in the previous LOD frame. */
    public static final int VISIBILITY_PREVIOUS_FLAG = 0x80000000;

    /**
     * The word {@code cull/raster.frag} stores for a section (upstream {@code cull/raster.vert}): the packed
     * frame id plus the "was visible last frame" flag, so the next frame can classify temporal additions.
     */
    public static int encodeVisibility(int packedFrameId, boolean visibleLastFrame) {
        return (packedFrameId & VISIBILITY_FRAME_MASK)
                | (visibleLastFrame ? VISIBILITY_PREVIOUS_FLAG : 0);
    }

    /**
     * Whether a stored word says the section was drawn in the previous LOD frame:
     * {@code raster.vert}'s {@code previous == frameId - 1}. A stored word from any other frame (unvisited,
     * skipped frame, resumed from a stale buffer) is never "visible last frame", so the section is drawn
     * again in the temporal range instead of being silently dropped.
     */
    public static boolean wasVisibleLastFrame(int storedWord, int packedFrameId) {
        return (storedWord & VISIBILITY_FRAME_MASK) == ((packedFrameId - 1) & VISIBILITY_FRAME_MASK);
    }

    /** Whether a stored word says the section is drawn in {@code packedFrameId} ({@code cmdgen.comp}). */
    public static boolean visibleThisFrame(int storedWord, int packedFrameId) {
        return (storedWord & VISIBILITY_FRAME_MASK) == (packedFrameId & VISIBILITY_FRAME_MASK);
    }

    /**
     * Whether {@code cmdgen.comp} writes this section into the temporal range: it is drawn this frame but was
     * not drawn in the previous one. The previous-frame opaque range is supplied separately, so a section
     * classified temporally is never lost even when the frame id skips a value.
     */
    public static boolean rendersTemporally(int storedWord) {
        return (storedWord & VISIBILITY_PREVIOUS_FLAG) == 0;
    }

    /** Upstream: {@code subDivisionSize^2 / (width*height)}, the screen fraction below which nodes stop descending. */
    public static float minimumScreenSpaceSize(float subdivisionSize, int width, int height) {
        if (!(subdivisionSize > 0) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("subdivision size and viewport must be positive");
        }
        return subdivisionSize * subdivisionSize / ((float) width * (float) height);
    }

    /** Upstream: {@code (sectionRenderDistance*16*32)^2}; negative means unlimited (shader tests {@code < 0}). */
    public static float renderDistanceSquared(int renderDistanceSections) {
        if (renderDistanceSections < 0) {
            return -1.0f;
        }
        double blocks = (double) renderDistanceSections * 16.0 * 32.0;
        return (float) (blocks * blocks);
    }

    /** Camera section coordinate ({@code floor(position) >> 5}) used as baseSectionPos / camSecPos. */
    public static int cameraSection(double position) {
        return ((int) Math.floor(position)) >> 5;
    }

    /** {@code position - section*32} as float, the upstream Viewport.innerTranslation. */
    public static float cameraSubSection(double position) {
        return (float) (position - (cameraSection(position) << 5));
    }

    /**
     * Returns {@code mvp * translate(-sub)} (column-major), the matrix the section/cull/cmdgen scene
     * uniform uses because positions are expressed relative to the camera section corner.
     */
    public static float[] translateMvp(float[] mvp, float subX, float subY, float subZ) {
        requireMatrix(mvp);
        float[] out = mvp.clone();
        for (int row = 0; row < 4; row++) {
            out[12 + row] = mvp[12 + row] - subX * mvp[row] - subY * mvp[4 + row] - subZ * mvp[8 + row];
        }
        return out;
    }

    /**
     * Six clip planes {@code (nx, ny, nz, w)} of a camera-relative column-major MVP for Vulkan's zero-to-one
     * clip depth: left, right, bottom, top, near, far (upstream JOML order). Planes are not normalised; the
     * shader's positive-vertex test is scale invariant. With reverse Z the near/far planes swap.
     */
    public static float[] frustumPlanes(float[] mvp, boolean reverseZ) {
        requireMatrix(mvp);
        float[] planes = new float[24];
        for (int i = 0; i < 4; i++) {
            float r0 = mvp[i * 4];
            float r1 = mvp[i * 4 + 1];
            float r2 = mvp[i * 4 + 2];
            float r3 = mvp[i * 4 + 3];
            planes[i] = r3 + r0;
            planes[4 + i] = r3 - r0;
            planes[8 + i] = r3 + r1;
            planes[12 + i] = r3 - r1;
            planes[16 + i] = reverseZ ? r3 - r2 : r2;
            planes[20 + i] = reverseZ ? r2 : r3 - r2;
        }
        return planes;
    }

    /** Number of 512-element groups the cleaner sorter dispatches for the current node id high-water mark. */
    public static int cleanerGroups(int currentMaxNodeId) {
        if (currentMaxNodeId < 0) {
            throw new IllegalArgumentException("node id high-water mark must be non-negative");
        }
        return (int) (((long) currentMaxNodeId + CLEANER_ELEMS_PER_GROUP - 1) / CLEANER_ELEMS_PER_GROUP);
    }

    /** Whether the subgroup prefix-sum shader's fixed 32/64-lane assumptions hold on this device. */
    public static boolean useSubgroupPrefixSum(boolean subgroupArithmetic, boolean computeStageSupported,
                                               int subgroupSize, int vendorId) {
        final int intel = 0x8086;
        return subgroupArithmetic && computeStageSupported && vendorId != intel
                && (subgroupSize == 32 || subgroupSize == 64);
    }

    private static void requireMatrix(float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException("matrix must contain sixteen column-major floats");
        }
    }
}
