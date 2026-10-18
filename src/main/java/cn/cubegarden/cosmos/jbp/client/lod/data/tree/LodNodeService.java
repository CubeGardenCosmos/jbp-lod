package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Off-thread owner of the {@link NodeManager}: consumes top-level node changes, child-existence changes,
 * mesh results, traversal request batches and cleaner removal batches, and publishes merged
 * {@link SectionDelta}s that the render thread polls without blocking.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/hierachical/AsyncNodeManager.java}. The input categories, processing order
 * (top-level nodes → child changes → throttled geometry gated by free geometry capacity → request batches
 * → removal batches), the 300-result/1000-KiB geometry limit and the merge-on-publish contract are kept.
 * Differences: the GL upload-stream/scatter/memcpy sync objects are replaced by heap-owned
 * {@link SectionDelta} values consumed by {@code LodNodeGpuBuffers}, and results are published via a short
 * monitor instead of VarHandle result caches.
 */
public final class LodNodeService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    /** Minimum free geometry before uploads are processed (upstream: 50 MB; scaled for small heaps). */
    private static final long GEOMETRY_RESERVE_BYTES = 50_000_000L;
    private static final int GEOMETRY_RESULTS_PER_LOOP = 300;
    private static final long GEOMETRY_UPLOAD_BYTES_PER_LOOP = 1_000L << 10;

    /** Called for every position that needs (re)meshing; the mesher answers with {@link #submitGeometryResult}. */
    public interface MeshRequests {
        void request(long position);
    }

    private record TopLevelOp(long position, boolean add) {
    }

    private record ChildChange(long position, byte childExistence) {
    }

    public final int maxNodeCount;
    public final int maxSectionCount;
    private final NodeManager manager;
    private final SectionGeometryManager geometry;
    private final SectionUpdateRouter router = new SectionUpdateRouter();
    private final long geometryReserveBytes;

    private final ConcurrentLinkedQueue<TopLevelOp> topLevelOps = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChildChange> childChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BuiltSection> geometryResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> requestBatches = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> removeBatches = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workCounter = new AtomicInteger();

    //Worker-thread locals gathered per loop
    private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();
    private final IntOpenHashSet cleanerIdResetClear = new IntOpenHashSet();

    private final Object resultLock = new Object();
    private SectionDelta pending;
    private volatile Throwable failure;
    private volatile boolean running = true;
    private final Thread thread;

    public LodNodeService(int maxNodeCount, int maxSectionCount, long geometryCapacityBytes,
                          MeshRequests meshRequests) {
        this.maxNodeCount = maxNodeCount;
        this.maxSectionCount = maxSectionCount;
        this.geometryReserveBytes = Math.min(GEOMETRY_RESERVE_BYTES, geometryCapacityBytes / 10);
        this.geometry = new SectionGeometryManager(maxSectionCount, geometryCapacityBytes);

        this.router.setCallbacks(meshRequests::request, meshRequests::request,
                (position, childExistence) -> {
                    this.childChanges.add(new ChildChange(position, childExistence));
                    this.signalWork(1);
                });
        this.manager = new NodeManager(maxNodeCount, this.geometry, this.router);
        this.manager.setClear(new NodeManager.Cleaner() {
            @Override
            public void alloc(int id) {
                cleanerIdResetClear.remove(id);//Remove clear
                cleanerIdResetClear.add(id | SectionDelta.ADD_FLAG);//Add reset
            }

            @Override
            public void move(int from, int to) {
                //Same as upstream async manager: moves are not tracked (visibility is conservatively reset)
            }

            @Override
            public void free(int id) {
                cleanerIdResetClear.remove(id | SectionDelta.ADD_FLAG);//Remove reset
                cleanerIdResetClear.add(id);//Add clear
            }
        });
        this.manager.setTLNCallbacks(id -> {
            if (!tlnIdChange.remove(id)) {
                if (!tlnIdChange.add(id | SectionDelta.ADD_FLAG)) {
                    throw new IllegalStateException();
                }
            }
        }, id -> {
            if (!tlnIdChange.remove(id | SectionDelta.ADD_FLAG)) {
                if (!tlnIdChange.add(id)) {
                    throw new IllegalStateException();
                }
            }
        });

        this.thread = new Thread(this::runLoop, "JBP-LOD Node Manager");
        this.thread.setDaemon(true);
        this.thread.setUncaughtExceptionHandler((t, e) -> {
            this.failure = e == null ? new RuntimeException("null throwable") : e;
            this.running = false;
            LOGGER.error("[JBP-LOD] Node manager thread failed; LOD tree updates stopped.", e);
        });
        this.thread.start();
    }

    //==================================================================================================================
    // Inputs (any thread)

    public void insertTopLevelNode(long position) {
        this.topLevelOps.add(new TopLevelOp(position, true));
        this.signalWork(1);
    }

    public void removeTopLevelNode(long position) {
        this.topLevelOps.add(new TopLevelOp(position, false));
        this.signalWork(1);
    }

    /** Mesh result for a requested position (empty sections are valid results). */
    public void submitGeometryResult(BuiltSection section) {
        this.geometryResults.add(section);
        this.signalWork(1);
    }

    public void submitChildChange(long position, byte childExistence) {
        this.childChanges.add(new ChildChange(position, childExistence));
        this.signalWork(1);
    }

    /** Forwards a world update for {@code position} to watchers of the given update types. */
    public void onSectionUpdated(long position, int updateTypes, byte childExistence) {
        this.router.forwardEvent(position, updateTypes, childExistence);
    }

    /** Expansion requests produced by traversal, as raw section positions. */
    public void submitRequestBatch(long[] positions) {
        if (positions.length != 0) {
            this.requestBatches.add(positions.clone());
            this.signalWork(1);
        }
    }

    /** Geometry removal candidates produced by the node cleaner; {@code -1} entries are ignored. */
    public void submitRemoveBatch(long[] positions) {
        if (positions.length != 0) {
            this.removeBatches.add(positions.clone());
            this.signalWork(1);
        }
    }

    private void signalWork(int amount) {
        this.workCounter.addAndGet(amount);
        LockSupport.unpark(this.thread);
    }

    //==================================================================================================================
    // Render-thread output

    /**
     * Atomically takes the merged changes published since the last call without waiting for the worker;
     * returns null when there is nothing new (or the worker has failed).
     */
    public SectionDelta poll() {
        synchronized (this.resultLock) {
            SectionDelta out = this.pending;
            this.pending = null;
            return out;
        }
    }

    /** Non-null once the worker thread has died. */
    public Throwable failure() {
        return this.failure;
    }

    //==================================================================================================================

    private void runLoop() {
        while (this.running) {
            if (this.workCounter.get() <= 0 && this.geometryResults.isEmpty()) {
                LockSupport.parkNanos(this, 50_000_000L);
                if (!this.running) {
                    return;
                }
                if (this.workCounter.get() <= 0 && this.geometryResults.isEmpty()) {
                    continue;
                }
            }
            int done = this.processOnce();
            if (done == 0) {
                //Gated on geometry capacity or nothing processable; avoid spinning
                LockSupport.parkNanos(this, 10_000_000L);
            }
        }
    }

    private int processOnce() {
        int workDone = 0;

        TopLevelOp op;
        while ((op = this.topLevelOps.poll()) != null) {
            workDone++;
            if (op.add) {
                this.manager.insertTopLevelNode(op.position);
            } else {
                this.manager.removeTopLevelNode(op.position);
            }
        }

        ChildChange change;
        while ((change = this.childChanges.poll()) != null) {
            workDone++;
            this.manager.processChildChange(change.position, change.childExistence);
        }

        //Limit uploading as well as by geometry capacity being available
        long estimatedGeometryUploadAmount = 0;
        for (int limit = 0; limit < GEOMETRY_RESULTS_PER_LOOP
                && (this.geometry.getGeometryCapacityBytes() - this.geometry.getGeometryUsedBytes()) > this.geometryReserveBytes
                && estimatedGeometryUploadAmount < GEOMETRY_UPLOAD_BYTES_PER_LOOP; limit++) {
            BuiltSection job = this.geometryResults.poll();
            if (job == null) {
                break;
            }
            workDone++;
            this.manager.processGeometryResult(job);
            estimatedGeometryUploadAmount += (long) job.quadCount() * SectionGeometryManager.GEOMETRY_ELEMENT_SIZE;
        }

        long[] batch;
        while ((batch = this.requestBatches.poll()) != null) {
            workDone++;
            for (long position : batch) {
                this.manager.processRequest(position);
            }
        }

        while ((batch = this.removeBatches.poll()) != null) {
            workDone++;
            int zeroCount = 0;
            for (long position : batch) {
                if (position == -1) {
                    continue;
                }
                if (position == 0 && zeroCount++ > 0) {
                    LOGGER.error("[JBP-LOD] Remove node pos is 0 {} times, ignoring duplicates", zeroCount);
                    continue;
                }
                this.manager.removeNodeGeometry(position);
            }
        }

        if (this.workCounter.addAndGet(-workDone) < 0) {
            this.workCounter.set(0);
        }
        if (workDone == 0) {
            return 0;
        }
        this.publish();
        return workDone;
    }

    private void publish() {
        SectionDelta delta = new SectionDelta();

        IntOpenHashSet nodes = this.manager.takeNodeUpdates();
        IntIterator nodeIterator = nodes.iterator();
        while (nodeIterator.hasNext()) {
            int id = nodeIterator.nextInt();
            ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            this.manager.writeNode(id, bytes, 0);
            delta.nodeWrites().put(id, bytes.array());
        }

        IntOpenHashSet slots = this.geometry.takeInvalidatedIds();
        IntIterator slotIterator = slots.iterator();
        while (slotIterator.hasNext()) {
            int id = slotIterator.nextInt();
            ByteBuffer bytes = ByteBuffer.allocate(SectionGeometryManager.SECTION_METADATA_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            this.geometry.writeMetadata(id, bytes, 0);
            delta.metadataWrites().put(id, bytes.array());
        }
        delta.quadUploads().putAll(this.geometry.takeUploads());
        IntOpenHashSet removedHeap = this.geometry.takeHeapRemovals();

        delta.topLevelDelta().addAll(this.tlnIdChange);
        this.tlnIdChange.clear();
        delta.cleanerOps().addAll(this.cleanerIdResetClear);
        this.cleanerIdResetClear.clear();
        delta.setStatistics(this.manager.getCurrentMaxNodeId(), this.manager.getNodeCount(),
                this.geometry.getSectionCount(), this.geometry.getGeometryUsedBytes());

        synchronized (this.resultLock) {
            if (this.pending == null) {
                //A fresh first delta cannot hold stale uploads at freed heap addresses
                this.pending = delta;
            } else {
                this.pending.mergeFrom(delta, removedHeap);
            }
        }
    }

    /** Signals the daemon worker to stop; does not wait (it exits after its current loop iteration). */
    @Override
    public void close() {
        this.running = false;
        LockSupport.unpark(this.thread);
    }

    /** Test hook: waits for the worker thread to exit. */
    public boolean awaitStopped(long millis) throws InterruptedException {
        this.thread.join(millis);
        return !this.thread.isAlive();
    }
}
