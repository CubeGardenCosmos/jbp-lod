package cn.cubegarden.cosmos.jbp.client.lod.model;

import java.util.Arrays;

/** Builders for 16x16 baked faces in the upstream colour/depth word format. */
final class TestFaces {
    static final int SIZE = 16;
    static final int UNWRITTEN_DEPTH = 0xFFFFFF00;

    private TestFaces() {
    }

    /** Rasterized but uncovered: colour 0, depth24 = 0xFFFFFF, stencil 0. */
    static ColourDepthTextureData unwritten() {
        int[] depth = new int[SIZE * SIZE];
        Arrays.fill(depth, UNWRITTEN_DEPTH);
        return new ColourDepthTextureData(new int[SIZE * SIZE], depth, SIZE, SIZE);
    }

    static ColourDepthTextureData zero() {
        return new ColourDepthTextureData(new int[SIZE * SIZE], new int[SIZE * SIZE], SIZE, SIZE);
    }

    static ColourDepthTextureData uniform(int colour, int depth24, int stencil) {
        return rect(unwritten(), 0, 0, SIZE - 1, SIZE - 1, colour, depth24, stencil);
    }

    static ColourDepthTextureData rect(int x0, int y0, int x1, int y1, int colour, int depth24, int stencil) {
        return rect(unwritten(), x0, y0, x1, y1, colour, depth24, stencil);
    }

    static ColourDepthTextureData rect(ColourDepthTextureData face, int x0, int y0, int x1, int y1,
                                       int colour, int depth24, int stencil) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                put(face, x, y, colour, depth24, stencil);
            }
        }
        return face;
    }

    static void put(ColourDepthTextureData face, int x, int y, int colour, int depth24, int stencil) {
        face.colour()[x + y * SIZE] = colour;
        face.depth()[x + y * SIZE] = (depth24 << 8) | (stencil & 0xFF);
    }

    static ColourDepthTextureData[] six(ColourDepthTextureData face) {
        ColourDepthTextureData[] faces = new ColourDepthTextureData[6];
        for (int i = 0; i < 6; i++) {
            faces[i] = new ColourDepthTextureData(face.colour().clone(), face.depth().clone(), SIZE, SIZE);
        }
        return faces;
    }

    static int abgr(int a, int b, int g, int r) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
