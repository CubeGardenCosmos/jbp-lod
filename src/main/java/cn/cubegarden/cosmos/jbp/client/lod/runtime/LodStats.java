package cn.cubegarden.cosmos.jbp.client.lod.runtime;

import cn.cubegarden.cosmos.jbp.client.lod.data.tree.NodeDiagnostics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-scoped counters for the periodic {@code [JBP-LOD] stats:} log. Every field is written from a worker
 * thread and read from the log thread, so all access is atomic or volatile; the node and model values are
 * snapshots refreshed by their owners rather than live reads of single-threaded state.
 */
public final class LodStats {
    private final AtomicLong chunksCaptured = new AtomicLong();
    private final AtomicLong sectionsIngested = new AtomicLong();
    private final AtomicLong sectionsMeshed = new AtomicLong();
    private final AtomicLong sectionsMeshedNonEmpty = new AtomicLong();
    private final AtomicLong totalQuads = new AtomicLong();
    private final AtomicLong lightSections = new AtomicLong();
    private final AtomicLong lightNoSkyLayer = new AtomicLong();
    private final AtomicLong lightNoBlockLayer = new AtomicLong();
    private final AtomicLong lightSkyZero = new AtomicLong();
    private final AtomicLong lightSkyFull = new AtomicLong();
    private final AtomicLong lightSkyDefaulted = new AtomicLong();
    private final AtomicLong lightDeferred = new AtomicLong();
    private volatile int nodes;
    private volatile int metadataSlots;
    private volatile int pendingRequests;
    private volatile int modelsPublished;
    private volatile int modelsPending;
    private volatile int distinctMeshed;
    private volatile int meshWaiting;
    private volatile int meshInFlight;
    private volatile int slotCapacity;
    private volatile long geometryUsedBytes;
    private volatile long geometryCapacityBytes;
    private volatile String queueDepths = "";
    private volatile NodeDiagnostics nodeDiagnostics = NodeDiagnostics.EMPTY;

    /**
     * Per ingested section: whether the raw light layers were present, whether sky light was all-0/all-15 after
     * the engine's missing-layer default was applied, and whether that default had to be used.
     */
    public void lightSection(boolean skyLayer, boolean blockLayer, boolean skyZero, boolean skyFull,
                             boolean skyDefaulted) {
        lightSections.incrementAndGet();
        if (!skyLayer) lightNoSkyLayer.incrementAndGet();
        if (!blockLayer) lightNoBlockLayer.incrementAndGet();
        if (skyZero) lightSkyZero.incrementAndGet();
        if (skyFull) lightSkyFull.incrementAndGet();
        if (skyDefaulted) lightSkyDefaulted.incrementAndGet();
    }

    /** A chunk capture was deferred because its light packet had not been applied yet; it stays dirty. */
    public void lightDeferred() {
        lightDeferred.incrementAndGet();
    }

    public void chunkCaptured() {
        chunksCaptured.incrementAndGet();
    }

    public void sectionIngested() {
        sectionsIngested.incrementAndGet();
    }

    public void sectionMeshed(int quadCount) {
        sectionsMeshed.incrementAndGet();
        if (quadCount > 0) {
            sectionsMeshedNonEmpty.incrementAndGet();
            totalQuads.addAndGet(quadCount);
        }
    }

    public void nodeSnapshot(int nodeCount, int metadataSlotCount, int requestCount) {
        this.nodes = nodeCount;
        this.metadataSlots = metadataSlotCount;
        this.pendingRequests = requestCount;
    }

    public void modelSnapshot(int published, int pending) {
        this.modelsPublished = published;
        this.modelsPending = pending;
    }

    /** Mesh-side snapshot: distinct positions ever meshed, positions waiting on models, meshes in flight. */
    public void meshSnapshot(int distinct, int waiting, int inFlight) {
        this.distinctMeshed = distinct;
        this.meshWaiting = waiting;
        this.meshInFlight = inFlight;
    }

    /** Geometry heap usage and metadata slot capacity snapshot. */
    public void geometrySnapshot(long usedBytes, long capacityBytes, int slotCapacity) {
        this.geometryUsedBytes = usedBytes;
        this.geometryCapacityBytes = capacityBytes;
        this.slotCapacity = slotCapacity;
    }

    /** Input queue depths snapshot, already formatted by the node service. */
    public void queueSnapshot(String depths) {
        this.queueDepths = depths;
    }

    /** Node request and drop/eviction diagnostics snapshot from the node service. */
    public void nodeDiagnosticsSnapshot(NodeDiagnostics diagnostics) {
        this.nodeDiagnostics = diagnostics;
    }

    /** One line; the field names are the contract the user-facing docs refer to. */
    public String format() {
        NodeDiagnostics d = this.nodeDiagnostics;
        return "chunks captured=" + chunksCaptured.get()
                + " sections ingested=" + sectionsIngested.get()
                + " meshed=" + sectionsMeshed.get()
                + " meshedNonEmpty=" + sectionsMeshedNonEmpty.get()
                + " quads=" + totalQuads.get()
                + " light[sections=" + lightSections.get() + " noSkyLayer=" + lightNoSkyLayer.get()
                + " noBlockLayer=" + lightNoBlockLayer.get() + " skyZero=" + lightSkyZero.get()
                + " skyFull=" + lightSkyFull.get() + " skyDefaulted=" + lightSkyDefaulted.get()
                + " deferred=" + lightDeferred.get() + "]"
                + " nodes=" + nodes
                + " metadataSlots=" + metadataSlots
                + " models published=" + modelsPublished + " pending=" + modelsPending
                + " pendingRequests=" + pendingRequests
                + " remeshes=" + Math.max(0, sectionsMeshed.get() - distinctMeshed)
                + " meshWaiting=" + meshWaiting + " meshInFlight=" + meshInFlight
                + " slots=" + metadataSlots + "/" + slotCapacity
                + " geometryBytes=" + geometryUsedBytes + "/" + geometryCapacityBytes
                + " queues[" + queueDepths + "]"
                + " requests[single=" + d.singleRequests() + " singleNoMesh=" + d.singleAwaitingMesh()
                + " child=" + d.childRequests() + " childAwaiting=" + d.childAwaitingMeshes()
                + " missingChildMeshes=" + d.missingChildMeshes() + " missingUnwatched=" + d.missingChildrenUnwatched()
                + " satisfiedUnfinished=" + d.satisfiedUnfinishedRequests() + " parked=" + d.parkedAwaitingChildExistence() + "]"
                + " dropped[inactive=" + d.droppedInactiveGeometry() + " unwatched=" + d.droppedUnwatchedGeometry() + "]"
                + " evictions=" + d.evictionsApplied() + " childWatchNoMesh=" + d.childWatchesSkippedInitialMesh();
    }
}
