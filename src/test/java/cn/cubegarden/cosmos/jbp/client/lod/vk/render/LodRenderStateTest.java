package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LodRenderStateTest {
    private static final LodRenderState.ResourceKey BASE = key(1, 37, 126, 1280, 720, 1, 1, 1, 0, 7, false, true);

    @Test
    void buildsOnceThenReusesUnchangedTargetConfiguration() {
        LodRenderState state = new LodRenderState();

        assertEquals(LodRenderState.Action.REBUILD, state.update(BASE));
        assertEquals(LodRenderState.Action.REUSE, state.update(BASE));
        assertEquals(LodRenderState.Action.RELEASE, state.update(null));
        assertEquals(LodRenderState.Action.INACTIVE, state.update(null));
    }

    @Test
    void rebuildsForEveryResourceDefiningInput() {
        // device, color format, depth format, width, height, samples
        assertRebuilds(key(2, 37, 126, 1280, 720, 1, 1, 1, 0, 7, false, true));
        assertRebuilds(key(1, 43, 126, 1280, 720, 1, 1, 1, 0, 7, false, true));
        assertRebuilds(key(1, 37, 127, 1280, 720, 1, 1, 1, 0, 7, false, true));
        assertRebuilds(key(1, 37, 126, 1920, 720, 1, 1, 1, 0, 7, false, true));
        assertRebuilds(key(1, 37, 126, 1280, 1080, 1, 1, 1, 0, 7, false, true));
        assertRebuilds(key(1, 37, 126, 1280, 720, 4, 1, 1, 0, 7, false, true));
        // scope, resource reload, external provider, LOD runtime, depth convention, draw path
        assertRebuilds(key(1, 37, 126, 1280, 720, 1, 2, 1, 0, 7, false, true));
        assertRebuilds(key(1, 37, 126, 1280, 720, 1, 1, 2, 0, 7, false, true));
        assertRebuilds(key(1, 37, 126, 1280, 720, 1, 1, 1, 1, 7, false, true));
        assertRebuilds(key(1, 37, 126, 1280, 720, 1, 1, 1, 0, 8, false, true));
        assertRebuilds(key(1, 37, 126, 1280, 720, 1, 1, 1, 0, 7, true, true));
        assertRebuilds(key(1, 37, 126, 1280, 720, 1, 1, 1, 0, 7, false, false));
    }

    @Test
    void invalidationReleasesAndNextFrameRebuilds() {
        LodRenderState state = new LodRenderState();
        state.update(BASE);

        assertEquals(LodRenderState.Action.RELEASE, state.invalidate());
        assertEquals(LodRenderState.Action.INACTIVE, state.invalidate());
        assertEquals(LodRenderState.Action.REBUILD, state.update(BASE));
    }

    private static void assertRebuilds(LodRenderState.ResourceKey changed) {
        LodRenderState state = new LodRenderState();
        state.update(BASE);
        assertEquals(LodRenderState.Action.REBUILD, state.update(changed), changed.toString());
        assertEquals(LodRenderState.Action.REUSE, state.update(changed), changed.toString());
    }

    private static LodRenderState.ResourceKey key(long device, int color, int depth, int width, int height,
                                                  int samples, long scope, long reload, long provider,
                                                  long runtime, boolean reverseZ, boolean indirectCount) {
        return new LodRenderState.ResourceKey(device, color, depth, width, height, samples,
                scope, reload, provider, runtime, reverseZ, indirectCount);
    }
}
