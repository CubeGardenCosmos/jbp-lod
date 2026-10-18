package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The upstream fallback quad format: exactly two packed 32-bit words (ivec2/uvec2). */
public record PackedQuad(int lowWord, int highWord) {
    public static final int BYTE_SIZE = 8;

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("packed quad does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(byteOffset, lowWord);
        bytes.putInt(byteOffset + 4, highWord);
    }

    public static PackedQuad readFrom(ByteBuffer source, int byteOffset) {
        if (byteOffset < 0 || byteOffset > source.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("packed quad does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = source.order(ByteOrder.LITTLE_ENDIAN);
        return new PackedQuad(bytes.getInt(byteOffset), bytes.getInt(byteOffset + 4));
    }
}
