package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkyLightFallbackTest {
    private static Map<Long, DataLayer> layers() {
        return new HashMap<>();
    }

    private static SkyLightFallback.LayerSource source(Map<Long, DataLayer> layers, int sectionX, int sectionZ) {
        return (x, y, z) -> {
            assertEquals(sectionX, x, "fallback must only search the captured column");
            assertEquals(sectionZ, z, "fallback must only search the captured column");
            return layers.get((long) y);
        };
    }

    private static void assertAllLayers(DataLayer layer, java.util.function.IntUnaryOperator expected) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(expected.applyAsInt(x), layer.get(x, y, z), "x=" + x + " y=" + y + " z=" + z);
                }
            }
        }
    }

    @Test void missingLayerWithNothingAboveIsFullSkyLight() {
        Map<Long, DataLayer> layers = layers();
        DataLayer resolved = SkyLightFallback.resolve(3, 4, 5, 15, source(layers, 3, 5));
        assertAllLayers(resolved, x -> 15);
    }

    @Test void missingLayerRepeatsLowestLayerOfNearestSectionAbove() {
        Map<Long, DataLayer> layers = layers();
        DataLayer above = new DataLayer();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                above.set(x, 0, z, x);//Only layer 0 is meaningful; the engine repeats it.
            }
        }
        layers.put(9L, above);
        DataLayer resolved = SkyLightFallback.resolve(0, 4, 0, 15, source(layers, 0, 0));
        assertAllLayers(resolved, x -> x);
        //The nearest section wins even when a farther one has different data.
        DataLayer nearer = new DataLayer(15);
        layers.put(8L, nearer);
        assertAllLayers(SkyLightFallback.resolve(0, 4, 0, 15, source(layers, 0, 0)), x -> 15);
    }

    @Test void homogeneousAboveLayerIsCopied() {
        Map<Long, DataLayer> layers = layers();
        layers.put(7L, new DataLayer(12));
        DataLayer resolved = SkyLightFallback.resolve(0, 6, 0, 15, source(layers, 0, 0));
        assertAllLayers(resolved, x -> 12);
    }

    @Test void darkAboveLayerRepeatsDarkness() {
        //An empty (all-zero) stored layer above means the engine derives 0 for the section below, not 15.
        Map<Long, DataLayer> layers = layers();
        layers.put(7L, new DataLayer());
        assertAllLayers(SkyLightFallback.resolve(0, 6, 0, 15, source(layers, 0, 0)), x -> 0);
    }

    @Test void resolvedLayerIsOwnedByTheCaller() {
        Map<Long, DataLayer> layers = layers();
        DataLayer above = new DataLayer();
        above.set(1, 0, 1, 7);
        layers.put(7L, above);
        DataLayer resolved = SkyLightFallback.resolve(0, 6, 0, 15, source(layers, 0, 0));
        resolved.set(1, 5, 1, 0);
        assertEquals(7, above.get(1, 0, 1), "source layer must not be mutated");
        assertEquals(0, resolved.get(1, 5, 1));
    }

    @Test void resolutionStopsAtTheHighestSection() {
        Map<Long, DataLayer> layers = layers();
        layers.put(16L, new DataLayer(3));//Outside the level: must not be read.
        assertAllLayers(SkyLightFallback.resolve(0, 14, 0, 15, source(layers, 0, 0)), x -> 15);
    }

    @Test void onlyAirSectionsMayBeCapturedWithoutLightLayers() {
        DataLayer sky = new DataLayer();
        DataLayer block = new DataLayer();
        assertTrue(SkyLightFallback.ready(true, null, null));
        assertTrue(SkyLightFallback.ready(true, sky, null));
        assertTrue(SkyLightFallback.ready(false, sky, block));
        assertFalse(SkyLightFallback.ready(false, null, block), "an unlit non-air section is not ready");
        assertFalse(SkyLightFallback.ready(false, sky, null), "missing block light means the packet is pending");
        assertFalse(SkyLightFallback.ready(false, null, null));
    }
}
