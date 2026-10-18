package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NodeDiagnosticsTest {
    @Test
    void missingMaskTracksChildrenWithoutMeshResult() {
        NodeChildRequest request = new NodeChildRequest(0L);
        request.addChildRequirement(0);
        request.addChildRequirement(3);
        assertEquals(0b1001, request.missingMask());

        request.setChildMesh(0, 7);
        assertEquals(0b1000, request.missingMask());
        assertEquals(false, request.isSatisfied());

        request.setChildMesh(3, -1);
        assertEquals(0, request.missingMask());
        assertEquals(true, request.isSatisfied());
    }

    @Test
    void missingMaskIgnoresChildrenThatWereUnrequired() {
        NodeChildRequest request = new NodeChildRequest(0L);
        request.addChildRequirement(5);
        assertEquals(0b100000, request.missingMask());

        request.removeAndUnRequire(5);
        assertEquals(0, request.missingMask());
    }

    @Test
    void forEachAllocatedVisitsOnlyLiveEntriesWithTheirIds() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        int a = list.put("a");
        int b = list.put("b");
        int c = list.put("c");
        list.release(b);

        List<String> seen = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        list.forEachAllocated((value, id) -> {
            seen.add(value);
            ids.add(id);
        });

        assertEquals(List.of("a", "c"), seen);
        assertEquals(List.of(a, c), ids);
        assertEquals(2, list.count());
    }

    @Test
    void forEachAllocatedOnEmptyListVisitsNothing() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        int[] visits = new int[1];
        list.forEachAllocated((value, id) -> visits[0]++);
        assertEquals(0, visits[0]);
    }

    @Test
    void emptyDiagnosticsAreAllZero() {
        assertEquals(0L, NodeDiagnostics.EMPTY.droppedInactiveGeometry());
        assertEquals(0, NodeDiagnostics.EMPTY.missingChildMeshes());
    }
}
