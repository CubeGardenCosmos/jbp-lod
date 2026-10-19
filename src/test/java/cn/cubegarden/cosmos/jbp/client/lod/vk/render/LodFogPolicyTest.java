package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodFogPolicyTest {
    @Test void fogOnlyMovesWhenTheRendererAndAScopeAreActive() {
        assertTrue(LodFogPolicy.shouldExtendLodFog(true, true));
        assertFalse(LodFogPolicy.shouldExtendLodFog(true, false));
        assertFalse(LodFogPolicy.shouldExtendLodFog(false, true));
        assertFalse(LodFogPolicy.shouldExtendLodFog(false, false));
    }

    @Test void closeRangeEnvironmentalFogIsPreserved() {
        assertFalse(LodFogPolicy.shouldExtendEnvironmentalFog(5.0f));
        assertFalse(LodFogPolicy.shouldExtendEnvironmentalFog(9.99f));
        assertTrue(LodFogPolicy.shouldExtendEnvironmentalFog(10.0f));
        assertTrue(LodFogPolicy.shouldExtendEnvironmentalFog(96.0f));
    }
}
