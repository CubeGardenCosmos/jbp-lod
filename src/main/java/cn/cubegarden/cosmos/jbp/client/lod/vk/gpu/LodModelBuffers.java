package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.data.BlockModelData;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Host-updated std430 model/colour SSBOs shared with the upstream quad shader ABI. */
public final class LodModelBuffers implements AutoCloseable {
    private final GpuBuffer modelData;
    private final GpuBuffer colourData;
    private final long modelBytes;
    private final long colourBytes;

    private LodModelBuffers(GpuBuffer modelData, GpuBuffer colourData,
                            long modelBytes, long colourBytes) {
        this.modelData = modelData;
        this.colourData = colourData;
        this.modelBytes = modelBytes;
        this.colourBytes = colourBytes;
    }

    /** Uploads model records as 64-byte BlockModel values and colour entries as uint32 words. */
    public static LodModelBuffers upload(GameVulkan.Snapshot game, byte[] packedModelRecords,
                                         byte[] packedColourWords) {
        if (packedModelRecords.length == 0 || packedModelRecords.length % BlockModelData.BYTE_SIZE != 0) {
            throw new IllegalArgumentException("model SSBO bytes must contain complete 64-byte BlockModel records");
        }
        if (packedColourWords.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("colour SSBO bytes must contain complete uint32 words");
        }
        long colourCapacity = Math.max(Integer.BYTES, packedColourWords.length);
        GpuBuffer models = GpuBuffer.allocate(game, packedModelRecords.length,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        GpuBuffer colours = null;
        try {
            colours = GpuBuffer.allocate(game, colourCapacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            models.mappedBytes().put(0, packedModelRecords);
            models.flush(0, packedModelRecords.length);
            if (packedColourWords.length == 0) {
                colours.mappedBytes().putInt(0, 0);
                colours.flush(0, Integer.BYTES);
            } else {
                colours.mappedBytes().put(0, packedColourWords);
                colours.flush(0, packedColourWords.length);
            }
            return new LodModelBuffers(models, colours, packedModelRecords.length, packedColourWords.length);
        } catch (RuntimeException | Error failure) {
            if (colours != null) colours.close();
            models.close();
            throw failure;
        }
    }

    public GpuBuffer models() {
        return modelData;
    }

    public GpuBuffer colours() {
        return colourData;
    }

    public long modelBytes() {
        return modelBytes;
    }

    public long colourBytes() {
        return colourBytes;
    }

    @Override
    public void close() {
        colourData.close();
        modelData.close();
    }
}
