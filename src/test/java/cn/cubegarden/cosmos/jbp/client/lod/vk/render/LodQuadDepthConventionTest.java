package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression pin for the LOD bounding gate: {@code quads.frag} consumes {@code DEPTH_SCALAR_COMPARE}, whose
 * default is standard Z. The production shader compile must receive {@code USE_REVERSE_Z} on a reverse-Z
 * device, otherwise {@code outsideBound} inverts and rejects every far LOD fragment.
 */
class LodQuadDepthConventionTest {
    @Test
    void quadFragmentDefinesCarryTheDepthConvention() {
        assertEquals(Map.of("USE_REVERSE_Z", "1"), VulkanLodRenderer.quadFragmentDefines(true));
        assertEquals(Map.of(), VulkanLodRenderer.quadFragmentDefines(false));
    }

    @Test
    void quadsFragmentStillUsesTheDepthCompareMacro() throws IOException {
        String source = shaderSource("/assets/jbp-lod/shaders/lod/gl46/quads.frag");
        assertTrue(source.contains("DEPTH_SCALAR_COMPARE"),
                "quads.frag must keep the bounding gate that depends on USE_REVERSE_Z");
    }

    @Test
    void untouchedBoundingDepthNeverRejectsFarLod() {
        for (boolean reverseZ : new boolean[]{false, true}) {
            // LodBoundingPass clears to the inverse of FAR: 1.0 in reverse-Z, 0.0 in standard Z.
            double untouched = reverseZ ? 1.0 : 0.0;
            double farFragment = reverseZ ? 0.01 : 0.99;
            assertFalse(outsideBound(reverseZ, farFragment, untouched),
                    "an untouched bounding pixel cannot exclude far LOD (reverseZ=" + reverseZ + ")");
            // A fragment nearer than the loaded-volume exit must be rejected so LOD never shows inside it.
            double bound = 0.5;
            double insideFragment = reverseZ ? 0.9 : 0.1;
            assertTrue(outsideBound(reverseZ, insideFragment, bound),
                    "LOD nearer than the loaded-volume bound must be discarded (reverseZ=" + reverseZ + ")");
        }
    }

    /** CPU reference of {@code depthutils.glsl DEPTH_SCALAR_COMPARE(fragment, bound)}. */
    private static boolean outsideBound(boolean reverseZ, double fragment, double bound) {
        return reverseZ ? fragment > bound : fragment < bound;
    }

    private static String shaderSource(String resource) throws IOException {
        try (InputStream stream = LodQuadDepthConventionTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing shader resource " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
