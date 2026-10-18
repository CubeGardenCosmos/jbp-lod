package cn.cubegarden.cosmos.jbp.client.lod.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Registration point for a per-frame external G-buffer provider. The default is vanilla. */
public final class LodExternalTargets {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile LodExternalTargetsProvider provider;

    private LodExternalTargets() {
    }

    /**
     * Installs the provider used for subsequent render-thread frame callbacks. A provider must
     * obey {@link LodExternalTargetsProvider}'s frame lifetime and synchronization contract.
     */
    public static synchronized void install(LodExternalTargetsProvider newProvider) {
        provider = Objects.requireNonNull(newProvider, "newProvider");
        GENERATION.incrementAndGet();
    }

    /** Removes {@code expectedProvider} without clearing a provider installed by another mod. */
    public static synchronized void remove(LodExternalTargetsProvider expectedProvider) {
        if (provider == expectedProvider) {
            provider = null;
            GENERATION.incrementAndGet();
        }
    }

    /** Returns the currently installed provider, or {@code vanillaProvider} when none is installed. */
    public static LodExternalTargetsProvider currentOr(LodExternalTargetsProvider vanillaProvider) {
        LodExternalTargetsProvider current = provider;
        return current == null ? Objects.requireNonNull(vanillaProvider, "vanillaProvider") : current;
    }

    /** Changes whenever provider ownership changes, for frame-resource invalidation. */
    public static long generation() {
        return GENERATION.get();
    }
}
