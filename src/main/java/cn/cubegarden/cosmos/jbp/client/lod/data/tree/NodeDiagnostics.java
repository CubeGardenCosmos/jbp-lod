package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Point-in-time node manager diagnostics, captured on the node worker and read by the stats logger.
 *
 * @param droppedInactiveGeometry geometry results for positions that are no longer active (silently discarded)
 * @param droppedUnwatchedGeometry geometry results for positions not watching BLOCK updates (discarded)
 * @param evictionsApplied cleaner geometry removals applied to live inner or leaf nodes
 * @param childWatchesSkippedInitialMesh child positions added to a request whose BLOCK bit was already set, so no
 *                                       initial mesh was requested for them by the watch
 * @param singleRequests pending single-node requests
 * @param singleAwaitingMesh pending single-node requests whose own mesh has not arrived
 * @param childRequests pending child requests
 * @param childAwaitingMeshes child requests with at least one child mesh still missing
 * @param missingChildMeshes child meshes still missing across all child requests
 * @param missingChildrenUnwatched missing child meshes whose position has no BLOCK watch (never meshed)
 * @param satisfiedUnfinishedRequests child requests with a non-empty child mask whose children are all present but
 *                                    that were not finished (should always be 0)
 * @param parkedAwaitingChildExistence child requests with an empty child mask: a top-level leaf whose childExistence
 *                                    is 0 keeps its request parked until a child existence change finishes it
 */
public record NodeDiagnostics(long droppedInactiveGeometry, long droppedUnwatchedGeometry, long evictionsApplied,
                              long childWatchesSkippedInitialMesh, int singleRequests, int singleAwaitingMesh,
                              int childRequests, int childAwaitingMeshes, int missingChildMeshes,
                              int missingChildrenUnwatched, int satisfiedUnfinishedRequests,
                              int parkedAwaitingChildExistence) {
    public static final NodeDiagnostics EMPTY = new NodeDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
