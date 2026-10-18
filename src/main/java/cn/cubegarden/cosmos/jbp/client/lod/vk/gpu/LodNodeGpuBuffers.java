package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.data.SectionMeta;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.NodeStore;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionDelta;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/**
 * Persistent, slot-addressed device-local node, top-level-id, section-metadata and packed-quad buffers
 * that mirror the CPU {@code NodeManager}/{@code SectionGeometryManager} state through
 * {@link SectionDelta}s. Replaces the immutable per-snapshot section upload: the geometry heap, metadata
 * slots and node ids are mutated in place by range copies recorded into the caller's command buffer.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code HierarchicalOcclusionTraverser.java}
 * (node buffer filled with {@code -1}; {@code addTLN}/{@code remTLN} swap-remove top-level id array),
 * {@code AsyncNodeManager.java} (merged sync results) and {@code BasicSectionGeometryData.java}
 * (32-byte metadata + 8-byte quad stores). Upstream's GL upload stream/scatter shaders become one staging
 * buffer plus {@code vkCmdCopyBuffer} regions with range-scoped Synchronization2 barriers.
 *
 * <p>Render-thread only. Hand {@link #detachPendingUploads()} to the frame slot's {@code retireAfterUse}
 * once its command buffer has been submitted; never waits for the GPU.
 */
public final class LodNodeGpuBuffers {
    /** Upstream traversal capacity ({@code MAX_QUEUE_SIZE}) for top-level node ids. */
    public static final int MAX_TOP_LEVEL_NODES = 200_000;

    private final GpuBuffer nodes;
    private final GpuBuffer topLevelIds;
    private final GpuBuffer sectionMetadata;
    private final GpuBuffer geometry;
    private final int nodeCapacity;
    private final int sectionCapacity;

    //CPU mirror of the GPU top-level id array: index -> id (swap-remove, as upstream traverser)
    private final int[] idxToTop = new int[MAX_TOP_LEVEL_NODES];
    private final Int2IntOpenHashMap topToIdx = new Int2IntOpenHashMap();
    private int topLevelCount;

    private boolean initialized;
    private boolean closed;
    private int maxNodeId = -1;
    private int liveNodeCount;
    private int liveSectionCount;
    private long geometryUsedBytes;
    private final List<StagingBuffer> pendingStaging = new ArrayList<>();

    public LodNodeGpuBuffers(GameVulkan.Snapshot game, int nodeCapacity, int sectionCapacity,
                             long geometryCapacityBytes) {
        if (nodeCapacity <= 0 || sectionCapacity <= 0 || sectionCapacity > 200_000) {
            throw new IllegalArgumentException("node capacity must be positive and sections 1..200000");
        }
        if (geometryCapacityBytes <= 0 || geometryCapacityBytes % 8 != 0) {
            throw new IllegalArgumentException("geometry capacity must be a positive multiple of 8");
        }
        this.nodeCapacity = nodeCapacity;
        this.sectionCapacity = sectionCapacity;
        this.topToIdx.defaultReturnValue(-1);
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        GpuBuffer n = null;
        GpuBuffer t = null;
        GpuBuffer m = null;
        try {
            n = GpuBuffer.allocate(game, (long) nodeCapacity * NodeStore.GPU_NODE_BYTES, usage, false);
            t = GpuBuffer.allocate(game, MAX_TOP_LEVEL_NODES * 4L, usage, false);
            m = GpuBuffer.allocate(game, (long) sectionCapacity * SectionMeta.BYTE_SIZE, usage, false);
            this.geometry = GpuBuffer.allocate(game, geometryCapacityBytes, usage, false);
            this.nodes = n;
            this.topLevelIds = t;
            this.sectionMetadata = m;
        } catch (RuntimeException | Error failure) {
            if (m != null) m.close();
            if (t != null) t.close();
            if (n != null) n.close();
            throw failure;
        }
    }

    /** Node storage: {@code NodeStore.GPU_NODE_BYTES} per node, indexed by node id ({@code uvec4[]}). */
    public GpuBuffer nodes() {
        return nodes;
    }

    /** {@code uint[]} of top-level node ids; the first {@link #topLevelCount()} entries are valid. */
    public GpuBuffer topLevelIds() {
        return topLevelIds;
    }

