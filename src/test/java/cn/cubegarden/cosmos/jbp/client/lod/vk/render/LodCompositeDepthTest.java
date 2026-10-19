package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodCompositeDepthTest {
    @Test
    void compositeReprojectsDifferentNearAndFarPlanes() {
        for (boolean reverse : new boolean[]{false, true}) {
            for (boolean zeroToOne : new boolean[]{false, true}) {
                LodDepthConversion game = perspective(0.05, 4096, reverse, zeroToOne);
                LodDepthConversion lod = perspective(16, 65536, reverse, zeroToOne);
                double distantLodDepth = lod.depthAtDistance(512);
                double compositeDepth = lod.convertTo(game, distantLodDepth);
                assertEquals(game.depthAtDistance(512), compositeDepth, 1e-6);
                // The current game's near terrain wins the real attachment depth test,
                // even if the offscreen depth seed came from a different/previous frame.
                double nearTerrainDepth = game.depthAtDistance(32);
                assertTrue(reverse ? compositeDepth < nearTerrainDepth : compositeDepth > nearTerrainDepth);
            }
        }
    }

    @Test
    void coincidentLodDepthReprojectsOntoVanillaSurfaceWithoutBias() {
        for (boolean reverse : new boolean[]{false, true}) {
            for (boolean zeroToOne : new boolean[]{false, true}) {
                LodDepthConversion game = perspective(0.05, 4096, reverse, zeroToOne);
                LodDepthConversion lod = perspective(16, 65536, reverse, zeroToOne);
                for (double w : new double[]{32, 128, 512, 2048}) {
                    assertEquals(game.depthAtDistance(w), lod.convertTo(game, lod.depthAtDistance(w)), 1e-6,
                            "LOD depth must reproject onto the vanilla surface at w=" + w);
                }
            }
        }
    }

    @Test
    void conversionSupportsDifferentSourceAndDestinationDepthConventions() {
        LodDepthConversion lod = perspective(16, 65536, true, true);
        LodDepthConversion game = perspective(0.05, 4096, false, false);
        assertEquals(game.depthAtDistance(256), lod.convertTo(game, lod.depthAtDistance(256)), 1e-6);
    }

    @Test
    void boundingDepthRejectsOnlyFragmentsCloserThanTheLoadedVolumeExit() {
        for (boolean reverse : new boolean[]{false, true}) {
            LodDepthConversion lod = perspective(16, 48000, reverse, true);
            double bound = lod.depthAtDistance(128);
            double inside = lod.depthAtDistance(64);
            double outside = lod.depthAtDistance(256);
            // CPU reference of depthutils.glsl DEPTH_SCALAR_COMPARE(fragmentDepth, bound).
            assertTrue(reverse ? inside > bound : inside < bound);
            assertFalse(reverse ? outside > bound : outside < bound);
            assertFalse(reverse ? bound > bound : bound < bound, "equality must survive the strict gate");

            // LodBoundingPass uses the opposite of FAR as its clear value. Pixels not covered by
            // a built-section cube must never reject a valid fragment, including depth endpoints.
            double untouched = reverse ? 1.0 : 0.0;
            for (double fragment : new double[]{0.0, inside, bound, outside, 1.0}) {
                assertFalse(reverse ? fragment > untouched : fragment < untouched,
                        "an untouched bounding pixel cannot exclude LOD");
            }
        }
    }

    private static LodDepthConversion perspective(double near, double far, boolean reverse, boolean zeroToOne) {
        double n = reverse ? far : near;
        double f = reverse ? near : far;
        double a = zeroToOne ? f / (f - n) : (f + n) / (f - n);
        double b = zeroToOne ? -f * n / (f - n) : -2 * f * n / (f - n);
        return new LodDepthConversion((float) a, (float) b, zeroToOne);
    }
}
