package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic model of the LOD opaque/translucent alpha path:
 *
 * <ol>
 *   <li>{@code quads.frag} writes face | level&lt;&lt;3 | AO&lt;&lt;6 into alpha while computing the colour
 *       ({@code quad_util.glsl} {@code makeRemainingAttributes} plus {@code computeColour});</li>
 *   <li>{@code quads.frag} now resets opaque alpha to 1.0, equivalent to upstream Voxy clearing
 *       {@code ocolour.w = 1.0f} in {@code post/ssao.comp};</li>
 *   <li>{@code offscreen_composite.frag} divides by the accumulated alpha to undo the premultiplication
 *       of SRC_ALPHA blending onto transparent black;</li>
 *   <li>the composite pipeline blends SRC_ALPHA / ONE_MINUS_SRC_ALPHA into the game's 8-bit target.</li>
 * </ol>
 *
 * Without step 2 the packed metadata becomes coverage: 65/255 at level 0 up to 101/255 at level 4.
 */
class LodCompositeAlphaTest {
    private static final float EPSILON = 1e-6f;
    private static final float[] SKY = {0.55f, 0.72f, 1.0f, 1.0f};

    /** The metadata byte packed by quad_util.glsl: face | lodLevel&lt;&lt;3 | hasAO&lt;&lt;6. */
    static int packedMetadata(int face, int level, boolean hasAO) {
        return face | (level << 3) | (hasAO ? 1 << 6 : 0);
    }

    /** {@code quad_util.glsl} opaque tinting alpha: metadata/255 (lighting alpha is zero). */
    static float packedAlpha(int face, int level, boolean hasAO) {
        return packedMetadata(face, level, hasAO) / 255.0f;
    }

    record Texel(float r, float g, float b, float a) {
        Texel composite() {
            return a <= 0.0f ? null : new Texel(r / a, g / a, b / a, a);
        }
    }

    /** Offscreen texel of an opaque draw after the quads.frag alpha reset. */
    static Texel opaqueAfterFix(float r, float g, float b) {
        return new Texel(r, g, b, 1.0f);
    }

    /** Offscreen texel of an opaque draw if computeColour's packed alpha reached the output (the defect). */
    static Texel opaqueBeforeFix(float r, float g, float b, int face, int level, boolean hasAO) {
        return new Texel(r, g, b, packedAlpha(face, level, hasAO));
    }

    /** OPAQUE blend mode: srcColor/dstColor ONE/ZERO, srcAlpha/dstAlpha ONE/ZERO, i.e. overwrite. */
    static Texel overOpaque(Texel destination, Texel source) {
        return source;
    }

    /** Translucent blend: colour SRC_ALPHA / ONE_MINUS_SRC_ALPHA, alpha ONE / ONE_MINUS_SRC_ALPHA. */
    static Texel translucentOver(Texel destination, Texel source) {
        return new Texel(
                source.a * source.r + (1.0f - source.a) * destination.r,
                source.a * source.g + (1.0f - source.a) * destination.g,
                source.a * source.b + (1.0f - source.a) * destination.b,
                source.a + (1.0f - source.a) * destination.a);
    }

    /** Composite pipeline blend into the 8-bit game target, then UNORM quantization. */
    static float[] blendInto8BitTarget(Texel composite, float[] target) {
        float[] out = new float[4];
        out[0] = composite.a * composite.r + (1.0f - composite.a) * target[0];
        out[1] = composite.a * composite.g + (1.0f - composite.a) * target[1];
        out[2] = composite.a * composite.b + (1.0f - composite.a) * target[2];
        out[3] = composite.a + (1.0f - composite.a) * target[3];
        for (int i = 0; i < 4; i++) {
            out[i] = Math.round(Math.min(1.0f, Math.max(0.0f, out[i])) * 255.0f) / 255.0f;
        }
        return out;
    }

    @Test
    void opaqueCompositesAtFullCoverageForEveryPackedMetadataValue() {
        float[] lodColour = {0.24f, 0.62f, 0.31f};
        for (int face = 0; face < 6; face++) {
            for (int level = 0; level <= 4; level++) {
                for (boolean hasAO : new boolean[]{false, true}) {
                    int metadata = packedMetadata(face, level, hasAO);
                    assertTrue(metadata < 255, "metadata byte must fit in alpha");
                    Texel accumulated = overOpaque(new Texel(0, 0, 0, 0),
                            opaqueAfterFix(lodColour[0], lodColour[1], lodColour[2]));
                    Texel composite = accumulated.composite();
                    assertNotNull(composite);
                    assertEquals(1.0f, composite.a(), EPSILON, "opaque coverage must be 1");
                    float[] screen = blendInto8BitTarget(composite, SKY);
                    assertEquals(quantize(lodColour[0]), screen[0], EPSILON, "face=" + face + " level=" + level);
                    assertEquals(quantize(lodColour[1]), screen[1], EPSILON, "face=" + face + " level=" + level);
                    assertEquals(quantize(lodColour[2]), screen[2], EPSILON, "face=" + face + " level=" + level);
                    assertEquals(1.0f, screen[3], EPSILON, "opaque coverage must reach the game target");
                }
            }
        }
    }

