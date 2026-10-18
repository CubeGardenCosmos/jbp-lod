package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.model.ModelRecordEncoder.FaceStats;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelRecordEncoder.RenderLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRecordEncoderTest {
    private static final int OPAQUE = 0xFF808080;

    private static FaceStats full(int written, float average, float nearest) {
        return new FaceStats(0, 15, 0, 15, written, 0, 0, average, nearest);
    }

    @Test
    void faceBoundsArePackedAsMinXMaxXMinYMaxY() {
        FaceStats stats = new FaceStats(2, 13, 1, 14, 168, 0, 0, 0.4f, 0.25f);
        var solid = ModelRecordEncoder.encodeFace(stats, RenderLayer.SOLID, false);
        // depth round(0.4 * 64) = 26; 168 / 168 = 1.0 so no alpha discard; not full block => override bit 23
        assertEquals(2 | (13 << 4) | (1 << 8) | (14 << 12) | (26 << 16) | (1 << 23), solid.packedFaceData());
        assertEquals(0b1000, solid.metadataByte()); // not full, depth 0.4 neither occludes nor can be occluded
        assertFalse(solid.occludesNeighbour());

        var cutout = ModelRecordEncoder.encodeFace(stats, RenderLayer.CUTOUT, false);
        // nearest depth 0.25 => round(16); cutout always needs alpha discard
        assertEquals(2 | (13 << 4) | (1 << 8) | (14 << 12) | (16 << 16) | (1 << 22) | (1 << 23),
                cutout.packedFaceData());
        assertEquals(0b1100, cutout.metadataByte());

        var translucent = ModelRecordEncoder.encodeFace(stats, RenderLayer.TRANSLUCENT, false);
        assertEquals(2 | (13 << 4) | (1 << 8) | (14 << 12) | (16 << 16), translucent.packedFaceData());
        assertEquals(0b1100, translucent.metadataByte());
    }

    @Test
    void sparseCoverageEnablesAlphaDiscardOnSolidFaces() {
        FaceStats sparse = new FaceStats(2, 13, 1, 14, 120, 0, 0, 0.4f, 0.4f);
        var record = ModelRecordEncoder.encodeFace(sparse, RenderLayer.SOLID, false);
        assertEquals((1 << 22) | (1 << 23), record.packedFaceData() & ((1 << 22) | (1 << 23)));
    }

    @Test
    void alphaDiscardThresholdUsesDoubleNinetyPercentLikeUpstream() {
        // 9 / 10 as a float is slightly below 0.9 as a double, so upstream sets the discard bit
        FaceStats ninetyPercent = new FaceStats(0, 9, 0, 0, 9, 0, 0, 0.5f, 0.5f);
        FaceStats allCovered = new FaceStats(0, 9, 0, 0, 10, 0, 0, 0.5f, 0.5f);
        assertNotEquals(0, ModelRecordEncoder.encodeFace(ninetyPercent, RenderLayer.SOLID, false)
                .packedFaceData() & (1 << 22));
        assertEquals(0, ModelRecordEncoder.encodeFace(allCovered, RenderLayer.SOLID, false)
                .packedFaceData() & (1 << 22));
    }

    @Test
    void depthEncodingIsRoundedAndClampedTo62() {
        assertEquals(3 << 16, ModelRecordEncoder.encodeFace(full(256, 0.05f, 0.05f), RenderLayer.SOLID, false)
                .packedFaceData() & (0x3F << 16));
        assertEquals(62 << 16, ModelRecordEncoder.encodeFace(full(256, 1.0f, 1.0f), RenderLayer.SOLID, false)
                .packedFaceData() & (0x3F << 16));
        assertEquals(62 << 16, ModelRecordEncoder.encodeFace(full(256, 0.97f, 0.97f), RenderLayer.SOLID, false)
                .packedFaceData() & (0x3F << 16));
        assertEquals(0, ModelRecordEncoder.encodeFace(full(256, 0.0f, 0.0f), RenderLayer.SOLID, false)
                .packedFaceData() & (0x3F << 16));
    }

    @Test
    void fullFaceMetadataBits() {
        var solid = ModelRecordEncoder.encodeFace(full(256, 0.05f, 0.05f), RenderLayer.SOLID, false);
        assertEquals(0b1111, solid.metadataByte()); // occludes | full | can be occluded | self lit (depth > 0.01)
        assertTrue(solid.occludesNeighbour());
        // exactly 90% coverage does not occlude (strictly greater than), more does
        assertFalse(ModelRecordEncoder.encodeFace(full(230, 0.05f, 0.05f), RenderLayer.SOLID, false)
                .occludesNeighbour());
        assertTrue(ModelRecordEncoder.encodeFace(full(231, 0.05f, 0.05f), RenderLayer.SOLID, false)
                .occludesNeighbour());
        // too far away to occlude
        assertFalse(ModelRecordEncoder.encodeFace(full(256, 0.2f, 0.2f), RenderLayer.SOLID, false)
                .occludesNeighbour());
        var translucent = ModelRecordEncoder.encodeFace(full(256, 0.05f, 0.05f), RenderLayer.TRANSLUCENT, false);
        assertEquals(0b1110, translucent.metadataByte());
        assertFalse(translucent.occludesNeighbour());
        // flat faces do not use self lighting
        assertEquals(0, ModelRecordEncoder.encodeFace(full(256, 0.0f, 0.0f), RenderLayer.SOLID, false)
                .metadataByte() & 0b1000);
    }

    @Test
    void tintBitsOnlyWhenModelHasTintSource() {
        FaceStats partial = new FaceStats(0, 15, 0, 15, 256, 100, 50, 0.05f, 0.05f);
        FaceStats allTinted = new FaceStats(0, 15, 0, 15, 256, 100, 100, 0.05f, 0.05f);
        FaceStats none = new FaceStats(0, 15, 0, 15, 256, 100, 0, 0.05f, 0.05f);
        FaceStats noTintable = new FaceStats(0, 15, 0, 15, 256, 0, 0, 0.05f, 0.05f);
        assertEquals(1 << 24, ModelRecordEncoder.encodeFace(partial, RenderLayer.SOLID, true).packedFaceData() & (3 << 24));
        assertEquals(2 << 24, ModelRecordEncoder.encodeFace(allTinted, RenderLayer.SOLID, true).packedFaceData() & (3 << 24));
        assertEquals(0, ModelRecordEncoder.encodeFace(none, RenderLayer.SOLID, true).packedFaceData() & (3 << 24));
        assertEquals(0, ModelRecordEncoder.encodeFace(noTintable, RenderLayer.SOLID, true).packedFaceData() & (3 << 24));
        assertEquals(0, ModelRecordEncoder.encodeFace(allTinted, RenderLayer.SOLID, false).packedFaceData() & (3 << 24));
    }

    @Test
    void emptyFaceEncodesMinusOneAndFF() {
        var empty = ModelRecordEncoder.encodeFace(FaceStats.empty(), RenderLayer.CUTOUT, true);
        assertEquals(-1, empty.packedFaceData());
        assertEquals(0xFF, empty.metadataByte());
        assertFalse(empty.occludesNeighbour());
    }

    @Test
    void metadataWordHasFaceBytesThenGlobalFlags() {
        FaceStats[] faces = new FaceStats[6];
        java.util.Arrays.fill(faces, full(256, 0.0f, 0.0f));
        var encoded = ModelRecordEncoder.encode(faces, RenderLayer.SOLID, false, false, false,
                false, false, false, 15, -1, 0);
        for (int face = 0; face < 6; face++) {
            assertEquals(0b111, (encoded.metadata() >>> (8 * face)) & 0xFF); // occludes | full block | can be occluded
        }
        assertEquals(0x40 | (15 << 7), encoded.metadata() >>> 48);
        assertEquals(0, encoded.data().flags());
        assertEquals(-1, encoded.data().colourTint());

        // all individual global flags
        faces[0] = FaceStats.empty();
        faces[1] = FaceStats.empty();
        var flags = ModelRecordEncoder.encode(faces, RenderLayer.TRANSLUCENT, true, true, true,
                true, true, true, 0, 0xFF010203, 7);
        assertEquals(0xFF, flags.metadata() & 0xFF);
        assertEquals(0xFF, (flags.metadata() >>> 8) & 0xFF);
        // biome | translucent | doubleSided | fluidState | isFluid | cullsSame ; not fully opaque
        assertEquals(0b0011_1111, flags.metadata() >>> 48);
        assertEquals(0b1111, flags.data().flags()); // tint | biome | translucent | shaded
        assertEquals(7, flags.data().customId());
        assertEquals(-1, flags.data().face0());
        assertEquals(-1, flags.data().face1());

        // double sidedness comes from any missing opposite pair
        faces[0] = full(256, 0.0f, 0.0f);
        faces[1] = full(256, 0.0f, 0.0f);
        faces[4] = FaceStats.empty();
        faces[5] = FaceStats.empty();
        var side = ModelRecordEncoder.encode(faces, RenderLayer.SOLID, false, false, false,
                false, false, false, 0, -1, 0);
        assertEquals(1 << 2, (side.metadata() >>> 48) & (1 << 2));
        assertEquals(0, (side.metadata() >>> 48) & (1 << 6));
    }

    @Test
    void modelFlagsBits() {
        assertEquals(0, ModelRecordEncoder.modelFlags(false, false, false, false));
        assertEquals(1, ModelRecordEncoder.modelFlags(true, false, false, false));
        assertEquals(2, ModelRecordEncoder.modelFlags(false, true, false, false));
        assertEquals(4, ModelRecordEncoder.modelFlags(false, false, true, false));
        assertEquals(8, ModelRecordEncoder.modelFlags(false, false, false, true));
    }

    @Test
    void statsForCountsWrittenBoundsTintAndDepths() {
        var face = TestFaces.rect(2, 1, 13, 14, OPAQUE, 0x400000, 1);
        TestFaces.rect(face, 2, 1, 7, 14, OPAQUE, 0x400000, 0x81); // left half tinted
        TestFaces.put(face, 13, 14, 0xFF000000, 0x400000, 0x81);   // black pixel: written, not tintable
        TestFaces.put(face, 12, 14, OPAQUE, 0x200000, 1);          // nearer pixel
        FaceStats stats = ModelRecordEncoder.statsFor(face, RenderLayer.SOLID);
        assertEquals(2, stats.minX());
        assertEquals(13, stats.maxX());
        assertEquals(1, stats.minY());
        assertEquals(14, stats.maxY());
        assertEquals(12 * 14, stats.writtenPixels());
        assertEquals(12 * 14 - 1, stats.tintablePixels());
        assertEquals(6 * 14, stats.tintedPixels()); // left half only; black pixel excluded
        float scale = (1 << 24) - 1;
        assertEquals(0x200000 / scale, stats.nearestDepth(), 1e-6f);
        long total = (long) 0x400000 * (12 * 14 - 1) + 0x200000;
        assertEquals((float) ((double) (total / (12 * 14)) / scale), stats.averageDepth(), 1e-6f);
        assertEquals(2, stats.tintState());
    }

    @Test
    void statsForUsesStencilForSolidAndAlphaOtherwise() {
        var face = TestFaces.unwritten();
        TestFaces.put(face, 0, 0, 0xFF808080, 0, 1);   // stencil and alpha
        TestFaces.put(face, 1, 0, 0x01808080, 0, 1);   // stencil only (alpha 1 is not "written" by alpha)
        TestFaces.put(face, 2, 0, 0xFF808080, 0, 0);   // alpha only
        assertEquals(2, ModelRecordEncoder.statsFor(face, RenderLayer.SOLID).writtenPixels());
        assertEquals(2, ModelRecordEncoder.statsFor(face, RenderLayer.CUTOUT).writtenPixels());
        assertEquals(2, ModelRecordEncoder.statsFor(face, RenderLayer.TRANSLUCENT).writtenPixels());
        assertEquals(0, ModelRecordEncoder.statsFor(face, RenderLayer.SOLID).minX());
        assertEquals(1, ModelRecordEncoder.statsFor(face, RenderLayer.SOLID).maxX());
        assertEquals(0, ModelRecordEncoder.statsFor(face, RenderLayer.CUTOUT).minX());
        assertEquals(2, ModelRecordEncoder.statsFor(face, RenderLayer.CUTOUT).maxX());
    }

    @Test
    void statsForEmptyFaceIsEmptyStats() {
        assertEquals(FaceStats.empty(), ModelRecordEncoder.statsFor(TestFaces.unwritten(), RenderLayer.SOLID));
        assertEquals(FaceStats.empty(), ModelRecordEncoder.statsFor(TestFaces.zero(), RenderLayer.CUTOUT));
        assertFalse(ModelRecordEncoder.statsFor(TestFaces.zero(), RenderLayer.TRANSLUCENT).exists());
    }

    @Test
    void layerSelectionTruthTable() {
        var solid = TestFaces.six(TestFaces.rect(0, 0, 7, 7, 0xFFFFFFFF, 0, 1));
        var translucent = TestFaces.six(TestFaces.rect(0, 0, 7, 7, 0x80FFFFFF, 0, 1));
        var holes = TestFaces.six(TestFaces.rect(0, 0, 7, 7, 0x00000000, 0, 1));

        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(0, solid, false));
        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(0, translucent, false)); // bit 2 required
        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(1 | 2, solid, false));

        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(4, solid, false));
        assertEquals(RenderLayer.TRANSLUCENT, ModelRecordEncoder.selectLayer(4, translucent, false));
        assertEquals(RenderLayer.CUTOUT, ModelRecordEncoder.selectLayer(4, holes, false));
        assertEquals(RenderLayer.TRANSLUCENT, ModelRecordEncoder.selectLayer(4 | 8, translucent, false));
        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(4 | 8, solid, false));

        assertEquals(RenderLayer.CUTOUT, ModelRecordEncoder.selectLayer(8, solid, false));

        // leaves always render solid
        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(8, solid, true));
        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(4, translucent, true));
        assertEquals(RenderLayer.SOLID, ModelRecordEncoder.selectLayer(0, solid, true));
    }

    @Test
    void darkenedMipsFlagIsBitOne() {
        assertFalse(ModelRecordEncoder.needsDarkenedMips(0));
        assertFalse(ModelRecordEncoder.needsDarkenedMips(1 | 4 | 8));
        assertTrue(ModelRecordEncoder.needsDarkenedMips(2));
        assertTrue(ModelRecordEncoder.needsDarkenedMips(15));
    }
}
