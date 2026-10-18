package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;

/**
 * VMA-backed shared UINT16 index buffer derived from upstream {@code SharedIndexBuffer}: the
 * 16380-quad (4 vertices per quad) pattern at index 0 and the 36-index section-AABB cube at index
 * {@link #CUBE_INDEX_OFFSET}, which is the {@code firstIndex} the upstream prep pass writes into the
 * cull draw command. Upstream stores the cube as bytes and draws it with GL_UNSIGNED_BYTE; Vulkan's
 * UINT8 index type is an optional extension, so the cube is stored as UINT16 at the same index.
 */
public final class LodIndexBuffer implements AutoCloseable {
    public static final int QUAD_CAPACITY = 16_380;
    public static final int QUAD_INDEX_COUNT = QUAD_CAPACITY * 6;
    /** Same value as upstream {@code CUBE_INDEX_OFFSET} and {@code firstIndex} of the cull command. */
    public static final int CUBE_INDEX_OFFSET = (1 << 16) * 6 * 2;
    public static final int CUBE_INDEX_COUNT = 6 * 2 * 3;
    private static final int INDEX_BYTES = Short.BYTES;

    /** Upstream cube corner order: bit0 = +x, bit1 = +z, bit2 = +y of {@code raster.vert}. */
    private static final byte[] CUBE = {
            0, 1, 2, 3, 2, 1,
            6, 5, 4, 5, 6, 7,
            0, 4, 1, 5, 1, 4,
            3, 6, 2, 6, 3, 7,
            2, 4, 0, 4, 2, 6,
            1, 5, 3, 7, 3, 5,
    };

    private final GpuBuffer buffer;

    private LodIndexBuffer(GpuBuffer buffer) {
        this.buffer = buffer;
    }

    public static LodIndexBuffer create(GameVulkan.Snapshot game) {
        long bytes = (long) (CUBE_INDEX_OFFSET + CUBE_INDEX_COUNT) * INDEX_BYTES;
        GpuBuffer buffer = GpuBuffer.allocate(game, bytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, true);
        try {
            ByteBuffer indices = buffer.mappedBytes().order(ByteOrder.LITTLE_ENDIAN);
            writeQuadPattern(indices);
            writeCube(indices);
            buffer.flush(0, bytes);
            return new LodIndexBuffer(buffer);
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    /** Fills the quad region: indices (v+1, v+2, v, v+1, v+3, v+2) per quad, upstream's winding. */
    static void writeQuadPattern(ByteBuffer indices) {
        int index = 0;
        for (int quad = 0; quad < QUAD_CAPACITY; quad++) {
            int vertex = quad * 4;
            indices.putShort(index++ * INDEX_BYTES, (short) (vertex + 1));
            indices.putShort(index++ * INDEX_BYTES, (short) (vertex + 2));
            indices.putShort(index++ * INDEX_BYTES, (short) vertex);
            indices.putShort(index++ * INDEX_BYTES, (short) (vertex + 1));
            indices.putShort(index++ * INDEX_BYTES, (short) (vertex + 3));
            indices.putShort(index++ * INDEX_BYTES, (short) (vertex + 2));
        }
    }

    static void writeCube(ByteBuffer indices) {
        for (int i = 0; i < CUBE_INDEX_COUNT; i++) {
            indices.putShort((CUBE_INDEX_OFFSET + i) * INDEX_BYTES, CUBE[i]);
        }
    }

    public long handle() {
        return buffer.handle();
    }

    @Override
    public void close() {
        buffer.close();
    }
}
