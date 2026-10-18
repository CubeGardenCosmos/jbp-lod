package cn.cubegarden.cosmos.jbp.client.lod.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelContractTest {
    @Test
    void atlasFaceSelectionMatchesShaderTileAddressing() {
        assertEquals(0, ModelAtlasLayout.faceIndex(0)); // down
        assertEquals(5, ModelAtlasLayout.faceIndex(5)); // east
        assertEquals(12_240, ModelAtlasLayout.tileX(255));
        assertEquals(8_160, ModelAtlasLayout.tileY(0xffff));
        assertEquals(12_272, ModelAtlasLayout.faceX(0xffff, 4));
        assertEquals(8_176, ModelAtlasLayout.faceY(0xffff, 1));
        assertEquals(ModelAtlasLayout.WIDTH, 12_288);
        assertEquals(ModelAtlasLayout.HEIGHT, 8_192);
        assertEquals(3, ModelAtlasLayout.MAX_MIP_LOD);
        assertEquals(4, ModelAtlasLayout.MIP_LEVELS);
        assertThrows(IllegalArgumentException.class, () -> ModelAtlasLayout.faceIndex(6));
        assertThrows(IllegalArgumentException.class, () -> ModelAtlasLayout.tileX(0x1_0000));
    }

    @Test
    void modelIdsAreStableDenseDeduplicatedAndUseTheIngestDomain() {
        ModelIdTable ids = new ModelIdTable();
        byte[] firstModel = {1, 2, 3, 4};
        assertEquals(0, ids.modelIdForBlockState(0));
        assertEquals(-1, ids.modelIdForBlockState(1));
        assertEquals(1, ids.register(10, firstModel));
        firstModel[0] = 99;
        assertEquals(1, ids.register(11, new byte[]{1, 2, 3, 4}));
        assertEquals(1, ids.register(10, new byte[]{8}));
        assertEquals(2, ids.register(12, new byte[]{8}));
        assertEquals(1, ids.modelIdForBlockState(10));
        assertEquals(1, ids.modelIdForBlockState(11));
        assertEquals(2, ids.modelIdForBlockState(12));
        assertEquals(3, ids.modelCount());
        assertEquals(-1, ids.modelIdForBlockState(1));
    }

    @Test
    void faceEncodingPreservesOcclusionDepthBoundsTintAndLayerFlags() {
        ModelRecordEncoder.FaceStats fullFace = new ModelRecordEncoder.FaceStats(
                0, 15, 0, 15, 256, 100, 100, 0.05f, 0.01f);
        var solid = ModelRecordEncoder.encodeFace(fullFace, ModelRecordEncoder.RenderLayer.SOLID, true);
        assertEquals(0x0203f0f0, solid.packedFaceData()); // full bounds, depth 3 << 16, full tint 2 << 24
        assertEquals(0b1111, solid.metadataByte()); // occludes + full block + can be occluded + self lit
        assertEquals(true, solid.occludesNeighbour());

        var cutout = ModelRecordEncoder.encodeFace(fullFace, ModelRecordEncoder.RenderLayer.CUTOUT, true);
        assertEquals((1 << 22) | 0x0201f0f0, cutout.packedFaceData()); // nearest depth 0.01 => 1 << 16
        assertEquals(0b0111, cutout.metadataByte());

        var translucent = ModelRecordEncoder.encodeFace(fullFace,
                ModelRecordEncoder.RenderLayer.TRANSLUCENT, true);
        assertEquals(0, translucent.packedFaceData() & ((1 << 22) | (1 << 23)));
        assertEquals(0b1110, translucent.metadataByte()); // self-lit, can be occluded and full block, but non-occluding
        assertEquals(false, translucent.occludesNeighbour());

        var empty = ModelRecordEncoder.encodeFace(ModelRecordEncoder.FaceStats.empty(),
                ModelRecordEncoder.RenderLayer.SOLID, false);
        assertEquals(-1, empty.packedFaceData());
        assertEquals(0xff, empty.metadataByte());
        assertEquals(false, empty.occludesNeighbour());
    }

    @Test
    void modelRecordAndMetadataUseSixFacesAndUpstreamBitPositions() {
        ModelRecordEncoder.FaceStats[] faces = {
                new ModelRecordEncoder.FaceStats(0, 15, 0, 15, 256, 0, 0, 0.02f, 0.02f),
                ModelRecordEncoder.FaceStats.empty(),
                new ModelRecordEncoder.FaceStats(2, 13, 1, 14, 120, 40, 20, 0.4f, 0.3f),
                new ModelRecordEncoder.FaceStats(2, 13, 1, 14, 120, 40, 0, 0.4f, 0.3f),
                new ModelRecordEncoder.FaceStats(2, 13, 1, 14, 120, 40, 40, 0.4f, 0.3f),
                new ModelRecordEncoder.FaceStats(2, 13, 1, 14, 120, 40, 40, 0.4f, 0.3f)
        };
        var encoded = ModelRecordEncoder.encode(faces, ModelRecordEncoder.RenderLayer.CUTOUT,
                true, true, true, true, false, false, 11, 0x7f123456, 42);

        assertEquals(0b1011, encoded.data().flags());
        assertEquals(0x7f123456, encoded.data().colourTint());
        assertEquals(42, encoded.data().customId());
        assertEquals(0, encoded.data().padding0());
        assertEquals(0, encoded.data().padding6());
        assertEquals(-1, encoded.data().face1());
        // Global flags occupy bits 48–54; light emission begins at bit 55, within the same byte.
        assertEquals(0b1001, (int) ((encoded.metadata() >>> 48) & 0x7f));
        assertEquals(11, (encoded.metadata() >>> 55) & 0xf);
        assertEquals(0b01, encoded.data().face2() >>> 24 & 0b11); // partial tint
        assertThrows(IllegalArgumentException.class, () -> ModelRecordEncoder.encode(
                new ModelRecordEncoder.FaceStats[5], ModelRecordEncoder.RenderLayer.SOLID,
                false, false, false, false, false, false, 0, -1, 0));
    }

    @Test
    void colourTableRebasesBiomeRowsWhenBiomesArrive() {
        ModelColourTable colours = new ModelColourTable();
        int constant = colours.registerModel(1, true, false, 0x00112233, null);
        assertEquals(0xff112233, constant);
        assertEquals(-1, colours.registerModel(2, false, false, 0, null));
        int firstBase = colours.registerModel(3, true, true, 0, biome -> biome == 0
                ? 0x00010203 : 0x000a0b0c);
        assertEquals(0, firstBase);
        colours.registerBiome(0);
        colours.registerBiome(1);
        int secondBase = colours.registerModel(4, true, true, 0, biome -> biome == 0
                ? 0x000c0d0e : 0x000f1011);
        assertEquals(2, secondBase);

        ModelColourTable.Snapshot snapshot = colours.snapshot(5);
        assertEquals(2, snapshot.biomeCount());
        assertEquals(0xff112233, snapshot.colourTint(1));
        assertEquals(-1, snapshot.colourTint(2));
        assertEquals(0, snapshot.colourTint(3));
        assertEquals(2, snapshot.colourTint(4));
        assertEquals(0xff010203, snapshot.colourWords()[0]);
        assertEquals(0xff0a0b0c, snapshot.colourWords()[1]);
        assertEquals(0xff0c0d0e, snapshot.colourWords()[2]);
        assertEquals(0xff0f1011, snapshot.colourWords()[3]);
        assertEquals(16, snapshot.colourBytes().length);
    }
}