    /** 32-byte {@code SectionMeta} per slot; a node's mesh pointer is the slot index. */
    public GpuBuffer sectionMetadata() {
        return sectionMetadata;
    }

    /** Packed 8-byte quads; {@code SectionMeta.a.w} is a quad index into this buffer. */
    public GpuBuffer geometry() {
        return geometry;
    }

    public int topLevelCount() {
        return topLevelCount;
    }

    public int nodeCapacity() {
        return nodeCapacity;
    }

    public int sectionCapacity() {
        return sectionCapacity;
    }

    public long geometryCapacityBytes() {
        return geometry.capacity();
    }

    /** Highest allocated node id as of the last applied delta (-1 when none). */
    public int maxNodeId() {
        return maxNodeId;
    }

    public int nodeCount() {
        return liveNodeCount;
    }

    public int sectionCount() {
        return liveSectionCount;
    }

    public long geometryUsedBytes() {
        return geometryUsedBytes;
    }

    /**
     * Records every copy and dependency needed to bring the GPU buffers to the delta's state. Returns true
     * if commands were recorded. {@code delta} may be null/empty. Must be recorded before the compute
     * passes that read these buffers, in the same command buffer.
     */
    public boolean recordUploads(GameVulkan.Snapshot game, VkCommandBuffer commandBuffer, SectionDelta delta) {
        ensureOpen();
        boolean recorded = false;
        if (!initialized) {
            initialized = true;
            //Upstream fills the node buffer with -1 (non-existent node) before first use
            vkCmdFillBuffer(commandBuffer, nodes.handle(), 0, nodes.capacity(), 0xFFFFFFFF);
            Barriers.record(commandBuffer, nodes, 0, nodes.capacity(),
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                            | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            recorded = true;
        }
        if (delta == null || delta.isEmpty()) {
            if (delta != null) {
                adoptStatistics(delta);
            }
            return recorded;
        }

        UploadPlan plan = plan(delta);
        adoptStatistics(delta);
        if (plan.totalBytes == 0) {
            return recorded;
        }

        StagingBuffer staging = new StagingBuffer(game, plan.totalBytes);
        try {
            staging.write(plan.bytes);
            hazardBarrier(commandBuffer, nodes, plan.nodes);
            hazardBarrier(commandBuffer, topLevelIds, plan.topLevel);
            hazardBarrier(commandBuffer, sectionMetadata, plan.metadata);
            hazardBarrier(commandBuffer, geometry, plan.quads);
            copyRegions(commandBuffer, staging.buffer(), nodes, plan.nodes);
            copyRegions(commandBuffer, staging.buffer(), topLevelIds, plan.topLevel);
            copyRegions(commandBuffer, staging.buffer(), sectionMetadata, plan.metadata);
            copyRegions(commandBuffer, staging.buffer(), geometry, plan.quads);

            barrier(commandBuffer, nodes, plan.nodes, true, false);
            barrier(commandBuffer, topLevelIds, plan.topLevel, false, false);
            barrier(commandBuffer, sectionMetadata, plan.metadata, false, true);
            barrier(commandBuffer, geometry, plan.quads, false, true);
            pendingStaging.add(staging);
            return true;
        } catch (RuntimeException | Error failure) {
            staging.close();
            throw failure;
        }
    }

    /**
     * Detaches the staging buffers used by the uploads recorded since the last call, or returns null when
     * there are none. Register the result with {@code LodFrameBuffers.retireAfterUse} after that slot's
     * command buffer has been submitted; it closes once the slot's fence-only marker signals.
     */
    public AutoCloseable detachPendingUploads() {
        ensureOpen();
        if (pendingStaging.isEmpty()) {
            return null;
        }
        List<StagingBuffer> batch = new ArrayList<>(pendingStaging);
        pendingStaging.clear();
        return () -> batch.forEach(StagingBuffer::close);
    }

    /** Defers destruction to a fence marker that follows all previously submitted uses. */
    public void retireAndClose(GameVulkan.Snapshot game) {
        if (closed) {
            return;
        }
        closed = true;
        List<StagingBuffer> staging = new ArrayList<>(pendingStaging);
        pendingStaging.clear();
        GpuRetireQueue.retire(GpuCompletion.submitMarker(game), () -> {
            staging.forEach(StagingBuffer::close);
            geometry.close();
            sectionMetadata.close();
            topLevelIds.close();
            nodes.close();
        });
        GpuRetireQueue.poll(game);
    }

    //==================================================================================================================

    private void adoptStatistics(SectionDelta delta) {
        maxNodeId = delta.maxNodeId();
        liveNodeCount = delta.nodeCount();
        liveSectionCount = delta.sectionCount();
        geometryUsedBytes = delta.geometryUsedBytes();
    }

    private UploadPlan plan(SectionDelta delta) {
        //Top-level mirror first: removals (swap with last) then additions, as the upstream callbacks do
        IntOpenHashSet changedTopIndices = new IntOpenHashSet();
        IntIterator removals = delta.topLevelDelta().iterator();
        IntArrayList additions = new IntArrayList();
        while (removals.hasNext()) {
            int value = removals.nextInt();
            if ((value & SectionDelta.ADD_FLAG) != 0) {
                additions.add(value & ~SectionDelta.ADD_FLAG);
            } else {
                removeTopLevel(value, changedTopIndices);
            }
        }
        for (int i = 0; i < additions.size(); i++) {
            addTopLevel(additions.getInt(i), changedTopIndices);
        }

        int[] nodeIds = delta.nodeWrites().keySet().toIntArray();
        int[] slotIds = delta.metadataWrites().keySet().toIntArray();
        int[] quadPtrs = delta.quadUploads().keySet().toIntArray();
        int[] topIdx = changedTopIndices.toIntArray();
        Arrays.sort(nodeIds);
        Arrays.sort(slotIds);
        Arrays.sort(quadPtrs);
        Arrays.sort(topIdx);

        long total = 0;
        total += (long) nodeIds.length * NodeStore.GPU_NODE_BYTES;
        total += (long) slotIds.length * SectionMeta.BYTE_SIZE;
        total += (long) topIdx.length * 4;
        for (int ptr : quadPtrs) {
            total += delta.quadUploads().get(ptr).length;
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("[JBP-LOD] section delta exceeds 2 GiB staging limit");
        }

        byte[] bytes = new byte[(int) total];
        RegionList nodeRegions = new RegionList();
        RegionList topRegions = new RegionList();
        RegionList metaRegions = new RegionList();
        RegionList quadRegions = new RegionList();
        int cursor = 0;

        for (int id : nodeIds) {
            if (id < 0 || id >= nodeCapacity) {
                throw new IllegalStateException("[JBP-LOD] node id " + id + " exceeds GPU capacity " + nodeCapacity);
            }
            byte[] data = delta.nodeWrites().get(id);
            System.arraycopy(data, 0, bytes, cursor, NodeStore.GPU_NODE_BYTES);
            nodeRegions.add(cursor, (long) id * NodeStore.GPU_NODE_BYTES, NodeStore.GPU_NODE_BYTES);
            cursor += NodeStore.GPU_NODE_BYTES;
        }
        for (int idx : topIdx) {
            writeIntLe(bytes, cursor, idxToTop[idx]);
            topRegions.add(cursor, idx * 4L, 4);
            cursor += 4;
        }
        for (int slot : slotIds) {
            if (slot < 0 || slot >= sectionCapacity) {
                throw new IllegalStateException("[JBP-LOD] section slot " + slot + " exceeds GPU capacity "
                        + sectionCapacity);
            }
            byte[] data = delta.metadataWrites().get(slot);
            System.arraycopy(data, 0, bytes, cursor, SectionMeta.BYTE_SIZE);
            metaRegions.add(cursor, (long) slot * SectionMeta.BYTE_SIZE, SectionMeta.BYTE_SIZE);
            cursor += SectionMeta.BYTE_SIZE;
        }
        for (int ptr : quadPtrs) {
            byte[] data = delta.quadUploads().get(ptr);
            long dstOffset = Integer.toUnsignedLong(ptr) * 8L;
            if (dstOffset + data.length > geometry.capacity()) {
                throw new IllegalStateException("[JBP-LOD] quad upload exceeds geometry buffer capacity");
            }
            System.arraycopy(data, 0, bytes, cursor, data.length);
            quadRegions.add(cursor, dstOffset, data.length);
            cursor += data.length;
        }
        return new UploadPlan(bytes, total, nodeRegions, topRegions, metaRegions, quadRegions);
    }

    private void addTopLevel(int id, IntOpenHashSet changed) {
        int idx = topLevelCount++;
        if (topLevelCount > MAX_TOP_LEVEL_NODES) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }
        if (topToIdx.put(id, idx) != -1) {
            throw new IllegalStateException("top-level node id already present: " + id);
        }
        idxToTop[idx] = id;
        changed.add(idx);
    }

