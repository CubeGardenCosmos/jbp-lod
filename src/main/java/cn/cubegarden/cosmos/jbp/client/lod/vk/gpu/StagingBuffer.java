package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

/** Host-writable VMA allocation used as the source of asynchronous GPU uploads. */
public final class StagingBuffer implements AutoCloseable {
    private final GpuBuffer buffer;

    public StagingBuffer(GameVulkan.Snapshot game, long capacity) {
        this.buffer = GpuBuffer.allocate(game, capacity, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
    }

    public GpuBuffer buffer() {
        return buffer;
    }

    public void write(byte[] bytes) {
        if (bytes.length > buffer.capacity()) {
            throw new IllegalArgumentException("staging payload exceeds allocated capacity");
        }
        buffer.mappedBytes().put(0, bytes);
        if (bytes.length != 0) {
            buffer.flush(0, bytes.length);
        }
    }

    @Override
    public void close() {
        buffer.close();
    }
}
