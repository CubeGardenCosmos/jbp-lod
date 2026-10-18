package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import cn.cubegarden.cosmos.jbp.client.lod.data.SectionMeta;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Slot and quad-heap allocator mirroring the GPU section metadata / geometry buffers on the CPU.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/section/geometry/BasicAsyncGeometryManager.java}. Slot allocation
 * ({@link HierarchicalBitSet}), 128-quad heap rounding ({@link AllocationArena}), invalidation sets and the
 * 32-byte metadata encoding are unchanged. Native upload buffers are heap byte arrays.
 *
 * <p>Not thread-safe: owned by the node worker thread.
 */
public final class SectionGeometryManager implements GeometryManager {
    public static final int SECTION_METADATA_SIZE = SectionMeta.BYTE_SIZE;
    /** Bytes per packed quad. */
    public static final long GEOMETRY_ELEMENT_SIZE = 8;

    private final HierarchicalBitSet allocationSet;
    private final AllocationArena allocationHeap = new AllocationArena();
    private final ObjectArrayList<Meta> sectionMetadata = new ObjectArrayList<>(1 << 15);

    private final IntOpenHashSet invalidatedIds = new IntOpenHashSet(1024);
    //Key is the quad index of the heap allocation
    private final Int2ObjectOpenHashMap<byte[]> heapUploads = new Int2ObjectOpenHashMap<>(1024);
    private final IntOpenHashSet heapRemoveUploads = new IntOpenHashSet(1024);
    private long usedCapacity = 0;

    public SectionGeometryManager(int maxSectionCount, long geometryCapacityBytes) {
        this.allocationSet = new HierarchicalBitSet(maxSectionCount);
        if (geometryCapacityBytes % GEOMETRY_ELEMENT_SIZE != 0) {
            throw new IllegalArgumentException("geometry capacity must be a multiple of 8 bytes");
        }
        this.allocationHeap.setLimit(geometryCapacityBytes / GEOMETRY_ELEMENT_SIZE);
    }

    @Override
    public int uploadSection(BuiltSection section) {
        return this.uploadReplaceSection(-1, section);
    }

    @Override
    public int uploadReplaceSection(int oldId, BuiltSection section) {
        if (section.isEmpty()) {
            throw new IllegalArgumentException("sectionData is empty, cannot upload nothing");
        }
        if (oldId != -1) {
            this.removeSection(oldId);
        }

        int newId = this.allocationSet.allocateNext();
        if (newId == HierarchicalBitSet.SET_FULL) {
            throw new IllegalStateException("Tried adding section when section count is already at capacity");
        }
        if (newId > this.sectionMetadata.size()) {
            throw new IllegalStateException("Size exceeds limits: " + newId + ", " + this.sectionMetadata.size()
                    + ", " + this.allocationSet.getCount());
        }
        if (newId < this.sectionMetadata.size() && this.sectionMetadata.get(newId) != null) {
            throw new IllegalStateException();
        }

        Meta newMeta;
        try {
            newMeta = this.createMeta(section);
        } catch (RuntimeException e) {
            //Geometry OOM after the id was claimed: release the slot so it is not leaked.
            this.allocationSet.free(newId);
            throw e;
        }
        if (newId == this.sectionMetadata.size()) {
            this.sectionMetadata.add(newMeta);
        } else if (this.sectionMetadata.set(newId, newMeta) != null) {
            throw new IllegalStateException();
        }
        this.invalidatedIds.add(newId);
        return newId;
    }

    @Override
    public void removeSection(int id) {
        if (!this.allocationSet.free(id)) {
            throw new IllegalStateException("Id was not already allocated. id: " + id);
        }
        Meta oldMetadata = this.sectionMetadata.set(id, null);
        int ptr = oldMetadata.geometryPtr;
        this.usedCapacity -= this.allocationHeap.free(Integer.toUnsignedLong(ptr));
        this.heapUploads.remove(ptr);
        this.heapRemoveUploads.add(ptr);
        this.invalidatedIds.add(id);
    }

