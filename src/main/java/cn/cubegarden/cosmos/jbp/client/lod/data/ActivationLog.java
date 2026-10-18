package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Latches one-time first-activation diagnostics so each pipeline stage reports its first activity once per JVM. */
public final class ActivationLog {
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private ActivationLog() {
    }

    /** Returns true exactly once per key for the lifetime of the JVM. */
    public static boolean first(String key) {
        return SEEN.add(key);
    }
}
