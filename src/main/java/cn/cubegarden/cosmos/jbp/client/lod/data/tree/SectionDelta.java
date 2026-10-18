package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Batch of CPU-side tree/geometry state changes that must be mirrored into GPU buffers.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/hierachical/AsyncNodeManager.java} ({@code SyncResults}: top-level-node
 * delta, geometry uploads, cleaner id operations and invalidated node/section data, merged without
 * blocking the render thread). Producers merge deltas with {@link #mergeFrom}; later writes win.
 *
 * <p>Encodings (identical to upstream):
 * <ul>
 *   <li>{@link #topLevelDelta()}: {@code id | 1<<31} = top-level node added, plain {@code id} = removed.</li>
 *   <li>{@link #cleanerOps()}: {@code id | 1<<31} = reset visibility to the current frame (node allocated),
 *       plain {@code id} = clear visibility (node freed).</li>
 *   <li>{@link #nodeWrites()}: node id → 16-byte compact node ({@code node.glsl} {@code uvec4}).</li>
 *   <li>{@link #metadataWrites()}: section slot → 32-byte {@link cn.cubegarden.cosmos.jbp.client.lod.data.SectionMeta}.</li>
 *   <li>{@link #quadUploads()}: quad index in the geometry heap → packed 8-byte quads.</li>
 * </ul>
 */
public final class SectionDelta {
    public static final int ADD_FLAG = 1 << 31;

    private final Int2ObjectOpenHashMap<byte[]> nodeWrites = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<byte[]> metadataWrites = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<byte[]> quadUploads = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet topLevelDelta = new IntOpenHashSet();
    private final IntOpenHashSet cleanerOps = new IntOpenHashSet();
    private int maxNodeId = -1;
    private int nodeCount;
    private int sectionCount;
    private long geometryUsedBytes;

    public Int2ObjectOpenHashMap<byte[]> nodeWrites() {
        return nodeWrites;
    }

    public Int2ObjectOpenHashMap<byte[]> metadataWrites() {
        return metadataWrites;
    }

    public Int2ObjectOpenHashMap<byte[]> quadUploads() {
        return quadUploads;
    }

    public IntOpenHashSet topLevelDelta() {
        return topLevelDelta;
    }

    public IntOpenHashSet cleanerOps() {
        return cleanerOps;
    }

    /** Highest allocated node id after this delta (-1 when none). */
    public int maxNodeId() {
        return maxNodeId;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int sectionCount() {
        return sectionCount;
    }

    public long geometryUsedBytes() {
        return geometryUsedBytes;
    }

    public boolean isEmpty() {
        return nodeWrites.isEmpty() && metadataWrites.isEmpty() && quadUploads.isEmpty()
                && topLevelDelta.isEmpty() && cleanerOps.isEmpty();
    }

    /** Records the post-change tree statistics. */
    public void setStatistics(int maxNodeId, int nodeCount, int sectionCount, long geometryUsedBytes) {
        this.maxNodeId = maxNodeId;
        this.nodeCount = nodeCount;
        this.sectionCount = sectionCount;
        this.geometryUsedBytes = geometryUsedBytes;
    }

    /**
     * Applies {@code later} on top of this delta. {@code removedQuadPtrs} are heap addresses that were freed
     * between the two deltas and whose still-pending uploads in this delta must be dropped.
     */
    public void mergeFrom(SectionDelta later, IntOpenHashSet removedQuadPtrs) {
        nodeWrites.putAll(later.nodeWrites);
        metadataWrites.putAll(later.metadataWrites);
        if (removedQuadPtrs != null) {
            IntIterator iterator = removedQuadPtrs.iterator();
            while (iterator.hasNext()) {
                quadUploads.remove(iterator.nextInt());
            }
        }
        quadUploads.putAll(later.quadUploads);

        IntIterator tln = later.topLevelDelta.iterator();
        while (tln.hasNext()) {
            int value = tln.nextInt();
            if (!topLevelDelta.remove(value ^ ADD_FLAG)) {//Remove opposite
                topLevelDelta.add(value);
            }
        }

        IntIterator ops = later.cleanerOps.iterator();
        while (ops.hasNext()) {
            int value = ops.nextInt();
            cleanerOps.remove(value ^ ADD_FLAG);
            cleanerOps.add(value);
        }

        setStatistics(later.maxNodeId, later.nodeCount, later.sectionCount, later.geometryUsedBytes);
    }
}
