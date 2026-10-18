package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.model.ModelRecordEncoder.FaceStats;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelRecordEncoder.RenderLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelQueriesTest {
    private static long metadata(RenderLayer layer, boolean biome, boolean fluidState, boolean isFluid,
                                 boolean cullsSame, int light, FaceStats[] faces) {
        return ModelRecordEncoder.encode(faces, layer, false, biome, false, fluidState, isFluid, cullsSame,
                light, -1, 0).metadata();
    }

    @Test
    void faceAccessorsRoundTripEncoderOutput() {
        FaceStats[] faces = {
                new FaceStats(0, 15, 0, 15, 256, 0, 0, 0.05f, 0.05f),
                FaceStats.empty(),
                new FaceStats(2, 13, 1, 14, 120, 0, 0, 0.4f, 0.4f),
                new FaceStats(2, 13, 1, 14, 120, 0, 0, 0.2f, 0.2f),
                FaceStats.empty(),
                FaceStats.empty()
        };
        long meta = metadata(RenderLayer.SOLID, false, false, false, false, 0, faces);
        assertTrue(ModelQueries.faceExists(meta, 0));
        assertFalse(ModelQueries.faceExists(meta, 1));
        assertTrue(ModelQueries.faceExists(meta, 2));
        assertFalse(ModelQueries.faceExists(meta, 4));

        assertTrue(ModelQueries.faceOccludes(meta, 0));
        assertFalse(ModelQueries.faceOccludes(meta, 1));
        assertFalse(ModelQueries.faceOccludes(meta, 2));
        assertFalse(ModelQueries.faceOccludes(meta, 3));

        assertTrue(ModelQueries.faceCanBeOccluded(meta, 0));
        assertFalse(ModelQueries.faceCanBeOccluded(meta, 2));
        assertTrue(ModelQueries.faceCanBeOccluded(meta, 3));

        assertTrue(ModelQueries.faceUsesSelfLighting(meta, 0));
        assertTrue(ModelQueries.faceUsesSelfLighting(meta, 2));
        assertTrue(ModelQueries.faceUsesSelfLighting(meta, 3));
    }

    @Test
    void globalAccessorsRoundTripEncoderOutput() {
        FaceStats full = new FaceStats(0, 15, 0, 15, 256, 0, 0, 0.0f, 0.0f);
        FaceStats[] faces = {full, full, full, full, full, full};
        long opaque = metadata(RenderLayer.SOLID, false, false, false, false, 0, faces);
        assertTrue(ModelQueries.isFullyOpaque(opaque));
        assertEquals(1L, ModelQueries._isFullyOpaque(opaque));
        assertFalse(ModelQueries.isBiomeColoured(opaque));
        assertEquals(1L, ModelQueries._notIsBiomeColoured(opaque));
        assertFalse(ModelQueries.isTranslucent(opaque));
        assertFalse(ModelQueries.isDoubleSided(opaque));
        assertFalse(ModelQueries.containsFluid(opaque));
        assertFalse(ModelQueries.isFluid(opaque));
        assertFalse(ModelQueries.cullsSame(opaque));
        assertEquals(0L, ModelQueries.lightEmission(opaque));

        FaceStats[] sided = {full, full, full, full, FaceStats.empty(), FaceStats.empty()};
        long all = metadata(RenderLayer.TRANSLUCENT, true, true, true, true, 9, sided);
        assertTrue(ModelQueries.isBiomeColoured(all));
        assertEquals(1L, ModelQueries._isBiomeColoured(all));
        assertEquals(0L, ModelQueries._notIsBiomeColoured(all));
        assertTrue(ModelQueries.isTranslucent(all));
        assertEquals(1L, ModelQueries._isTranslucent(all));
        assertTrue(ModelQueries.isDoubleSided(all));
        assertEquals(1L, ModelQueries._isDoubleSided(all));
        assertTrue(ModelQueries.containsFluid(all));
        assertEquals(1L, ModelQueries._containsFluid(all));
        assertTrue(ModelQueries.isFluid(all));
        assertEquals(1L, ModelQueries._isFluid(all));
        assertTrue(ModelQueries.cullsSame(all));
        assertFalse(ModelQueries.isFullyOpaque(all));
        assertEquals(0L, ModelQueries._isFullyOpaque(all));
        assertEquals(9L, ModelQueries.lightEmission(all));
    }
}
