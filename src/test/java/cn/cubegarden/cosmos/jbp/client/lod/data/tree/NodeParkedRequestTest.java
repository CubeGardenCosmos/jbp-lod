package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the "satisfied but unfinished" child request state. A top-level leaf whose childExistence is 0 gets a
 * child request with an empty mask (upstream MCRcortex/voxy keeps it parked on purpose). The request is satisfied
 * by definition, so it must be reported as parked, not as an unfinished request that is missing a finish.
 */
final class NodeParkedRequestTest {
    private static final long ROOT = SectionPos.pack(4, 3, 0, -2);

    private final LongArrayList meshRequests = new LongArrayList();
    private final SectionUpdateRouter router = new SectionUpdateRouter();
    private final SectionGeometryManager geometry = new SectionGeometryManager(1024, 1L << 20);
    private final NodeManager manager;

    NodeParkedRequestTest() {
        router.setCallbacks(meshRequests::add, position -> {
        }, (position, existence) -> {
        });
        manager = new NodeManager(1024, geometry, router);
        manager.setTLNCallbacks(id -> {
        }, id -> {
        });
    }

    @Test
    void zeroExistenceTopLevelLeafParksAnEmptyRequestWhichIsNotReportedAsUnfinished() {
        manager.insertTopLevelNode(ROOT);
        manager.processGeometryResult(NodeTreeTest.section(ROOT, 0, 1));
        meshRequests.clear();

        manager.processRequest(ROOT);

        assertTrue(meshRequests.isEmpty(), "no child has existence, so no child mesh is requested");
        NodeDiagnostics parked = manager.diagnosticsSnapshot();
        assertEquals(1, parked.childRequests());
        assertEquals(0, parked.missingChildMeshes());
        assertEquals(1, parked.parkedAwaitingChildExistence());
        assertEquals(0, parked.satisfiedUnfinishedRequests());
        manager.verifyIntegrity();

        //The parked request is released by a child existence change, not by a mesh result.
        manager.processChildChange(ROOT, (byte) 0b1);
        long childA = SectionPos.childPos(ROOT, 0);
        assertEquals(LongArrayList.of(childA), meshRequests);
        NodeDiagnostics awaiting = manager.diagnosticsSnapshot();
        assertEquals(1, awaiting.childRequests());
        assertEquals(1, awaiting.childAwaitingMeshes());
        assertEquals(0, awaiting.parkedAwaitingChildExistence());
        assertEquals(0, awaiting.satisfiedUnfinishedRequests());

        manager.processGeometryResult(NodeTreeTest.section(childA, 0, 1));
        NodeDiagnostics done = manager.diagnosticsSnapshot();
        assertEquals(0, done.childRequests());
        assertEquals(0, done.satisfiedUnfinishedRequests());
        assertEquals(0, done.parkedAwaitingChildExistence());
        manager.verifyIntegrity();
    }
}
