package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 64-byte std430 BlockModel record consumed by Voxy's LOD vertex/fragment shaders. */
public record BlockModelData(
        int face0,
        int face1,
        int face2,
        int face3,
        int face4,
        int face5,
        int flags,
        int colourTint,
        int customId,
        int padding0,
        int padding1,
        int padding2,
        int padding3,
        int padding4,
        int padding5,
        int padding6
) {
    public static final int BYTE_SIZE = 64;

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("BlockModel does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(byteOffset, face0);
        bytes.putInt(byteOffset + 4, face1);
        bytes.putInt(byteOffset + 8, face2);
        bytes.putInt(byteOffset + 12, face3);
        bytes.putInt(byteOffset + 16, face4);
        bytes.putInt(byteOffset + 20, face5);
        bytes.putInt(byteOffset + 24, flags);
        bytes.putInt(byteOffset + 28, colourTint);
        bytes.putInt(byteOffset + 32, customId);
        bytes.putInt(byteOffset + 36, padding0);
        bytes.putInt(byteOffset + 40, padding1);
        bytes.putInt(byteOffset + 44, padding2);
        bytes.putInt(byteOffset + 48, padding3);
        bytes.putInt(byteOffset + 52, padding4);
        bytes.putInt(byteOffset + 56, padding5);
        bytes.putInt(byteOffset + 60, padding6);
    }
}
