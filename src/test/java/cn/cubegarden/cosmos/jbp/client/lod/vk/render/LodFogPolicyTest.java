package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test void activeExtensionExplicitlyDisablesTheLodFogBlock() {
        LodFrameTargets.Fog captured = new LodFrameTargets.Fog(0.0f, 1024.0f, 0.679f, 0.797f, 1.0f, 1.0f);
        assertEquals(LodFrameTargets.Fog.disabled(), LodFogPolicy.lodFog(true, true, captured),
                "while vanilla fog is extended the LoD must not reuse the captured distance fog");
        assertEquals(captured, LodFogPolicy.lodFog(true, false, captured));
        assertEquals(captured, LodFogPolicy.lodFog(false, true, captured));
    }

    @Test void theRendererWritesThePolicyResultNotTheCapturedFog() throws IOException {
        String renderer = Files.readString(Path.of(
                "src/main/java/cn/cubegarden/cosmos/jbp/client/lod/vk/render/VulkanLodRenderer.java"));
        assertTrue(renderer.contains("LodFogPolicy.lodFog(enabled(), true, targets.fog())"),
                "the LoD fog uniform must go through the policy so the extended-fog mode is explicitly fog-free");
    }
}
