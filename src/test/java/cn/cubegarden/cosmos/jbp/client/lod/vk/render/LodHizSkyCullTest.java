package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reverse-Z occlusion model mirroring hiz/blit.frag + hiz.comp (mip0 reduction by min) and
 * lod/hierarchical/screenspace.glsl isCulledByHiz (culled when min(occluders) >= section nearest z).
 * Seed texels: sky = FAR = 0, vanilla terrain = NEAR = 1.
 */
class LodHizSkyCullTest {
    private static final float FAR = 0f;
    private static final float NEAR = 1f;

    /** Mip0 texel reduced over a 2x2 footprint without any FAR->NEAR patch. */
    private static float reduce(float a, float b, float c, float d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static boolean culled(float[] footprint, float sectionNearestZ) {
        float occluder = NEAR * 3f - 1f;
        for (float texel : footprint) {
            occluder = Math.min(occluder, texel);
        }
        return occluder >= sectionNearestZ;
    }

    @Test
    void pureSkyDrawsLod() {
        float[] sky = {FAR, FAR, FAR, FAR};
        assertFalse(culled(sky, 0.5f));
        assertFalse(culled(sky, 0.99f));
    }

    @Test
    void farGroundBeyondVanillaRangeWithNoVanillaPixelDrawsLod() {
        // Vanilla draws nothing this far out, so the seed footprint is sky (FAR) even though LOD ground sits there.
        float[] beyondRange = {FAR, FAR, FAR, FAR};
        assertFalse(culled(beyondRange, 0.8f));
        assertEquals(FAR, reduce(FAR, FAR, FAR, FAR));
    }

    @Test
    void nearerVanillaTerrainSuppressesLodBehindIt() {
        float[] terrain = {NEAR, NEAR, NEAR, NEAR};
        assertTrue(culled(terrain, 0.5f));
        // Mixed footprint: any sky texel keeps the tile open, so LOD is never culled by a partially covered tile.
        float[] mixed = {NEAR, NEAR, NEAR, FAR};
        assertFalse(culled(mixed, 0.5f));
    }

    @Test
    void consecutiveFramesFromSameSeedMakeSameDecisions() {
        float[][] frameSeed = {
                {FAR, FAR, NEAR, NEAR},
                {FAR, FAR, NEAR, NEAR},
        };
        float[] sectionZ = {0.3f, 0.6f, 0.9f};
        for (float z : sectionZ) {
            boolean first = culled(frameSeed[0], z);
            boolean second = culled(frameSeed[1], z);
            assertEquals(first, second, "section z=" + z);
        }
    }

    @Test
    void blitMip0DoesNotPatchFarTexelsIntoOccluders() throws IOException {
        String blit = Files.readString(Path.of("src/main/resources/assets/jbp-lod/shaders/hiz/blit.frag"),
                StandardCharsets.UTF_8);
        assertFalse(blit.contains("equal(vec4(FAR)"));
        assertFalse(blit.contains("mix(vec4(NEAR)"));
        assertTrue(blit.contains("REDUCTION(REDUCTION(depths.x, depths.y)"));
    }
}
