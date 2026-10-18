package cn.cubegarden.cosmos.jbp.client.lod.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Deduplication key of a baked model. Upstream keys its model table by a {@code ModelEntry} record holding the
 * six full face arrays plus fluid id and tint colour; this class replaces that equality key with a SHA-256
 * digest over a canonical little-endian encoding of the same inputs (each face's colour[] then depth[], then
 * fluidModelId, then tintingColour). It is collision-safe for practical purposes and bounds memory to 32 bytes
 * per model instead of retaining about 12 KiB of arrays per model.
 */
public final class ModelSignature {
    public static final int LENGTH = 32;

    private ModelSignature() {
    }

    public static byte[] of(ColourDepthTextureData[] sixFaces, int fluidModelId, int tintingColour) {
        Objects.requireNonNull(sixFaces, "sixFaces");
        if (sixFaces.length != 6) {
            throw new IllegalArgumentException("a block model requires exactly six faces");
        }
        MessageDigest digest = newDigest();
        for (ColourDepthTextureData face : sixFaces) {
            Objects.requireNonNull(face, "face");
            update(digest, face.colour());
            update(digest, face.depth());
        }
        ByteBuffer tail = ByteBuffer.allocate(2 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        tail.putInt(fluidModelId).putInt(tintingColour);
        digest.update(tail.array());
        return digest.digest();
    }

    /**
     * Signature of six all-zero 16x16 faces (colour 0, depth 0, i.e. never rasterized: air and invisible
     * render shapes, as upstream zero-fills the bake output), with fluidModelId -1 and tintingColour -1.
     * Rasterized-but-uncovered faces carry depth 0xFFFFFF00 and therefore hash differently.
     */
    public static byte[] empty() {
        int pixels = ModelAtlasLayout.FACE_SIZE * ModelAtlasLayout.FACE_SIZE;
        ColourDepthTextureData[] faces = new ColourDepthTextureData[6];
        for (int i = 0; i < faces.length; i++) {
            faces[i] = new ColourDepthTextureData(new int[pixels], new int[pixels],
                    ModelAtlasLayout.FACE_SIZE, ModelAtlasLayout.FACE_SIZE);
        }
        return of(faces, -1, -1);
    }

    private static void update(MessageDigest digest, int[] words) {
        ByteBuffer bytes = ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(words);
        digest.update(bytes.array());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }
}