    private void removeTopLevel(int id, IntOpenHashSet changed) {
        int idx = topToIdx.remove(id);
        if (idx == -1) {
            throw new IllegalStateException("top-level node id not present: " + id);
        }
        topLevelCount--;
        if (idx == topLevelCount) {
            changed.remove(idx);
            return;
        }
        int endId = idxToTop[topLevelCount];
        idxToTop[idx] = endId;
        topToIdx.put(endId, idx);
        changed.remove(topLevelCount);
        changed.add(idx);
    }

    private static void writeIntLe(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void copyRegions(VkCommandBuffer commandBuffer, GpuBuffer source, GpuBuffer destination,
                                    RegionList regions) {
        if (regions.count == 0) {
            return;
        }
        VkBufferCopy.Buffer copies = VkBufferCopy.calloc(regions.count);
        try {
            for (int i = 0; i < regions.count; i++) {
                copies.get(i).srcOffset(regions.src[i]).dstOffset(regions.dst[i]).size(regions.size[i]);
            }
            vkCmdCopyBuffer(commandBuffer, source.handle(), destination.handle(), copies);
        } finally {
            copies.free();
        }
    }

    /**
     * Write-after-read/write hazard: earlier submissions on this queue (compute traversal/prep, vertex or
     * fragment reads, compute node marking) must finish before the in-place copy overwrites the range.
     */
    private static void hazardBarrier(VkCommandBuffer commandBuffer, GpuBuffer buffer, RegionList regions) {
        if (regions.count == 0) {
            return;
        }
        long start = regions.minDst();
        Barriers.record(commandBuffer, buffer, start, regions.maxDstEnd() - start,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                        | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
    }

    private static void barrier(VkCommandBuffer commandBuffer, GpuBuffer buffer, RegionList regions,
                                boolean computeReadWrite, boolean vertexRead) {
        if (regions.count == 0) {
            return;
        }
        long start = regions.minDst();
        long size = regions.maxDstEnd() - start;
        if (computeReadWrite) {
            Barriers.transferWriteToComputeWrite(commandBuffer, buffer, start, size);
        } else if (vertexRead) {
            Barriers.transferWriteToComputeVertexRead(commandBuffer, buffer, start, size);
        } else {
            Barriers.transferWriteToComputeRead(commandBuffer, buffer, start, size);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("node GPU buffers are closed");
        }
    }

    private record UploadPlan(byte[] bytes, long totalBytes, RegionList nodes, RegionList topLevel,
                              RegionList metadata, RegionList quads) {
    }

    /** Growable list of copy regions, merging regions contiguous in both source and destination. */
    static final class RegionList {
        long[] src = new long[16];
        long[] dst = new long[16];
        long[] size = new long[16];
        int count;

        void add(long srcOffset, long dstOffset, long length) {
            if (length == 0) {
                return;
            }
            if (count > 0 && src[count - 1] + size[count - 1] == srcOffset
                    && dst[count - 1] + size[count - 1] == dstOffset) {
                size[count - 1] += length;
                return;
            }
            if (count == src.length) {
                int newLength = count * 2;
                src = Arrays.copyOf(src, newLength);
                dst = Arrays.copyOf(dst, newLength);
                size = Arrays.copyOf(size, newLength);
            }
            src[count] = srcOffset;
            dst[count] = dstOffset;
            size[count] = length;
            count++;
        }

        long minDst() {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                min = Math.min(min, dst[i]);
            }
            return min;
        }

        long maxDstEnd() {
            long max = 0;
            for (int i = 0; i < count; i++) {
                max = Math.max(max, dst[i] + size[i]);
            }
            return max;
        }
    }
}
