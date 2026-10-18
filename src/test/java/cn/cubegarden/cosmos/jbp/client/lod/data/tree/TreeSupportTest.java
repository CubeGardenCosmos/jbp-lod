package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import cn.cubegarden.cosmos.jbp.client.lod.data.SectionMeta;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeSupportTest {
    @Test
    void bitSetAllocatesLowestFreeAndTracksMaxIndexAndConsecutiveRuns() {
        HierarchicalBitSet set = new HierarchicalBitSet(1000);
        assertEquals(0, set.allocateNext());
        assertEquals(1, set.allocateNext());
        assertEquals(2, set.allocateNext());
        assertTrue(set.free(1));
        assertFalse(set.free(1));
        assertEquals(1, set.allocateNext());
        assertEquals(2, set.getMaxIndex());
        assertEquals(3, set.allocateNextConsecutiveCounted(5));
        assertEquals(7, set.getMaxIndex() - 0);
        set.free(7);
        assertEquals(6, set.getMaxIndex());
        assertEquals(8, set.getCount());
        assertTrue(set.isSet(3));
    }

    @Test
    void bitSetHonoursLimit() {
        HierarchicalBitSet set = new HierarchicalBitSet(2);
        assertEquals(0, set.allocateNext());
        assertEquals(1, set.allocateNext());
        assertEquals(-1, set.allocateNext());
    }

    @Test
    void arenaReusesAndMergesFreedBlocksAndEnforcesLimit() {
        AllocationArena arena = new AllocationArena();
        arena.setLimit(512);
        long a = arena.alloc(128);
        long b = arena.alloc(128);
        long c = arena.alloc(128);
        assertEquals(0, a);
        assertEquals(128, b);
        assertEquals(256, c);
        assertEquals(128, arena.free(b));
        assertEquals(128, arena.alloc(64));//first fit reuse of the hole
        assertEquals(192, arena.alloc(64));
        assertEquals(384, arena.alloc(128));
        assertEquals(AllocationArena.SIZE_LIMIT, arena.alloc(128));
        arena.free(a);
        arena.free(128);
        arena.free(192);
        assertEquals(0, arena.alloc(256), "adjacent free blocks merge");
    }

    @Test
    void objectAllocationListReusesReleasedIds() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new, 100);
        int a = list.put("a");
        int b = list.put("b");
        list.release(a);
        assertEquals(a, list.put("c"));
        assertEquals("b", list.get(b));
        assertEquals(2, list.count());
        assertThrows(IllegalArgumentException.class, () -> list.release(50));
    }

    @Test
    void geometryManagerEncodesSectionMetaAndRoundsHeapTo128Quads() {
        SectionGeometryManager manager = new SectionGeometryManager(16, 1L << 16);
        long position = SectionPos.pack(3, 4, 1, 9);
        int[] offsets = {0, 1, 3, 4, 6, 7, 9, 10};
        byte[] quads = new byte[12 * 8];
        for (int i = 0; i < quads.length; i++) {
            quads[i] = (byte) i;
        }
        int slot = manager.uploadSection(new BuiltSection(position, (byte) 3, 0xABCD, quads, offsets));
        assertEquals(0, slot);
        int second = manager.uploadSection(new BuiltSection(SectionPos.pack(3, 5, 1, 9), (byte) 0, 7, new byte[8], new int[8]));
        assertEquals(1, second);

        ByteBuffer meta = ByteBuffer.allocate(SectionMeta.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        manager.writeMetadata(slot, meta, 0);
        assertEquals((int) (position >> 32), meta.getInt(0));
        assertEquals((int) position, meta.getInt(4));
        assertEquals(0xABCD, meta.getInt(8));
        assertEquals(0, meta.getInt(12), "first quad = heap pointer + offsets[0]");
        assertEquals(1 | (2 << 16), meta.getInt(16));
        assertEquals(1 | (2 << 16), meta.getInt(20));
        assertEquals(1 | (2 << 16), meta.getInt(24));
        assertEquals(1 | (2 << 16), meta.getInt(28), "last count is itemCount - offsets[7]");
        assertEquals(3, (meta.getInt(0) >>> 28), "detail level lives in the top nibble of word a.x");

        manager.writeMetadata(second, meta, 0);
        assertEquals(128, meta.getInt(12), "second allocation starts after the first 128-quad block");

        var uploads = manager.takeUploads();
        assertEquals(2, uploads.size());
        assertArrayEquals(quads, uploads.get(0));
        assertTrue(manager.takeUploads().isEmpty());
        assertEquals(IntOpenHashSet.of(0, 1), manager.takeInvalidatedIds());

        manager.removeSection(slot);
        manager.writeMetadata(slot, meta, 0);
        for (int i = 0; i < SectionMeta.BYTE_SIZE; i += 4) {
            assertEquals(0, meta.getInt(i));
        }
        assertEquals(IntOpenHashSet.of(0), manager.takeHeapRemovals());
        assertEquals(128 * 8, manager.getGeometryUsedBytes());
    }

    @Test
    void geometryManagerMetadataRoundTripsThroughSectionMetaAccessors() {
        SectionGeometryManager manager = new SectionGeometryManager(4, 1 << 16);
        int[] offsets = {0, 2, 2, 5, 5, 8, 8, 11};
        manager.uploadSection(new BuiltSection(SectionPos.pack(2, 1, 1, 1), (byte) 0, 5, new byte[13 * 8], offsets));
        ByteBuffer bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        manager.writeMetadata(0, bytes, 0);
        SectionMeta meta = new SectionMeta(bytes.getInt(0), bytes.getInt(4), bytes.getInt(8), bytes.getInt(12),
                bytes.getInt(16), bytes.getInt(20), bytes.getInt(24), bytes.getInt(28));
        int[] expected = {2, 0, 3, 0, 3, 0, 3, 2};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], meta.quadCount(i), "face range " + i);
        }
        assertEquals(2, meta.detail());
    }

    @Test
    void builtSectionRejectsInvalidOffsets() {
        assertThrows(IllegalArgumentException.class,
                () -> new BuiltSection(0, (byte) 0, 0, new byte[16], new int[]{0, 0, 0, 0, 0, 0, 5, 5}));
        assertThrows(IllegalArgumentException.class,
                () -> new BuiltSection(0, (byte) 0, 0, new byte[12], new int[8]));
        assertTrue(BuiltSection.empty(5).isEmpty());
    }

    @Test
    void deltaMergeKeepsLaterWritesCancelsTopLevelAndDropsFreedUploads() {
        SectionDelta first = new SectionDelta();
        first.nodeWrites().put(1, new byte[]{1});
        first.quadUploads().put(0, new byte[]{9});
        first.quadUploads().put(128, new byte[]{8});
        first.topLevelDelta().add(5 | SectionDelta.ADD_FLAG);
        first.cleanerOps().add(5 | SectionDelta.ADD_FLAG);
        first.cleanerOps().add(6);
        first.setStatistics(5, 1, 1, 8);

        SectionDelta second = new SectionDelta();
        second.nodeWrites().put(1, new byte[]{2});
        second.quadUploads().put(256, new byte[]{7});
        second.topLevelDelta().add(5);//removal cancels the pending add
        second.cleanerOps().add(5);//free replaces the pending reset
        second.cleanerOps().add(6 | SectionDelta.ADD_FLAG);
        second.setStatistics(2, 3, 4, 16);

        first.mergeFrom(second, IntOpenHashSet.of(0));
        assertArrayEquals(new byte[]{2}, first.nodeWrites().get(1));
        assertNull(first.quadUploads().get(0), "upload at a freed heap address is dropped");
        assertNotNull(first.quadUploads().get(128));
        assertNotNull(first.quadUploads().get(256));
        assertTrue(first.topLevelDelta().isEmpty());
        assertEquals(IntOpenHashSet.of(5, 6 | SectionDelta.ADD_FLAG), first.cleanerOps());
        assertEquals(2, first.maxNodeId());
        assertEquals(3, first.nodeCount());
        assertEquals(16, first.geometryUsedBytes());
    }

    @Test
    void routerReportsFirstBlockWatchAndFullRemoval() {
        Set<Long> initial = new HashSet<>();
        SectionUpdateRouter router = new SectionUpdateRouter();
        long[] childEvents = new long[2];
        router.setCallbacks(initial::add, position -> childEvents[0]++, (position, existence) -> childEvents[1] = existence);
        long position = SectionPos.pack(1, 2, 3, 4);
        assertTrue(router.watch(position, SectionPos.DEFAULT_UPDATE_FLAGS));
        assertTrue(initial.contains(position));
        assertFalse(router.watch(position, SectionPos.DEFAULT_UPDATE_FLAGS));
        router.forwardEvent(position, SectionPos.UPDATE_TYPE_CHILD_EXISTENCE_BIT, (byte) 7);
        router.forwardEvent(position, SectionPos.UPDATE_TYPE_BLOCK_BIT, (byte) 0);
        assertEquals(7, childEvents[1]);
        assertEquals(1, childEvents[0]);
        assertFalse(router.unwatch(position, SectionPos.UPDATE_TYPE_BLOCK_BIT));
        assertEquals(SectionPos.UPDATE_TYPE_CHILD_EXISTENCE_BIT, router.get(position));
        assertTrue(router.unwatch(position, SectionPos.UPDATE_TYPE_CHILD_EXISTENCE_BIT));
        assertThrows(IllegalStateException.class, () -> router.unwatch(position, 1));
    }

    @Test
    void renderDistanceTrackerMaintainsCircleOfTopLevelColumns() {
        LongOpenHashSet loaded = new LongOpenHashSet();
        RenderDistanceTracker tracker = new RenderDistanceTracker(Integer.MAX_VALUE, -1, 0, loaded::add, loaded::remove);
        tracker.setRenderDistance(3);
        assertTrue(tracker.setCenterAndProcess(0, 0));
        int columns = expectedColumns(3, 0, 0).size();
        assertEquals(columns * 2, loaded.size(), "two Y sections per column");

        //Move 1000 blocks east/south (several 512-block columns); the loaded set equals a fresh circle there
        tracker.setCenterAndProcess(1000, 1500);
        int cx = 1000 >> 9;
        int cz = 1500 >> 9;
        LongOpenHashSet expected = new LongOpenHashSet();
        for (long column : expectedColumns(3, cx, cz)) {
            int x = (int) (column & 0xFFFFFFFFL);
            int z = (int) (column >>> 32);
            for (int y = -1; y <= 0; y++) {
                expected.add(SectionPos.pack(4, x, y, z));
            }
        }
        assertEquals(expected, loaded);
    }

    private static Set<Long> expectedColumns(int radius, int centerX, int centerZ) {
        Set<Long> out = new HashSet<>();
        for (int i = -radius; i <= radius; i++) {
            int d = (int) Math.sqrt(radius * radius - i * i);
            for (int z = centerZ - d; z <= centerZ + d; z++) {
                out.add(Integer.toUnsignedLong(centerX + i) | (Integer.toUnsignedLong(z) << 32));
            }
        }
        return out;
    }
}
