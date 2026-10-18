package cn.cubegarden.cosmos.jbp.client.lod.model;

import net.caffeinemc.mods.sodium.client.util.color.ColorSRGB;
import org.junit.jupiter.api.Test;

import static cn.cubegarden.cosmos.jbp.client.lod.model.TextureUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class TextureUtilsTest {
    private static final int OPAQUE = 0xFF808080;

    @Test
    void boundsAreInclusiveMinMaxOverWrittenPixels() {
        var face = TestFaces.rect(3, 5, 10, 12, OPAQUE, 0x100000, 1);
        assertArrayEquals(new int[]{3, 10, 5, 12}, computeBounds(face, WRITE_CHECK_STENCIL));
        assertEquals(8 * 8, getWrittenPixelCount(face, WRITE_CHECK_STENCIL));

        var corners = TestFaces.unwritten();
        TestFaces.put(corners, 15, 0, OPAQUE, 0, 1);
        TestFaces.put(corners, 0, 15, OPAQUE, 0, 1);
        assertArrayEquals(new int[]{0, 15, 0, 15}, computeBounds(corners, WRITE_CHECK_STENCIL));

        var single = TestFaces.unwritten();
        TestFaces.put(single, 7, 2, OPAQUE, 0, 1);
        assertArrayEquals(new int[]{7, 7, 2, 2}, computeBounds(single, WRITE_CHECK_STENCIL));
    }

    @Test
    void emptyFaceBoundsAreSafeForEveryCheckMode() {
        var empty = TestFaces.unwritten();
        for (int mode : new int[]{WRITE_CHECK_STENCIL, WRITE_CHECK_DEPTH, WRITE_CHECK_ALPHA}) {
            assertArrayEquals(new int[]{-1, -1, -1, -1}, computeBounds(empty, mode));
            assertEquals(0, getWrittenPixelCount(empty, mode));
        }
        assertEquals(-1.0f, computeDepth(empty, DEPTH_MODE_AVG, WRITE_CHECK_STENCIL));
        assertEquals(-1.0f, computeDepth(empty, DEPTH_MODE_MIN, WRITE_CHECK_STENCIL));
        assertEquals(-1.0f, computeDepth(empty, DEPTH_MODE_MAX, WRITE_CHECK_STENCIL));
        assertEquals(0, computeFaceTint(empty, WRITE_CHECK_STENCIL));
    }

    @Test
    void writeChecksUseStencilDepthOrAlpha() {
        var face = TestFaces.unwritten();
        TestFaces.put(face, 0, 0, 0xFF000000, 0xFFFFFF, 1);   // stencil only
        TestFaces.put(face, 1, 0, 0x01000000, 0x000010, 0);   // depth only, alpha 1 does not count
        TestFaces.put(face, 2, 0, 0x02000000, 0xFFFFFF, 0);   // alpha only
        assertEquals(1, getWrittenPixelCount(face, WRITE_CHECK_STENCIL));
        assertEquals(1, getWrittenPixelCount(face, WRITE_CHECK_DEPTH));
        assertEquals(2, getWrittenPixelCount(face, WRITE_CHECK_ALPHA));
        assertThrows(IllegalArgumentException.class, () -> getWrittenPixelCount(face, 0));
    }

    @Test
    void depthModesAverageMinAndMaxOverWrittenPixels() {
        var face = TestFaces.unwritten();
        TestFaces.put(face, 0, 0, OPAQUE, 0x100000, 1);
        TestFaces.put(face, 1, 0, OPAQUE, 0x300000, 1);
        TestFaces.put(face, 2, 0, OPAQUE, 0x000040, 0); // unwritten by stencil, must be ignored
        float scale = (1 << 24) - 1;
        assertEquals(0x200000 / scale, computeDepth(face, DEPTH_MODE_AVG, WRITE_CHECK_STENCIL), 1e-6f);
        assertEquals(0x100000 / scale, computeDepth(face, DEPTH_MODE_MIN, WRITE_CHECK_STENCIL), 1e-6f);
        assertEquals(0x300000 / scale, computeDepth(face, DEPTH_MODE_MAX, WRITE_CHECK_STENCIL), 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> computeDepth(face, 0, WRITE_CHECK_STENCIL));
        assertEquals(1.0f, computeDepth(TestFaces.uniform(OPAQUE, 0xFFFFFF, 1), DEPTH_MODE_MAX,
                WRITE_CHECK_STENCIL));
    }

    @Test
    void faceTintStates() {
        var untinted = TestFaces.rect(0, 0, 3, 3, OPAQUE, 0, 1);
        assertEquals(1, computeFaceTint(untinted, WRITE_CHECK_STENCIL));

        var some = TestFaces.rect(0, 0, 3, 3, OPAQUE, 0, 1);
        TestFaces.put(some, 0, 0, OPAQUE, 0, 0x81);
        assertEquals(2, computeFaceTint(some, WRITE_CHECK_STENCIL));

        var all = TestFaces.rect(0, 0, 3, 3, OPAQUE, 0, 0x81);
        assertEquals(3, computeFaceTint(all, WRITE_CHECK_STENCIL));

        // fully black and alpha 0 pixels do not take part
        TestFaces.put(all, 5, 5, 0xFF000000, 0, 1);
        TestFaces.put(all, 6, 5, 0x00FFFFFF, 0, 1);
        assertEquals(3, computeFaceTint(all, WRITE_CHECK_STENCIL));

        var onlyBlack = TestFaces.uniform(0xFF000000, 0, 0x81);
        assertEquals(0, computeFaceTint(onlyBlack, WRITE_CHECK_STENCIL));
    }

    @Test
    void translucentAndSolidDetectionOnlyLookAtWrittenPixels() {
        var solid = TestFaces.rect(0, 0, 7, 7, 0xFFFFFFFF, 0, 1);
        assertFalse(hasTranslucentPixel(solid));
        assertTrue(isSolidWhereDrawn(solid));
        assertFalse(isSolid(solid)); // unwritten texels have alpha 0

        assertTrue(isSolid(TestFaces.uniform(0xFFFFFFFF, 0, 1)));

        var translucent = TestFaces.rect(0, 0, 7, 7, 0x80FFFFFF, 0, 1);
        assertTrue(hasTranslucentPixel(translucent));
        assertFalse(isSolidWhereDrawn(translucent));

        var hiddenTranslucent = TestFaces.unwritten();
        TestFaces.put(hiddenTranslucent, 4, 4, 0x80FFFFFF, 0, 0);
        assertFalse(hasTranslucentPixel(hiddenTranslucent));
        assertTrue(isSolidWhereDrawn(hiddenTranslucent));

        var cutout = TestFaces.rect(0, 0, 7, 7, 0xFFFFFFFF, 0, 1);
        TestFaces.put(cutout, 3, 3, 0x00000000, 0, 1);
        assertFalse(hasTranslucentPixel(cutout)); // alpha 0 is a hole, not translucency
        assertFalse(isSolidWhereDrawn(cutout));
    }

    @Test
    void maskHasOneBitPerWrittenPixel() {
        var face = TestFaces.unwritten();
        TestFaces.put(face, 0, 0, OPAQUE, 0, 1);
        TestFaces.put(face, 5, 1, OPAQUE, 0, 1); // index 21
        TestFaces.put(face, 15, 15, OPAQUE, 0, 1); // index 255
        long[] mask = generateMask(face, WRITE_CHECK_STENCIL);
        assertEquals(4, mask.length);
        assertEquals(1L | (1L << 21), mask[0]);
        assertEquals(1L << 63, mask[3]);
        assertEquals(0L, mask[1]);
        assertEquals(0L, mask[2]);
    }

    @Test
    void mipColoursKeepsUpstreamInvertedFlagAndDivideByFour() {
        int c = TestFaces.abgr(255, 0x30, 0x20, 0xC8);
        int uniformPlain = mipColours(false, c, c, c, c);
        assertEquals(255, uniformPlain >>> 24);
        assertTrue(Math.abs(0xC8 - (uniformPlain & 0xFF)) <= 1, "colour survives the linear round trip");

        // darkened == true: translucent texels are excluded from the sum but the sum is still divided by 4
        int transparent = 0;
        int quarter = mipColours(true, c, transparent, transparent, transparent);
        float lin = ColorSRGB.srgbToLinear(0xC8) / 4;
        assertEquals(ColorSRGB.linearToSrgb(lin, ColorSRGB.srgbToLinear(0x20) / 4,
                ColorSRGB.srgbToLinear(0x30) / 4, 0) & 0x00FFFFFF, quarter & 0x00FFFFFF);
        assertTrue((quarter >>> 24) > 100 && (quarter >>> 24) < 160, "alpha is averaged linearly: " + (quarter >>> 24));
        assertEquals(255, mipColours(true, c, c, c, c) >>> 24);

        // darkened == false: all four texels count and alpha is an integer mean
        int plain = mipColours(false, c, transparent, transparent, transparent);
        assertEquals(255 / 4, plain >>> 24);
    }
}
