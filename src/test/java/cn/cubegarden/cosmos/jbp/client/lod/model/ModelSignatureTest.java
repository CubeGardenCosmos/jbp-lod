package cn.cubegarden.cosmos.jbp.client.lod.model;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class ModelSignatureTest {
    private static ColourDepthTextureData[] faces() {
        ColourDepthTextureData[] faces = new ColourDepthTextureData[6];
        for (int i = 0; i < 6; i++) {
            faces[i] = TestFaces.rect(i, i, 10, 12, 0xFF000000 | (i * 0x111111), 0x1000 * i, 1);
        }
        return faces;
    }

    @Test
    void equalInputsGiveEqualThirtyTwoByteDigests() {
        byte[] a = ModelSignature.of(faces(), 3, 0xFF123456);
        byte[] b = ModelSignature.of(faces(), 3, 0xFF123456);
        assertEquals(ModelSignature.LENGTH, a.length);
        assertArrayEquals(a, b);
    }

    @Test
    void anyDifferingInputChangesTheDigest() {
        byte[] base = ModelSignature.of(faces(), 3, 0xFF123456);
        assertFalse(java.util.Arrays.equals(base, ModelSignature.of(faces(), 4, 0xFF123456)));
        assertFalse(java.util.Arrays.equals(base, ModelSignature.of(faces(), 3, 0xFF123457)));

        ColourDepthTextureData[] colour = faces();
        colour[4].colour()[100] ^= 1;
        assertFalse(java.util.Arrays.equals(base, ModelSignature.of(colour, 3, 0xFF123456)));

        ColourDepthTextureData[] depth = faces();
        depth[5].depth()[255] ^= 0x100;
        assertFalse(java.util.Arrays.equals(base, ModelSignature.of(depth, 3, 0xFF123456)));

        ColourDepthTextureData[] swapped = faces();
        var tmp = swapped[0];
        swapped[0] = swapped[1];
        swapped[1] = tmp;
        assertFalse(java.util.Arrays.equals(base, ModelSignature.of(swapped, 3, 0xFF123456)));
    }

    @Test
    void digestIsSha256OverLittleEndianColourDepthPerFaceThenFluidAndTint() throws Exception {
        ColourDepthTextureData[] faces = faces();
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        for (ColourDepthTextureData face : faces) {
            for (int[] words : new int[][]{face.colour(), face.depth()}) {
                ByteBuffer bytes = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int word : words) {
                    bytes.putInt(word);
                }
                expected.update(bytes.array());
            }
        }
        expected.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).putInt(0x7F).array());
        assertArrayEquals(expected.digest(), ModelSignature.of(faces, -1, 0x7F));
    }

    @Test
    void emptyIsSignatureOfSixZeroFacesWithMinusOneFluidAndTint() {
        ColourDepthTextureData[] zeros = new ColourDepthTextureData[6];
        for (int i = 0; i < 6; i++) {
            zeros[i] = TestFaces.zero();
        }
        assertArrayEquals(ModelSignature.of(zeros, -1, -1), ModelSignature.empty());
        assertArrayEquals(ModelSignature.empty(), ModelSignature.empty());
        // a rasterized-but-uncovered face carries depth 0xFFFFFF00 and is not air
        assertFalse(java.util.Arrays.equals(ModelSignature.empty(),
                ModelSignature.of(TestFaces.six(TestFaces.unwritten()), -1, -1)));
        assertFalse(java.util.Arrays.equals(ModelSignature.empty(), ModelSignature.of(zeros, -1, 0)));
    }

    @Test
    void rejectsWrongFaceCount() {
        assertThrows(IllegalArgumentException.class, () -> ModelSignature.of(new ColourDepthTextureData[5], 0, 0));
        assertThrows(NullPointerException.class, () -> ModelSignature.of(new ColourDepthTextureData[6], 0, 0));
    }

    @Test
    void textureDataEqualityIsByContent() {
        var a = TestFaces.uniform(0xFF0000FF, 5, 1);
        var b = TestFaces.uniform(0xFF0000FF, 5, 1);
        var c = TestFaces.uniform(0xFF0000FE, 5, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
