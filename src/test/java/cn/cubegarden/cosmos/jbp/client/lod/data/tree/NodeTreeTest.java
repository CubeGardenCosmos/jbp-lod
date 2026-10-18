package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeTreeTest {
    private static final long ROOT = SectionPos.pack(4, 3, 0, -2);

    private final LongArrayList meshRequests = new LongArrayList();
    private final SectionUpdateRouter router = new SectionUpdateRouter();
    private final SectionGeometryManager geometry = new SectionGeometryManager(1024, 1L << 20);
    private final IntArrayList added = new IntArrayList();
    private final IntArrayList removed = new IntArrayList();
    private final NodeManager manager;

    NodeTreeTest() {
        router.setCallbacks(meshRequests::add, position -> {
        }, (position, existence) -> {
        });
        manager = new NodeManager(1024, geometry, router);
        manager.setTLNCallbacks(added::add, removed::add);
    }

    static BuiltSection section(long position, int childExistence, int quads) {
        int[] offsets = new int[8];
        for (int i = 0; i < 8; i++) {
            offsets[i] = Math.min(quads, i);
        }
        return new BuiltSection(position, (byte) childExistence, 0x1234, new byte[quads * 8], offsets);
    }

    private int[] nodeWords(int nodeId) {
        ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        manager.writeNode(nodeId, bytes, 0);
        return new int[]{bytes.getInt(0), bytes.getInt(4), bytes.getInt(8), bytes.getInt(12)};
    }

    @Test
    void positionPackingMatchesUpstreamLayoutAndChildIndices() {
        long pos = SectionPos.pack(2, -5, 17, 123456);
        assertEquals(2, SectionPos.level(pos));
        assertEquals(-5, SectionPos.x(pos));
        assertEquals(17, SectionPos.y(pos));
        assertEquals(123456, SectionPos.z(pos));
        assertEquals((2L << 60) | (17L << 52) | (123456L << 28) | ((long) (-5 & 0xFFFFFF) << 4), pos);
        for (int child = 0; child < 8; child++) {
            long childPos = SectionPos.childPos(pos, child);
            assertEquals(1, SectionPos.level(childPos));
            assertEquals(child, SectionPos.childIndex(childPos));
            assertEquals(pos, SectionPos.parentPos(childPos));
        }
    }

    @Test
    void topLevelRequestBecomesLeafNodeWithUpstreamGpuEncoding() {
        manager.insertTopLevelNode(ROOT);
        assertEquals(LongArrayList.of(ROOT), meshRequests);
        assertTrue(added.isEmpty());

        manager.processGeometryResult(section(ROOT, 0b101, 3));
        assertEquals(IntArrayList.of(0), added);
        assertEquals(1, manager.getNodeCount());
        assertEquals(0, manager.nodeIdAt(ROOT));

        int[] words = nodeWords(0);
        assertEquals((int) (ROOT >> 32), words[0]);
        assertEquals((int) ROOT, words[1]);
        //mesh pointer 0 in the low 24 bits, flags (no request, count-1 = 0) in the top byte
        assertEquals(0, words[2]);
        //null child pointer (NULL_NODE = 0xFFFFFF), flags 0
        assertEquals(0xFFFFFF, words[3]);
        assertEquals(1, geometry.getSectionCount());
        manager.verifyIntegrity();
    }

    @Test
    void leafRequestExpandsToInnerNodeAndRemovalReleasesEverything() {
        manager.insertTopLevelNode(ROOT);
        manager.processGeometryResult(section(ROOT, 0b11, 2));
        meshRequests.clear();

        manager.processRequest(ROOT);
        long childA = SectionPos.childPos(ROOT, 0);
        long childB = SectionPos.childPos(ROOT, 1);
        assertEquals(LongArrayList.of(childA, childB), meshRequests);
        int[] pending = nodeWords(0);
        assertEquals(1, pending[2] >>> 24 & 1, "request-in-flight flag is raised while children are requested");

        manager.processGeometryResult(section(childA, 0b1, 1));
        manager.processGeometryResult(section(childB, 0b1, 1));

        int[] inner = nodeWords(0);
        int childPtr = inner[3] & 0xFFFFFF;
        assertEquals(1, childPtr, "children are allocated consecutively after the root node");
        //flags byte: bit2..4 = child count-1 (1), bit5 = all children are leaves (cleaner eligible)
        assertEquals(0b100100, inner[2] >>> 24, "two leaf children, no request in flight");
        assertEquals(0, inner[3] >>> 24);
        assertEquals(3, manager.getNodeCount());
        manager.verifyIntegrity();

        manager.removeTopLevelNode(ROOT);
        assertEquals(IntArrayList.of(0), removed);
        assertEquals(0, manager.getNodeCount());
        assertEquals(0, geometry.getSectionCount());
        assertEquals(0, router.watchedCount());
        assertEquals(0, manager.getActiveSectionCount());
        manager.verifyIntegrity();
    }

    @Test
    void childExistenceChangeAddsAndRemovesChildrenWithCompaction() {
        manager.insertTopLevelNode(ROOT);
        manager.processGeometryResult(section(ROOT, 0b11, 2));
        manager.processRequest(ROOT);
        long childA = SectionPos.childPos(ROOT, 0);
        long childB = SectionPos.childPos(ROOT, 1);
        manager.processGeometryResult(section(childA, 0b1, 1));
        manager.processGeometryResult(section(childB, 0b1, 1));
        manager.verifyIntegrity();

        //Child B disappears: inner node is compacted down to one child
        manager.processChildChange(ROOT, (byte) 0b01);
        assertEquals(2, manager.getNodeCount());
        manager.verifyIntegrity();

        //A new child C appears: creates a request that finishes when its geometry arrives
        long childC = SectionPos.childPos(ROOT, 4);
        meshRequests.clear();
        manager.processChildChange(ROOT, (byte) 0b10001);
        assertEquals(LongArrayList.of(childC), meshRequests);
        manager.processGeometryResult(section(childC, 0b1, 1));
        assertEquals(3, manager.getNodeCount());
        manager.verifyIntegrity();

        manager.removeTopLevelNode(ROOT);
        assertEquals(0, manager.getNodeCount());
        manager.verifyIntegrity();
    }

    @Test
    void emptyGeometryUsesEmptySentinelAndNoSlot() {
        manager.insertTopLevelNode(ROOT);
        manager.processGeometryResult(BuiltSection.emptyWithChildren(ROOT, (byte) 1));
        int[] words = nodeWords(0);
        assertEquals(0xFFFFFE, words[2] & 0xFFFFFF, "EMPTY mesh sentinel");
        assertEquals(0, geometry.getSectionCount());
        manager.verifyIntegrity();
    }

    @Test
    void geometryRemovalOfInnerNodeNullsMeshAndFreesSlot() {
        manager.insertTopLevelNode(ROOT);
        manager.processGeometryResult(section(ROOT, 0b1, 1));
        manager.processRequest(ROOT);
        long child = SectionPos.childPos(ROOT, 0);
        manager.processGeometryResult(section(child, 0, 1));
        int before = geometry.getSectionCount();

        manager.removeNodeGeometry(ROOT);
        assertEquals(before - 1, geometry.getSectionCount());
        assertEquals(0xFFFFFF, nodeWords(0)[2] & 0xFFFFFF, "NULL mesh sentinel");
        IntOpenHashSet updates = manager.takeNodeUpdates();
        assertTrue(updates.contains(0));
        assertFalse(manager.hasNodeUpdates());
        manager.verifyIntegrity();
    }
}
