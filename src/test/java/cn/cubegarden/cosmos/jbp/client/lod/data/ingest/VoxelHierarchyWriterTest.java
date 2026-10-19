package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelHierarchyWriterTest {
    private static final int AIR = 0;

    private static final class MemoryStore implements VoxelHierarchyWriter.Store {
        final Map<Long, byte[]> sections = new HashMap<>();
        int puts;

        @Override
        public byte[] get(long key) {
            return this.sections.get(key);
        }

        @Override
        public void put(long key, byte[] encoded) {
            this.puts++;
            this.sections.put(key, encoded.clone());
        }
    }

    private record Update(long position, int types, byte children) {
    }

    private static long voxel(int block, int light) {
        return IdMapper.composeMappingId((byte) light, block, 0);
    }

    private static long[] filled(int block) {
        long[] words = new long[4096];
        Arrays.fill(words, voxel(block, 15));
        return words;
    }

    @Test void writesAllFiveLevelsAndNotifiesEachChangedLevelWithItsNeighbours() {
        MemoryStore store = new MemoryStore();
        List<Update> updates = new ArrayList<>();
        var writer = new VoxelHierarchyWriter(store, (position, types, children) ->
                updates.add(new Update(position, types, children)), word -> 15);

        assertTrue(writer.write(2, 3, 4, filled(7)));

        for (int level = 0; level <= SectionPos.MAX_LOD_LAYER; level++) {
            long key = SectionPos.pack(level, 2 >> (level + 1), 3 >> (level + 1), 4 >> (level + 1));
            assertNotNull(store.sections.get(key), "level " + level);
            long[] stored = StoredVoxelSection.decode(store.sections.get(key));
            assertTrue(Arrays.stream(stored).anyMatch(word -> word != AIR), "level " + level + " non-empty");
        }
        // five changed levels * (self + six neighbours)
        assertEquals(5 * 7, updates.size());
        Update self = updates.stream().filter(update -> SectionPos.level(update.position) == 0
                && SectionPos.x(update.position) == 1 && SectionPos.y(update.position) == 1
                && SectionPos.z(update.position) == 2).findFirst().orElseThrow();
        assertEquals(SectionPos.DEFAULT_UPDATE_FLAGS, self.types);
        assertEquals((byte) 0xFF, self.children, "level 0 forces all eight child bits for a non-empty section");
        assertTrue(updates.stream().anyMatch(update -> update.types == SectionPos.UPDATE_TYPE_BLOCK_BIT
                && SectionPos.level(update.position) == 0 && SectionPos.x(update.position) == 2));
    }

    @Test void octantPlacementMatchesStoredVoxelIndexing() {
        MemoryStore store = new MemoryStore();
        var writer = new VoxelHierarchyWriter(store, (p, t, c) -> { }, word -> 15);
        long[] source = new long[4096];
        source[1 | (2 << 4) | (3 << 8)] = voxel(9, 15);
        writer.write(1, 1, 1, source);

        long[] level0 = StoredVoxelSection.decode(store.sections.get(SectionPos.pack(0, 0, 0, 0)));
        // chunk octant (1,1,1): local x = 16+1, local y = 16+3, local z = 16+2
        assertEquals(voxel(9, 15), level0[(17) | (18 << 5) | (19 << 10)]);
    }

    @Test void identicalWriteDoesNoStorageWorkAndNoNotifications() {
        MemoryStore store = new MemoryStore();
        List<Update> updates = new ArrayList<>();
        var writer = new VoxelHierarchyWriter(store, (p, t, c) -> updates.add(new Update(p, t, c)),
                word -> 15);
        long[] source = filled(3);
        writer.write(2, 3, 4, source);
        int putsAfterFirst = store.puts;
        updates.clear();

        assertFalse(writer.write(2, 3, 4, source));
        assertEquals(putsAfterFirst, store.puts);
        assertTrue(updates.isEmpty());
    }

    @Test void firstAirWriteStoresEmptyPayloadAndSecondIsUnchanged() {
        MemoryStore store = new MemoryStore();
        var writer = new VoxelHierarchyWriter(store, (p, t, c) -> { }, word -> 15);
        assertTrue(writer.write(0, 0, 0, new long[4096]));
        assertFalse(writer.write(0, 0, 0, new long[4096]));
        assertEquals(5, store.sections.size());
    }

    @Test void concurrentChunksInTheSameStoredSectionKeepBothOctants() throws Exception {
        MemoryStore store = new MemoryStore();
        var writer = new VoxelHierarchyWriter(store, (p, t, c) -> { }, word -> 15);
        long[] left = new long[4096];
        left[1 | (2 << 4) | (3 << 8)] = voxel(4, 15);
        long[] right = new long[4096];
        right[5 | (6 << 4) | (7 << 8)] = voxel(5, 15);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a = executor.submit(() -> {
                start.await();
                return writer.write(0, 0, 0, left);
            });
            Future<Boolean> b = executor.submit(() -> {
                start.await();
                return writer.write(1, 0, 0, right);
            });
            start.countDown();
            assertTrue(a.get());
            assertTrue(b.get());
        } finally {
            executor.shutdownNow();
        }
        long[] level0 = StoredVoxelSection.decode(store.sections.get(SectionPos.pack(0, 0, 0, 0)));
        assertEquals(voxel(4, 15), level0[1 | (2 << 5) | (3 << 10)], "chunk (0,0,0) octant");
        assertEquals(voxel(5, 15), level0[(16 + 5) | (6 << 5) | (7 << 10)], "chunk (1,0,0) octant");
        assertArrayEquals(StoredVoxelSection.encode(level0), store.sections.get(SectionPos.pack(0, 0, 0, 0)));
    }

    /**
     * Regression for the shared-writer refactor: the pre-refactor {@code LiveSectionProducer.writeHierarchy}
     * inserted each level with the chunk section's own coordinates ({@code sectionX/Y/Z}), while the key used
     * the progressively shifted coordinates. Passing the shifted coordinates to {@link VoxelHierarchy#insert}
     * moved every level above 0 into the wrong octant and corrupted the mips. Compare both the stored payloads
     * and the exact notification sequence against that old implementation for several chunks that share
     * stored sections at every level, including a repeated write of the same section.
     */
    @Test void matchesPreRefactorLiveSectionProducerForMultiChunkMultiLevelInput() {
        LongToIntFunction opacity = word -> IdMapper.getLightId(word) & 15;
        MemoryStore actualStore = new MemoryStore();
        List<Update> actualUpdates = new ArrayList<>();
        var writer = new VoxelHierarchyWriter(actualStore,
                (position, types, children) -> actualUpdates.add(new Update(position, types, children)), opacity);
        MemoryStore expectedStore = new MemoryStore();
        List<Update> expectedUpdates = new ArrayList<>();

        // (0,0,0) and (1,0,0) merge into the same level-0..4 parents; the remaining chunks are spread over
        // positive and negative coordinates so every level observes other octants. (0,0,0) is written twice:
        // the second write must be a no-op, exactly like the old producer's unchanged-level fast path.
        int[][] chunks = {
                {0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 0, 1},
                {-1, 0, -1}, {2, 1, 3}, {5, -2, 7}, {8, 3, -5},
                {0, 0, 0},
        };
        // The final chunk repeats the first chunk's coordinates and payload, so its second write must take
        // the unchanged-level fast path (no store put, no notifications), exactly like the old producer.
        int[] seeds = {0, 1, 2, 3, 4, 5, 6, 7, 0};
        for (int i = 0; i < chunks.length; i++) {
            int x = chunks[i][0], y = chunks[i][1], z = chunks[i][2];
            long[] source = patternedSource(seeds[i]);
            writer.write(x, y, z, source);
            referenceWrite(expectedStore, expectedUpdates, x, y, z, source, opacity);
        }

        assertEquals(expectedUpdates, actualUpdates);
        assertEquals(expectedStore.sections.keySet(), actualStore.sections.keySet());
        for (long key : expectedStore.sections.keySet()) {
            assertArrayEquals(expectedStore.sections.get(key), actualStore.sections.get(key),
                    "stored payload " + SectionPos.pretty(key));
        }
    }

    /** Source with a deterministic mix of air, blocks and light so downsampling choices are non-trivial. */
    private static long[] patternedSource(int seed) {
        long[] words = new long[4096];
        for (int i = 0; i < words.length; i++) {
            int block = (i * 31 + seed * 17) % 7 == 0 ? 0 : 1 + ((i + seed) % 5);
            int light = (i + seed * 3) & 15;
            words[i] = voxel(block, light);
        }
        return words;
    }

    /**
     * Verbatim behaviour of {@code LiveSectionProducer.writeHierarchy} at d807e529 (before the shared
     * {@link VoxelHierarchyWriter} extraction), used as the oracle for the regression test.
     */
    private static void referenceWrite(MemoryStore store, List<Update> updates,
                                       int chunkSectionX, int chunkSectionY, int chunkSectionZ,
                                       long[] source, LongToIntFunction opacity) {
        int x = chunkSectionX, y = chunkSectionY, z = chunkSectionZ;
        int sectionX = chunkSectionX, sectionY = chunkSectionY, sectionZ = chunkSectionZ;
        for (int level = 0; level <= SectionPos.MAX_LOD_LAYER; level++) {
            long key = SectionPos.pack(level, x >> 1, y >> 1, z >> 1);
            byte[] stored = store.get(key);
            long[] target = stored == null ? new long[StoredVoxelSection.VOXELS]
                    : StoredVoxelSection.decode(stored);
            long[] before = stored == null ? null : target.clone();
            VoxelHierarchy.insert(target, source, level, sectionX, sectionY, sectionZ);
            if (before == null || !Arrays.equals(before, target)) {
                store.put(key, StoredVoxelSection.encode(target));
                byte children = VoxelHierarchy.children(target);
                if (level == 0 && children != 0) children = (byte) 0xFF;
                updates.add(new Update(key, SectionPos.DEFAULT_UPDATE_FLAGS, children));
                int sx = x >> 1, sy = y >> 1, sz = z >> 1;
                for (int axis = 0; axis < 3; axis++) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        long adjacent = SectionPos.pack(level,
                                sx + (axis == 0 ? sign : 0),
                                sy + (axis == 1 ? sign : 0),
                                sz + (axis == 2 ? sign : 0));
                        updates.add(new Update(adjacent, SectionPos.UPDATE_TYPE_BLOCK_BIT, (byte) 0));
                    }
                }
            }
            if (level < SectionPos.MAX_LOD_LAYER) {
                source = VoxelHierarchy.downsample(source, 16 >> level, opacity);
            }
            x >>= 1;
            y >>= 1;
            z >>= 1;
        }
    }
}
