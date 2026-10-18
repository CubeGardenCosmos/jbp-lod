package cn.cubegarden.cosmos.jbp.client.lod.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MipGenTest {
    private static int texel(byte[] tile, int level, int x, int y) {
        int width = ModelAtlasLayout.mipWidth(level);
        int offset = ModelAtlasLayout.mipOffsetBytes(level) + (x + y * width) * 4;
        return (tile[offset] & 0xFF) | (tile[offset + 1] & 0xFF) << 8
                | (tile[offset + 2] & 0xFF) << 16 | (tile[offset + 3] & 0xFF) << 24;
    }

    private static ColourDepthTextureData[] distinctOpaqueFaces() {
        ColourDepthTextureData[] faces = new ColourDepthTextureData[6];
        for (int i = 0; i < 6; i++) {
            faces[i] = TestFaces.uniform(TestFaces.abgr(255, 10 * i + 1, 20 * i + 2, 30 * i + 3), 0, 1);
        }
        return faces;
    }

    @Test
    void layoutHasFourLevelsTotallingTheTileSize() {
        assertEquals(8160, ModelAtlasLayout.TILE_BYTES_WITH_MIPS);
        assertEquals(0, ModelAtlasLayout.mipOffsetBytes(0));
        assertEquals(6144, ModelAtlasLayout.mipOffsetBytes(1));
        assertEquals(7680, ModelAtlasLayout.mipOffsetBytes(2));
        assertEquals(8064, ModelAtlasLayout.mipOffsetBytes(3));
        assertEquals(6144, ModelAtlasLayout.mipBytes(0));
        assertEquals(1536, ModelAtlasLayout.mipBytes(1));
        assertEquals(384, ModelAtlasLayout.mipBytes(2));
        assertEquals(96, ModelAtlasLayout.mipBytes(3));
        assertEquals(4, ModelAtlasLayout.MIP_LEVELS);
        assertEquals(3, ModelAtlasLayout.MAX_MIP_LOD);
        assertThrows(IllegalArgumentException.class, () -> ModelAtlasLayout.mipOffsetBytes(4));
        assertEquals(24, ModelAtlasLayout.mipWidth(1));
        assertEquals(4, ModelAtlasLayout.mipHeight(3));
    }

    @Test
    void facesAreLaidOutDownUpNorthSouthWestEastAsThreeByTwo() {
        byte[] tile = MipGen.putTextures(true, distinctOpaqueFaces());
        assertEquals(ModelAtlasLayout.TILE_BYTES_WITH_MIPS, tile.length);
        for (int face = 0; face < 6; face++) {
            int x = ModelAtlasLayout.faceX(0, face);
            int y = ModelAtlasLayout.faceY(0, face);
            int expected = TestFaces.abgr(255, 10 * face + 1, 20 * face + 2, 30 * face + 3);
            assertEquals(expected, texel(tile, 0, x, y));
            assertEquals(expected, texel(tile, 0, x + 15, y + 15));
        }
    }

    @Test
    void mipLevelsOfOpaqueFacesKeepTheirColourAndOpaqueAlpha() {
        byte[] tile = MipGen.putTextures(false, distinctOpaqueFaces());
        for (int level = 1; level < ModelAtlasLayout.MIP_LEVELS; level++) {
            for (int face = 0; face < 6; face++) {
                int size = ModelAtlasLayout.FACE_SIZE >> level;
                int x = (face >> 1) * size;
                int y = (face & 1) * size;
                int texel = texel(tile, level, x + size / 2, y + size / 2);
                assertEquals(255, texel >>> 24, "level " + level + " face " + face);
                assertTrue(Math.abs((texel & 0xFF) - (30 * face + 3)) <= level);
                assertTrue(Math.abs(((texel >> 8) & 0xFF) - (20 * face + 2)) <= level);
                assertTrue(Math.abs(((texel >> 16) & 0xFF) - (10 * face + 1)) <= level);
            }
        }
    }

    @Test
    void solidifyFillsTransparentTexelsWithNearestOpaqueColourAtAlphaZero() {
        var face = TestFaces.zero();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 8; x++) {
                face.colour()[x + y * 16] = 0xFF000000 | (y * 16);
            }
        }
        ColourDepthTextureData[] faces = TestFaces.six(TestFaces.uniform(0xFFFFFFFF, 0, 1));
        faces[0] = face;

        byte[] solid = MipGen.putTextures(false, faces);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int expected = x < 8 ? 0xFF000000 | (y * 16) : y * 16;
                assertEquals(expected, texel(solid, 0, x, y), "texel " + x + "," + y);
            }
        }
        // untouched opaque faces are unchanged
        assertEquals(0xFFFFFFFF, texel(solid, 0, 16, 0));

        byte[] darkened = MipGen.putTextures(true, faces);
        for (int y = 0; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                assertEquals(0, texel(darkened, 0, x, y));
            }
        }
    }

    @Test
    void darkenedAndSolidifiedMipsDifferOnTransparentEdges() {
        var face = TestFaces.zero();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 8; x++) {
                face.colour()[x + y * 16] = 0xFFFFFFFF;
            }
        }
        ColourDepthTextureData[] faces = TestFaces.six(TestFaces.uniform(0xFFFFFFFF, 0, 1));
        faces[0] = face;
        byte[] plain = MipGen.putTextures(false, faces);
        byte[] dark = MipGen.putTextures(true, faces);
        // fully transparent block of level 0 averaged at level 1: plain keeps alpha 0, both agree on opaque areas
        assertEquals(0, texel(plain, 1, 6, 0) >>> 24);
        assertEquals(0, texel(dark, 1, 6, 0) >>> 24);
        assertEquals(255, texel(plain, 1, 0, 0) >>> 24);
        assertEquals(255, texel(dark, 1, 0, 0) >>> 24);
        // plain mips average solidified colour (white) for the transparent block
        assertEquals(0xFFFFFF, texel(plain, 1, 6, 0) & 0xFFFFFF);
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> MipGen.putTextures(false, new ColourDepthTextureData[5]));
        ColourDepthTextureData[] faces = TestFaces.six(TestFaces.zero());
        faces[2] = new ColourDepthTextureData(new int[4], new int[4], 2, 2);
        assertThrows(IllegalArgumentException.class, () -> MipGen.putTextures(false, faces));
    }
}
