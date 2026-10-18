package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Raw upstream 32³ mapper words, indexed x | z << 5 | y << 10, little endian. */
public final class StoredVoxelSection {
    public static final int VOXELS = 32 * 32 * 32;
    public static final int BYTES = VOXELS * Long.BYTES;
    private StoredVoxelSection() { }
    public static byte[] encode(long[] voxels) {
        if (voxels.length != VOXELS) throw new IllegalArgumentException("Expected 32768 voxels");
        ByteBuffer bytes = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asLongBuffer().put(voxels);
        return bytes.array();
    }
    public static long[] decode(byte[] bytes) {
        if (bytes.length != BYTES) throw new IllegalArgumentException("Invalid stored voxel section length: " + bytes.length);
        long[] voxels = new long[VOXELS];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(voxels);
        return voxels;
    }
}
