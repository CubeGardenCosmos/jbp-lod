package cn.cubegarden.cosmos.jbp.client.lod.storage;

import cn.cubegarden.cosmos.jbp.client.lod.data.tree.BuiltSection;
import cn.cubegarden.cosmos.jbp.client.lod.data.ActivationLog;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.LodNodeService;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionMesher;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Real section source for the node tree: answers mesh requests by reading stored sections from the
 * {@link LodStorageService} (I/O thread), meshing them on the mesh executor with the installed
 * {@link SectionMesher}, and submitting the result to the {@link LodNodeService}.
 *
 * <p>Requests are coalesced per position (a request arriving during an in-flight one triggers exactly one
 * redo). Until a mesher is installed, requested positions are retained and dispatched when
 * {@link #setMesher} is called; nothing is answered with fabricated geometry.
 */
public final class StoredSectionMeshProvider implements LodNodeService.MeshRequests {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");

    private final LodStorageService storage;
    private final Executor meshExecutor;
    private volatile LodNodeService nodes;
    private volatile SectionMesher mesher;
    private volatile boolean closed;

    /** false = in flight, true = in flight and another request arrived (redo once). */
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();
    private final LongOpenHashSet waitingForMesher = new LongOpenHashSet();
    private final MeshRetryPolicy retries = new MeshRetryPolicy();

    /** Receives the quad count of every submitted mesh result (0 for empty sections). */
    private final java.util.function.IntConsumer onMeshed;

    public StoredSectionMeshProvider(LodStorageService storage, Executor meshExecutor,
                                     java.util.function.IntConsumer onMeshed) {
        this.storage = storage;
        this.meshExecutor = meshExecutor;
        this.onMeshed = onMeshed;
    }

    /** Binds the node service that receives results (it needs this provider for construction). */
    public void bind(LodNodeService nodes) {
        this.nodes = nodes;
    }

    public void setMesher(SectionMesher mesher) {
        long[] waiting;
        synchronized (waitingForMesher) {
            this.mesher = mesher;
            waiting = waitingForMesher.toLongArray();
            waitingForMesher.clear();
        }
        for (long position : waiting) {
            request(position);
        }
    }

    public boolean hasMesher() {
        return mesher != null;
    }

    /** Outstanding mesh work (in flight plus waiting for a mesher); the analogue of upstream's task count. */
    public int pendingTaskCount() {
        return inFlight.size() + waitingCount();
    }

    /** Number of positions currently waiting for a mesher to be installed. */
    public int waitingCount() {
        synchronized (waitingForMesher) {
            return waitingForMesher.size();
        }
    }

    /** Positions with a mesh request in flight (diagnostics). */
    public int inFlightCount() {
        return inFlight.size();
    }

    public void close() {
        closed = true;
    }

    @Override
    public void request(long position) {
        if (closed) {
            return;
        }
        synchronized (waitingForMesher) {
            if (mesher == null) {
                waitingForMesher.add(position);
                return;
            }
        }
        long cooldownMillis;
        synchronized (retries) {
            cooldownMillis = retries.cooldownRemainingMillis(position, System.nanoTime());
        }
        if (cooldownMillis > 0L) {
            // Recently failed terminally: defer the attempt rather than re-running a failing build.
            CompletableFuture.delayedExecutor(cooldownMillis, TimeUnit.MILLISECONDS, meshExecutor)
                    .execute(() -> request(position));
            return;
        }
        boolean[] startNow = {false};
        inFlight.compute(position, (key, state) -> {
            if (state == null) {
                startNow[0] = true;
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        });
        if (startNow[0]) {
            start(position);
        }
    }

    private void start(long position) {
        storage.getSectionData(position).whenComplete((raw, failure) -> {
            if (failure != null) {
                fail(position, failure);
                return;
            }
            try {
                meshExecutor.execute(() -> mesh(position, raw));
            } catch (RuntimeException rejected) {
                fail(position, rejected);
            }
        });
    }

    private void mesh(long position, byte[] raw) {
        try {
            SectionMesher active = mesher;
            LodNodeService target = nodes;
            if (!closed && active != null && target != null) {
                BuiltSection built = active.mesh(position, raw);
                synchronized (retries) {
                    retries.onSuccess(position);
                }
                if (ActivationLog.first("meshed")) {
                    LOGGER.info("[JBP-LOD] First section meshed: {} with {} quads.",
                            SectionPos.pretty(position), built.quadCount());
                }
                onMeshed.accept(built.quadCount());
                target.submitGeometryResult(built);
            }
            finish(position, null);
        } catch (Throwable failure) {
            fail(position, failure);
        }
    }

    /**
     * A storage read or mesh build failed. Nothing is submitted for the position, so no fabricated geometry reaches
     * the tree. While attempts remain the build is retried after backoff. When they are exhausted the position enters
     * a cooldown and the node service is told, so its pending request is cancelled and re-requested later.
     */
    private void fail(long position, Throwable failure) {
        if (closed || failure instanceof CancellationException) {
            finish(position, failure);
            return;
        }
        MeshRetryPolicy.Decision decision;
        synchronized (retries) {
            decision = retries.onFailure(position, System.nanoTime());
        }
        if (decision instanceof MeshRetryPolicy.Retry retry) {
            LOGGER.warn("[JBP-LOD] Section {} could not be produced; retrying in {} ms.",
                    SectionPos.pretty(position), retry.delayMillis(), failure);
            CompletableFuture.delayedExecutor(retry.delayMillis(), TimeUnit.MILLISECONDS, meshExecutor)
                    .execute(() -> start(position));
            return;
        }
        MeshRetryPolicy.Terminal terminal = (MeshRetryPolicy.Terminal) decision;
        LOGGER.warn("[JBP-LOD] Section {} failed {} attempts; reporting it as failed, not before {} ms.",
                SectionPos.pretty(position), MeshRetryPolicy.MAX_ATTEMPTS, terminal.cooldownMillis(), failure);
        finish(position, failure);
        LodNodeService target = nodes;
        if (target != null) {
            target.submitRequestFailed(position);
        }
    }

    private void finish(long position, Throwable failure) {
        boolean[] again = {false};
        inFlight.compute(position, (key, state) -> {
            if (state != null && state && !closed
                    && (failure == null || failure instanceof CancellationException)) {
                again[0] = true;
                return Boolean.FALSE;
            }
            return null;
        });
        if (again[0]) {
            start(position);
        }
    }
}
