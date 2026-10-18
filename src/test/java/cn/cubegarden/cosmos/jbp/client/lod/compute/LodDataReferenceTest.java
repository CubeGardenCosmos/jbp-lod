package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.data.DrawIndexedIndirectCommand;
import cn.cubegarden.cosmos.jbp.client.lod.data.PackedQuad;
import cn.cubegarden.cosmos.jbp.client.lod.data.SectionGeometry;
import cn.cubegarden.cosmos.jbp.client.lod.data.SectionIngest;
import cn.cubegarden.cosmos.jbp.client.lod.data.SectionMeta;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LodDataReferenceTest {
    @Test
    void sectionMetadataRoundTripsExactStd430Words() {
        SectionMeta expected = new SectionMeta(0xA1234567, 0x89abcdef, 0x76543210, 17,
                0x00030002, 0x00050004, 0x00070006, 0x00090008);
        ByteBuffer bytes = ByteBuffer.allocate(SectionMeta.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        expected.writeTo(bytes, 0);

        assertEquals(SectionMeta.BYTE_SIZE, bytes.capacity());
        assertEquals(expected, SectionMeta.readFrom(bytes, 0));
        assertEquals(0x67, Byte.toUnsignedInt(bytes.get(0)));
        assertEquals(0x45, Byte.toUnsignedInt(bytes.get(1)));
        assertEquals(0x23, Byte.toUnsignedInt(bytes.get(2)));
        assertEquals(0xA1, Byte.toUnsignedInt(bytes.get(3)));
        assertEquals(0xA, expected.detail());
        assertEquals(2, expected.translucentQuadCount());
        assertEquals(3, expected.doubleSidedQuadCount());
        assertEquals(4, expected.quadCount(2));
        assertEquals(9, expected.quadCount(7));
        assertThrows(IndexOutOfBoundsException.class, () -> SectionMeta.readFrom(bytes, 1));
    }

    @Test
    void sectionIngestRebasesOnlyQuadStartsAndPreservesPackedGeometry() {
        SectionMeta first = new SectionMeta(0x10000001, 0x11111111, 0, 88,
                1, 0, 0, 0);
        SectionMeta second = new SectionMeta(0x20000002, 0x22222222, 3, 99,
                0, 0, 0, 0x00010000);
        byte[] firstQuads = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] secondQuads = {9, 10, 11, 12, 13, 14, 15, 16};

        SectionGeometry geometry = SectionIngest.pack(List.of(
                new SectionIngest.SectionInput(first, firstQuads),
                new SectionIngest.SectionInput(second, secondQuads)));

        assertEquals(2, geometry.sectionCount());
        assertEquals(2, geometry.quadCount());
        ByteBuffer metadata = ByteBuffer.wrap(geometry.metadataBytes());
        assertEquals(first.withFirstQuad(0), SectionMeta.readFrom(metadata, 0));
        assertEquals(second.withFirstQuad(1), SectionMeta.readFrom(metadata, SectionMeta.BYTE_SIZE));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                geometry.quadBytes());
        assertEquals(new PackedQuad(0x04030201, 0x08070605),
                PackedQuad.readFrom(ByteBuffer.wrap(geometry.quadBytes()), 0));
    }

    @Test
    void sectionIngestRejectsMismatchBetweenMetadataAndRawQuadCount() {
        SectionMeta metadata = new SectionMeta(0, 0, 0, 0, 1, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> SectionIngest.pack(
                List.of(new SectionIngest.SectionInput(metadata, new byte[16]))));
        assertThrows(IllegalArgumentException.class,
                () -> new SectionIngest.SectionInput(metadata, new byte[7]));
    }

    @Test
    void prepReferenceMatchesDispatchAndCullCommandBoundaries() {
        assertEquals(0, SectionPreparationReference.prepare(0).dispatchX());
        assertEquals(1, SectionPreparationReference.prepare(1).dispatchX());
        assertEquals(1, SectionPreparationReference.prepare(128).dispatchX());
        assertEquals(2, SectionPreparationReference.prepare(129).dispatchX());
        var result = SectionPreparationReference.prepare(129);
        assertEquals(0, result.opaqueDrawCount());
        assertEquals(0, result.translucentDrawCount());
        assertEquals(0, result.temporalOpaqueDrawCount());
        assertEquals(new DrawIndexedIndirectCommand(36, 129, 786_432, 0, 0), result.cullCommand());
    }

    @Test
    void prefixReferenceMatchesFourComponentExclusiveScan() {
        int[][] result = PrefixSumReference.exclusiveScan(new int[][]{
                {1, 2, 3, 4},
                {2, 0, 1, 5},
                {0, 0, 0, 0}
        });
        assertArrayEquals(new int[]{0, 1, 3, 6}, result[0]);
        assertArrayEquals(new int[]{10, 12, 12, 13}, result[1]);
        assertArrayEquals(new int[]{18, 18, 18, 18}, result[2]);
        assertArrayEquals(new int[0][], PrefixSumReference.exclusiveScan(new int[0][]));
        assertThrows(IllegalArgumentException.class,
                () -> PrefixSumReference.exclusiveScan(new int[][]{{1, 2, 3}}));
    }
}
