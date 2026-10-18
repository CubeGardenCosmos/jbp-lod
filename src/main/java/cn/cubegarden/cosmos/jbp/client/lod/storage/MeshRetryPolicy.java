package cn.cubegarden.cosmos.jbp.client.lod.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * Retry bookkeeping for failed section mesh builds.
 *
 * <p>Each position gets {@link #MAX_ATTEMPTS} attempts in total. The gaps between attempts come from
 * {@link #BACKOFF_MILLIS}, so it must hold exactly {@code MAX_ATTEMPTS - 1} entries. When the attempts are exhausted
 * the position enters a cooldown during which new requests are deferred, so a persistently failing section cannot
 * spin on the tree's re-request path.
 *
 * <p>Not thread-safe: callers synchronize on the instance.
 */
final class MeshRetryPolicy {
    static final int MAX_ATTEMPTS = 3;
    static final long[] BACKOFF_MILLIS = {250L, 1000L};
    static final long COOLDOWN_MILLIS = 5000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    sealed interface Decision permits Retry, Terminal {
    }

    /** Attempt again after {@code delayMillis}. */
    record Retry(long delayMillis) implements Decision {
    }

    /** Attempts are exhausted; the position is cooling down for {@code cooldownMillis}. */
    record Terminal(long cooldownMillis) implements Decision {
    }

    private final Map<Long, Integer> failures = new HashMap<>();
    private final Map<Long, Long> cooldownUntilNanos = new HashMap<>();

    /** Records one failed attempt for {@code position} and decides what happens next. */
    Decision onFailure(long position, long nowNanos) {
        int attempts = failures.merge(position, 1, Integer::sum);
        if (attempts < MAX_ATTEMPTS) {
            return new Retry(BACKOFF_MILLIS[attempts - 1]);
        }
        failures.remove(position);
        cooldownUntilNanos.put(position, nowNanos + COOLDOWN_MILLIS * NANOS_PER_MILLI);
        return new Terminal(COOLDOWN_MILLIS);
    }

    /** Clears all failure state for {@code position} after a successful build. */
    void onSuccess(long position) {
        failures.remove(position);
        cooldownUntilNanos.remove(position);
    }

    /** Milliseconds until {@code position} may be attempted again; 0 when it is not cooling down. */
    long cooldownRemainingMillis(long position, long nowNanos) {
        Long until = cooldownUntilNanos.get(position);
        if (until == null) {
            return 0L;
        }
        long remaining = until - nowNanos;
        if (remaining <= 0L) {
            cooldownUntilNanos.remove(position);
            return 0L;
        }
        return (remaining + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI;
    }
}
