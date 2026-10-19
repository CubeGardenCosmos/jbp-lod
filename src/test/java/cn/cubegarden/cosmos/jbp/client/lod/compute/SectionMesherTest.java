package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.StoredVoxelSection;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.BuiltSection;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SectionMesherTest {
    private static final long OPAQUE = (64L << 48) | 0x050505050505L;
    private static long voxel(int id) { return IdMapper.composeMappingId((byte) 0, id, 0); }
    private static SectionMesher mesher(long metadata) {
        return new SectionMesher(models(metadata), key -> new long[32768]);
    }
    private static SectionMesher.Models models(long metadata) {
        return new SectionMesher.Models() {
            public int getModelId(int id) { return id; }
            public long getModelMetadataFromClientId(int id) { return id == 0 ? 0 : metadata; }
            public int getFluidClientStateId(int id) { return id; }
        };
    }
    private static BuiltSection build(SectionMesher mesher, long[] data) {
        return mesher.mesh(SectionPos.pack(0, 0, 0, 0), StoredVoxelSection.encode(data));
    }
    @Test void absentAndAirSectionsAreEmpty() {
        var mesher = mesher(OPAQUE);
        assertTrue(mesher.mesh(0, null).isEmpty());
        assertTrue(build(mesher, new long[32768]).isEmpty());
    }
    @Test void isolatedOpaqueVoxelEmitsEveryPackedFace() {
        long[] data = new long[32768];
        data[3 | (5 << 5) | (7 << 10)] = voxel(1);
        BuiltSection result = build(mesher(OPAQUE), data);
        assertEquals(6, result.quadCount());
        assertEquals((byte) 0xff, result.childExistence);
        var packed = ByteBuffer.wrap(result.packedQuads()).order(ByteOrder.LITTLE_ENDIAN);
        int faces = 0;
        while (packed.hasRemaining()) {
            long quad = packed.getLong();
            faces |= 1 << (quad & 7);
            assertEquals(1, (quad >>> 26) & 0xffff);
            assertEquals(0, (quad >>> 3) & 255);
        }
        assertEquals(63, faces);
    }
    @Test void fullSectionGreedilySplitsFacesAtSixteenAndCullsNeighborSeam() {
        long[] solid = new long[32768];
        Arrays.fill(solid, voxel(1));
        assertEquals(24, build(mesher(OPAQUE), solid).quadCount());
        long neighbor = SectionPos.pack(0, 1, 0, 0);
        var seam = new SectionMesher(models(OPAQUE), key -> key == neighbor ? solid : new long[32768]);
        assertEquals(20, build(seam, solid).quadCount());
        // Reusing the mutable upstream scratch state must not leak opaque masks into the next result.
        assertTrue(build(seam, new long[32768]).isEmpty());
    }
    @Test void fluidAndDoubleSidedGeometryKeepTheirFaceOrderBuckets() {
        long[] data = new long[32768];
        data[10 | (10 << 5) | (10 << 10)] = voxel(1);
        BuiltSection fluid = build(mesher((16L | 2L | 32L) << 48), data);
        assertEquals(6, fluid.quadCount());
        var words = ByteBuffer.wrap(fluid.packedQuads()).order(ByteOrder.LITTLE_ENDIAN);
        while (words.hasRemaining()) assertEquals(1, (words.getLong() >>> 26) & 0xffff);
        BuiltSection dual = build(mesher(4L << 48), data);
        assertEquals(6, dual.quadCount());
    }
    @Test void parentRetainsAllEightNonemptyChildOctants() {
        long[] data = new long[32768];
        for (int child = 0; child < 8; child++) {
            int x = (child & 1) * 16;
            int z = ((child >> 1) & 1) * 16;
            int y = ((child >> 2) & 1) * 16;
            data[x | (z << 5) | (y << 10)] = voxel(1);
        }
        BuiltSection result = mesher(OPAQUE).mesh(SectionPos.pack(1, 0, 0, 0), StoredVoxelSection.encode(data));
        assertEquals((byte) 255, result.childExistence);
    }
    @Test void missingModelsRemainPendingUntilPublicationAndEpochReplaysGeometry() {
        boolean[] ready = {false};
        var models = new SectionMesher.Models() {
            public int getModelId(int id) {
                if (!ready[0]) throw new SectionMesher.ModelNotReady(id, true);
                return id;
            }
            public long getModelMetadataFromClientId(int id) { return OPAQUE; }
            public int getFluidClientStateId(int id) { return id; }
        };
        var mesher = new SectionMesher(models, key -> new long[32768]);
        long[] data = new long[32768]; data[1025] = voxel(1);
        assertThrows(SectionMesher.ModelNotReady.class, () -> build(mesher, data));
        var retried = new ArrayList<Long>();
        mesher.retryReady(0, retried::add);
        assertTrue(retried.isEmpty());
        ready[0] = true;
        mesher.retryReady(0, retried::add);
        assertEquals(1, retried.size());
        assertEquals(6, build(mesher, data).quadCount());
        retried.clear();
        mesher.retryReady(1, retried::add);
        assertEquals(1, retried.size());
    }

    @Test void factoryWaitWithoutBlockIsNotRetriedPerFrameAndReplaysOnEpochChange() {
        boolean[] factory = {false};
        var models = new SectionMesher.Models() {
            public int getModelId(int id) { return id; }
            public long getModelMetadataFromClientId(int id) {
                if (!factory[0]) throw new SectionMesher.ModelNotReady(SectionMesher.ModelNotReady.NO_BLOCK, true);
                return OPAQUE;
            }
            public int getFluidClientStateId(int id) { return id; }
        };
        var mesher = new SectionMesher(models, key -> new long[32768]);
        long[] data = new long[32768]; data[1025] = voxel(1);
        assertThrows(SectionMesher.ModelNotReady.class, () -> build(mesher, data));
        assertEquals(0, mesher.waitingCount(), "epoch waits must not be stored as block waits");
        var retried = new ArrayList<Long>();
        for (int frame = 0; frame < 3; frame++) mesher.retryReady(0, retried::add);
        assertTrue(retried.isEmpty(), "no per-frame retry without a block dependency");
        factory[0] = true;
        mesher.retryReady(1, retried::add);
        assertEquals(1, retried.size(), "epoch change replays the requested position once");
        assertEquals(1, mesher.distinctRequestedCount());
        assertEquals(6, build(mesher, data).quadCount());
    }

    @Test void productionSectionSourceReturnsFullSkyAirForAMissingStoredSection() {
        var source = SectionMesher.sectionSource(key -> null);
        assertSame(SectionMesher.missingSectionAir(), source.apply(123L));
        long[] decoded = new long[32768];
        decoded[7] = IdMapper.composeMappingId((byte) 4, 3, 0);
        byte[] encoded = StoredVoxelSection.encode(decoded);
        assertArrayEquals(decoded, SectionMesher.sectionSource(key -> encoded).apply(456L));
    }

    @Test void missingSectionFallbackIsAirAtFullSkyLight() {
        long[] air = SectionMesher.missingSectionAir();
        assertEquals(32768, air.length);
        assertTrue(Arrays.stream(air).allMatch(word -> IdMapper.isAir(word)
                && IdMapper.getLightId(word) == 15));
    }

    @Test void outerFacesTakeTheNeighbourSectionLightAndAMissingSectionIsFullSkyAir() {
        long[] solid = new long[32768];
        Arrays.fill(solid, voxel(1));
        long[] fullSkyAir = new long[32768];
        Arrays.fill(fullSkyAir, IdMapper.airWithLight(15));

        BuiltSection lit = build(new SectionMesher(models(OPAQUE), key -> fullSkyAir), solid);
        assertEquals(24, lit.quadCount());
        var quads = ByteBuffer.wrap(lit.packedQuads()).order(ByteOrder.LITTLE_ENDIAN);
        while (quads.hasRemaining()) {
            assertEquals(15, (quads.getLong() >>> 55) & 0xFF,
                    "a face against full-sky air must carry sky light 15, the upstream missing-section default");
        }

        BuiltSection dark = build(new SectionMesher(models(OPAQUE), key -> new long[32768]), solid);
        quads = ByteBuffer.wrap(dark.packedQuads()).order(ByteOrder.LITTLE_ENDIAN);
        while (quads.hasRemaining()) {
            assertEquals(0, (quads.getLong() >>> 55) & 0xFF,
                    "the pre-fix all-zero fallback produced the black boundary faces");
        }
    }

    @Test void stoneFilledSectionMeshesToNonzeroQuadsAndNonzeroMetadataThroughEncodedPath() {
        // A solid 32^3 stone section as the ingest path stores it, meshed through the same encode/mesh entry point
        long[] stone = new long[32768];
        Arrays.fill(stone, voxel(1));
        var metadataLookups = new ArrayList<Integer>();
        SectionMesher.Models recording = new SectionMesher.Models() {
            public int getModelId(int id) { return id; }
            public long getModelMetadataFromClientId(int id) {
                metadataLookups.add(id);
                return id == 0 ? 0 : OPAQUE;
            }
            public int getFluidClientStateId(int id) { return id; }
        };
        BuiltSection result = build(new SectionMesher(recording, key -> new long[32768]), stone);
        assertFalse(result.isEmpty(), "solid stone must not mesh to an empty section");
        assertTrue(result.quadCount() > 0, "solid stone must emit quads");
        assertTrue(metadataLookups.contains(1), "stone model metadata must be looked up");
    }
}