    @Test
    void packedMetadataAlphaWouldBlendOpaqueLodAtPartialOpacity() {
        float[] lodColour = {0.24f, 0.62f, 0.31f};
        for (int face = 0; face < 6; face++) {
            for (int level = 0; level <= 4; level++) {
                for (boolean hasAO : new boolean[]{false, true}) {
                    float coverage = packedAlpha(face, level, hasAO);
                    Texel composite = opaqueBeforeFix(lodColour[0], lodColour[1], lodColour[2], face, level, hasAO)
                            .composite();
                    if (metadataIsZero(face, level, hasAO)) {
                        // The alpha-0 metadata byte (face 0, level 0, no AO) is discarded, not composited.
                        assertNull(composite);
                        continue;
                    }
                    assertTrue(coverage < 0.4f, "packed metadata is low enough to look like sky");
                    assertNotNull(composite);
                    assertEquals(quantize(coverage), quantize(composite.a()), EPSILON);
                    float[] screen = blendInto8BitTarget(composite, SKY);
                    // Colour is recovered by the division, but the blend only applies it at `coverage`,
                    // so a non-sky LOD colour is contaminated towards sky.
                    assertTrue(Math.abs(screen[2] - quantize(lodColour[2])) > 0.1f,
                            "defect model must show the sky blend at face=" + face + " level=" + level);
                }
            }
        }
    }

    @Test
    void translucentKeepsRealAlphaOverOpaqueAndOnClearedPixels() {
        Texel opaque = opaqueAfterFix(0.2f, 0.6f, 0.2f);
        Texel translucent = new Texel(0.6f, 0.1f, 0.1f, 0.5f);
        Texel accumulated = translucentOver(overOpaque(new Texel(0, 0, 0, 0), opaque), translucent);
        Texel composite = accumulated.composite();
        assertNotNull(composite);
        // Alpha remains 1 because the opaque layer below already covers the pixel.
        assertEquals(1.0f, composite.a(), EPSILON);
        assertEquals(0.5f * 0.6f + 0.5f * 0.2f, composite.r(), EPSILON);
        assertEquals(0.5f * 0.1f + 0.5f * 0.6f, composite.g(), EPSILON);
        assertEquals(0.5f * 0.1f + 0.5f * 0.2f, composite.b(), EPSILON);

        // A translucent quad with no opaque pixel below it keeps its own alpha and recovers its
        // straight colour through the premultiplied aggregate.
        Texel standalone = translucentOver(new Texel(0, 0, 0, 0), translucent);
        Texel standaloneComposite = standalone.composite();
        assertNotNull(standaloneComposite);
        assertEquals(0.5f, standaloneComposite.a(), EPSILON);
        assertEquals(0.6f, standaloneComposite.r(), EPSILON);
        assertEquals(0.1f, standaloneComposite.g(), EPSILON);
        assertEquals(0.1f, standaloneComposite.b(), EPSILON);
        float[] screen = blendInto8BitTarget(standaloneComposite, SKY);
        // The composite keeps the translucent alpha 0.5, so the game target sees a half blend.
        assertEquals(quantize(0.5f * 0.6f + 0.5f * SKY[0]), screen[0], EPSILON);
        assertEquals(quantize(0.5f * 0.1f + 0.5f * SKY[1]), screen[1], EPSILON);
        assertEquals(quantize(0.5f * 0.1f + 0.5f * SKY[2]), screen[2], EPSILON);
    }

    @Test
    void pixelsNeverWrittenStayTransparentAndPaintNothing() {
        Texel cleared = new Texel(0, 0, 0, 0);
        assertNull(cleared.composite(), "alpha 0 must discard in the composite, not divide by zero");
    }

    private static boolean metadataIsZero(int face, int level, boolean hasAO) {
        return packedMetadata(face, level, hasAO) == 0;
    }

    private static float quantize(float value) {
        return Math.round(value * 255.0f) / 255.0f;
    }
}