    private Meta createMeta(BuiltSection section) {
        int size = section.quadCount();
        //clamp size upwards to ranges of 128
        int upsized = (size + 127) & ~127;
        int addr = (int) this.allocationHeap.alloc(upsized);
        if (addr == -1) {
            throw new IllegalStateException("Geometry OOM. requested allocation size (in elements): " + size
                    + ", Heap size at top remaining: "
                    + (this.allocationHeap.getLimit() - this.allocationHeap.getSize())
                    + ", used elements: " + this.usedCapacity);
        }
        this.usedCapacity += upsized;
        if (this.heapUploads.put(addr, section.quadsUnsafe()) != null) {
            throw new IllegalStateException("Addr: " + addr);
        }
        this.heapRemoveUploads.remove(addr);
        return new Meta(section.position, section.aabb, addr, size, section.offsetsUnsafe(),
                section.childExistence);
    }

    public int getSectionCount() {
        return this.allocationSet.getCount();
    }

    public int getMaxSectionId() {
        return this.allocationSet.getMaxIndex();
    }

    public long getGeometryUsedBytes() {
        return this.usedCapacity * GEOMETRY_ELEMENT_SIZE;
    }

    public long getGeometryCapacityBytes() {
        return this.allocationHeap.getLimit() * GEOMETRY_ELEMENT_SIZE;
    }

    /** Returns and clears pending quad uploads (heap quad index → bytes). */
    public Int2ObjectOpenHashMap<byte[]> takeUploads() {
        Int2ObjectOpenHashMap<byte[]> out = new Int2ObjectOpenHashMap<>(this.heapUploads);
        this.heapUploads.clear();
        return out;
    }

    /** Returns and clears the slot ids whose metadata must be rewritten. */
    public IntOpenHashSet takeInvalidatedIds() {
        IntOpenHashSet out = new IntOpenHashSet(this.invalidatedIds);
        this.invalidatedIds.clear();
        return out;
    }

    /**
     * Returns and clears heap addresses freed since the last call. The device heap needs no zeroing, but
     * still-pending uploads at these addresses must be dropped from unpublished deltas.
     */
    public IntOpenHashSet takeHeapRemovals() {
        IntOpenHashSet out = new IntOpenHashSet(this.heapRemoveUploads);
        this.heapRemoveUploads.clear();
        return out;
    }

    /** Writes the 32-byte metadata of {@code sectionId} (zeroes for a freed id). */
    public void writeMetadata(int sectionId, ByteBuffer target, int byteOffset) {
        ByteBuffer out = target.order(ByteOrder.LITTLE_ENDIAN);
        Meta sec = sectionId < this.sectionMetadata.size() ? this.sectionMetadata.get(sectionId) : null;
        if (sec == null) {
            for (int i = 0; i < SECTION_METADATA_SIZE; i += 4) {
                out.putInt(byteOffset + i, 0);
            }
        } else {
            sec.write(out, byteOffset);
        }
    }

    private record Meta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets,
                        byte childExistence) {
        void write(ByteBuffer out, int o) {
            out.putInt(o, (int) (this.position >> 32));
            out.putInt(o + 4, (int) this.position);
            out.putInt(o + 8, this.aabb);
            out.putInt(o + 12, this.geometryPtr + this.offsets[0]);

            out.putInt(o + 16, (this.offsets[1] - this.offsets[0]) | ((this.offsets[2] - this.offsets[1]) << 16));
            out.putInt(o + 20, (this.offsets[3] - this.offsets[2]) | ((this.offsets[4] - this.offsets[3]) << 16));
            out.putInt(o + 24, (this.offsets[5] - this.offsets[4]) | ((this.offsets[6] - this.offsets[5]) << 16));
            out.putInt(o + 28, (this.offsets[7] - this.offsets[6]) | ((this.itemCount - this.offsets[7]) << 16));
        }
    }
}
