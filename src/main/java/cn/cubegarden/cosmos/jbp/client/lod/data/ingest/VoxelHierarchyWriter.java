package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;

import java.util.Arrays;
import java.util.function.LongToIntFunction;

/**
 * Read-modify-write of the five stored LoD levels for one captured 16^3 chunk section.
 *
 * <p>Extracted unchanged from {@code LiveSectionProducer.writeHierarchy} so live capture and the
 * background world importer share one implementation. The whole hierarchy update of a section is
 * serialized: two threads writing different octants of the same stored section would otherwise
 * read the same payload, modify their own copy and lose the other's octant on the last write.
 * Chunk parsing/voxelization stays parallel; only the storage read-modify-write (and its six
 * neighbour notifications per changed level) is serialized here.
 *
 * <p>{@link Store} and {@link Updates} are synchronous: callers run them on a worker thread. The
 * production adapters block on the session's storage executor; tests use in-memory maps.
 */
public final class VoxelHierarchyWriter {
    /** Synchronous section payload access. Payloads are {@link StoredVoxelSection#BYTES} long. */
    public interface Store {
        byte[] get(long key);

        void put(long key, byte[] encoded);
    }

    /** Node-manager notifications for a changed section and its six same-level neighbours. */
    public interface Updates {
        void sectionUpdated(long position, int updateTypes, byte children);
    }

    private final Store store;
    private final Updates updates;
    private final LongToIntFunction opacity;

    public VoxelHierarchyWriter(Store store, Updates updates, LongToIntFunction opacity) {
        this.store = store;
        this.updates = updates;
        this.opacity = opacity;
    }

    /**
     * Inserts {@code source} (16^3 mapper words, {@code x | z << 4 | y << 8}) into every stored level
     * and propagates the downsampled mip through {@link SectionPos#MAX_LOD_LAYER}.
     *
     * @param chunkSectionX chunk section X (16-block units)
     * @param chunkSectionY chunk section Y (16-block units)
     * @param chunkSectionZ chunk section Z (16-block units)
     * @return true when at least one stored level changed
     */
    public synchronized boolean write(int chunkSectionX, int chunkSectionY, int chunkSectionZ,
                                      long[] source) {
        int x = chunkSectionX, y = chunkSectionY, z = chunkSectionZ;
        boolean changed = false;
        for (int level = 0; level <= SectionPos.MAX_LOD_LAYER; level++) {
            long key = SectionPos.pack(level, x >> 1, y >> 1, z >> 1);
            byte[] stored = this.store.get(key);
            long[] target = stored == null ? new long[StoredVoxelSection.VOXELS]
                    : StoredVoxelSection.decode(stored);
            long[] before = stored == null ? null : target.clone();
            VoxelHierarchy.insert(target, source, level, x, y, z);
            // Unchanged levels skip the write and every notification: no watched node can see different voxels.
            if (before == null || !Arrays.equals(before, target)) {
                changed = true;
                this.store.put(key, StoredVoxelSection.encode(target));
                byte children = VoxelHierarchy.children(target);
                if (level == 0 && children != 0) children = (byte) 0xFF;
                this.updates.sectionUpdated(key, SectionPos.DEFAULT_UPDATE_FLAGS, children);
                int sx = x >> 1, sy = y >> 1, sz = z >> 1;
                for (int axis = 0; axis < 3; axis++) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        long adjacent = SectionPos.pack(level, sx + (axis == 0 ? sign : 0),
                                sy + (axis == 1 ? sign : 0), sz + (axis == 2 ? sign : 0));
                        this.updates.sectionUpdated(adjacent, SectionPos.UPDATE_TYPE_BLOCK_BIT, (byte) 0);
                    }
                }
            }
            if (level < SectionPos.MAX_LOD_LAYER) {
                source = VoxelHierarchy.downsample(source, 16 >> level, this.opacity);
            }
            x >>= 1;
            y >>= 1;
            z >>= 1;
        }
        return changed;
    }
}
