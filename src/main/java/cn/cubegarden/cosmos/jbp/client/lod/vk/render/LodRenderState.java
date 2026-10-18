package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import java.util.Objects;

/** Pure frame-resource key/state machine; borrowed per-frame image handles are deliberately absent. */
public final class LodRenderState {
    private ResourceKey key;

    public Action update(ResourceKey next) {
        if (next == null) {
            if (key == null) {
                return Action.INACTIVE;
            }
            key = null;
            return Action.RELEASE;
        }
        Objects.requireNonNull(next, "next");
        if (next.equals(key)) {
            return Action.REUSE;
        }
        key = next;
        return Action.REBUILD;
    }

    /** Invalidates owned resources after reload, scope replacement, disconnect, or device change. */
    public Action invalidate() {
        if (key == null) {
            return Action.INACTIVE;
        }
        key = null;
        return Action.RELEASE;
    }

    public ResourceKey key() {
        return key;
    }

    public enum Action {
        INACTIVE,
        REUSE,
        REBUILD,
        RELEASE
    }

    /**
     * Inputs that change a Vulkan pipeline, descriptor contract, or scope-owned resource set.
     * Image/view addresses are excluded because Sodium supplies different borrowed images per
     * frame; their descriptors are refreshed for every draw without rebuilding the pipeline.
     */
    public record ResourceKey(
            long deviceAddress,
            int colorFormat,
            int depthFormat,
            int width,
            int height,
            int sampleCount,
            long scopeGeneration,
            long resourceReloadGeneration,
            long providerGeneration,
            long runtimeGeneration,
            boolean reverseZ,
            boolean indirectCountEnabled
    ) {
        public ResourceKey {
            if (deviceAddress == 0 || width <= 0 || height <= 0 || sampleCount <= 0) {
                throw new IllegalArgumentException("invalid device/target resource key");
            }
        }
    }
}
