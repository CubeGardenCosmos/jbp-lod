package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exact std430 SectionMeta representation used by Voxy's LOD compute shaders. */
public record SectionMeta(
        int packedPositionLow,
        int packedPositionHigh,
        int packedAabb,
        int firstQuad,
        int translucentAndDoubleSidedCounts,
        int downAndUpCounts,
        int northAndSouthCounts,
        int westAndEastCounts
) {
    public static final int BYTE_SIZE = 32;
    public static final int POSITION_LOW_OFFSET = 0;
    public static final int POSITION_HIGH_OFFSET = 4;
    public static final int AABB_OFFSET = 8;
    public static final int FIRST_QUAD_OFFSET = 12;
    public static final int COUNTS_OFFSET = 16;

    public int detail() {
        return packedPositionLow >>> 28;
    }

    public int translucentQuadCount() {
        return packedHalf(translucentAndDoubleSidedCounts, false);
    }

    public int doubleSidedQuadCount() {
        return packedHalf(translucentAndDoubleSidedCounts, true);
    }

    public int quadCount(int faceOrder) {
        return switch (faceOrder) {
            case 0 -> translucentQuadCount();
            case 1 -> doubleSidedQuadCount();
            case 2 -> packedHalf(downAndUpCounts, false);
            case 3 -> packedHalf(downAndUpCounts, true);
            case 4 -> packedHalf(northAndSouthCounts, false);
            case 5 -> packedHalf(northAndSouthCounts, true);
            case 6 -> packedHalf(westAndEastCounts, false);
            case 7 -> packedHalf(westAndEastCounts, true);
            default -> throw new IndexOutOfBoundsException("face/count order must be 0..7: " + faceOrder);
        };
    }

    public SectionMeta withFirstQuad(int quadIndex) {
        if (quadIndex < 0) {
            throw new IllegalArgumentException("quad index must be non-negative");
        }
        return new SectionMeta(packedPositionLow, packedPositionHigh, packedAabb, quadIndex,
                translucentAndDoubleSidedCounts, downAndUpCounts, northAndSouthCounts, westAndEastCounts);
    }

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("SectionMeta does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(byteOffset, packedPositionLow);
        bytes.putInt(byteOffset + 4, packedPositionHigh);
        bytes.putInt(byteOffset + 8, packedAabb);
        bytes.putInt(byteOffset + 12, firstQuad);
        bytes.putInt(byteOffset + 16, translucentAndDoubleSidedCounts);
        bytes.putInt(byteOffset + 20, downAndUpCounts);
        bytes.putInt(byteOffset + 24, northAndSouthCounts);
        bytes.putInt(byteOffset + 28, westAndEastCounts);
    }

    public static SectionMeta readFrom(ByteBuffer source, int byteOffset) {
        if (byteOffset < 0 || byteOffset > source.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("SectionMeta does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = source.order(ByteOrder.LITTLE_ENDIAN);
        return new SectionMeta(
                bytes.getInt(byteOffset), bytes.getInt(byteOffset + 4), bytes.getInt(byteOffset + 8),
                bytes.getInt(byteOffset + 12), bytes.getInt(byteOffset + 16), bytes.getInt(byteOffset + 20),
                bytes.getInt(byteOffset + 24), bytes.getInt(byteOffset + 28));
    }

    private static int packedHalf(int packed, boolean high) {
        return high ? packed >>> 16 : packed & 0xffff;
    }
}
