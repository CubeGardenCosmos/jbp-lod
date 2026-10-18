package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds how many section snapshots a {@link LiveSectionProducer} may hold accepted-but-unwritten.
 *
 * <p>Permits are acquired on the game thread before a snapshot is built and released by the ingest worker when the
 * task finishes (or is skipped after close). Closing refuses all further acquisitions.
 */
final class CaptureAdmission {
    /** Upper bound on queued section snapshots; each snapshot holds ~4096 block states and ids. */
    static final int MAX_PENDING_SECTIONS = 512;

    private final int limit;
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;

    CaptureAdmission(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        this.limit = limit;
    }

    /** Reserves one permit; false when closed or when the backlog is full. */
    boolean tryAcquire() {
        if (closed) {
            return false;
        }
        if (pending.incrementAndGet() > limit) {
            pending.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Returns one permit previously granted by {@link #tryAcquire()}. */
    void release() {
        pending.decrementAndGet();
    }

    /** True when {@code sections} more permits could be granted right now. */
    boolean hasCapacity(int sections) {
        return !closed && pending.get() + sections <= limit;
    }

    void close() {
        closed = true;
    }

    boolean isClosed() {
        return closed;
    }

    int pendingCount() {
        return pending.get();
    }
}
