package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class VoxelHierarchyTest {
    @Test void rawCodecHasExactLittleEndianLayout() {
        long[] words = new long[32768];
        words[0] = 0x0123456789ABCDEFL;
        words[31 | 17 << 5 | 29 << 10] = -1;
        byte[] bytes = StoredVoxelSection.encode(words);
        assertEquals(262144, bytes.length);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0x01, bytes[7]);
        assertArrayEquals(words, StoredVoxelSection.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> StoredVoxelSection.decode(new byte[8]));
    }
    @Test void negativeChunkSectionMapsIntoPositiveLocalOctant() {
        long key = SectionPos.pack(0, -1 >> 1, -3 >> 1, -5 >> 1);
        assertEquals(-1, SectionPos.x(key)); assertEquals(-2, SectionPos.y(key)); assertEquals(-3, SectionPos.z(key));
        long[] target = new long[32768], input = new long[4096];
        long block = 7L << 27;
        Arrays.fill(input, block);
        VoxelHierarchy.insert(target, input, 0, -1, -3, -5);
        assertEquals(block, target[16 | 16 << 5 | 16 << 10]);
        assertEquals(block, target[32767]);
        assertEquals(0, target[0]);
        assertEquals((byte) 128, VoxelHierarchy.children(target));
        Arrays.fill(input, 0);
        VoxelHierarchy.insert(target, input, 0, -1, -3, -5);
        assertEquals(0, VoxelHierarchy.children(target));
    }
    @Test void upstreamMipUsesOpacityThenXThenYThenZ() {
        long[] words = new long[32768];
        words[0] = 1L << 27;
        words[1] = 2L << 27;
        words[32] = 3L << 27;
        words[1024] = 4L << 27;
        assertEquals(2L << 27, VoxelHierarchy.mip(words, 0, 32, word -> 1));
        assertEquals(3L << 27, VoxelHierarchy.mip(words, 0, 32, word -> IdMapper.getBlockId(word) == 3 ? 15 : 1));
    }
    @Test void airLightAveragesBlockDownAndSkyUp() {
        long[] words = new long[32768];
        words[0] = IdMapper.airWithLight(0x11);
        assertEquals(IdMapper.airWithLight(1), VoxelHierarchy.mip(words, 0, 32, word -> 0));
    }
    @Test void parentInsertionKeepsStoredSiblingOctants() {
        long[] parent = new long[32768], child = new long[32768];
        parent[31 | 31 << 5 | 31 << 10] = 9L << 27;
        child[0] = 2L << 27;
        long[] compact = VoxelHierarchy.downsample(child, 32, word -> 15);
        VoxelHierarchy.insert(parent, compact, 0, 0, 0, 0);
        assertEquals(2L << 27, parent[0]);
        assertEquals(9L << 27, parent[32767]);
        assertEquals((byte) 129, VoxelHierarchy.children(parent));
    }
    @Test void eachLodWritesOnlyTheUpstreamCompactSubcube() {
        long block = 3L << 27;
        long[] source = new long[4096];
        Arrays.fill(source, block);
        for (int level = 0; level <= 4; level++) {
            long[] target = new long[32768];
            VoxelHierarchy.insert(target, source, level, -1, -1, -1);
            int size = 16 >> level;
            assertEquals(block, target[32767]);
            assertEquals(block, target[(32 - size) | (32 - size) << 5 | (32 - size) << 10]);
            assertEquals(0, target[0]);
            assertEquals(size * size * size, Arrays.stream(target).filter(word -> word != 0).count());
            if (level < 4) source = VoxelHierarchy.downsample(source, size, word -> 15);
        }
    }
}
