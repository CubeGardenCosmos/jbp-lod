package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.data.SectionGeometry;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/** Device-local VMA buffers containing upstream-format SectionMeta records and packed quads. */
public final class LodSectionGpuBuffers implements AutoCloseable {
    private final GpuBuffer geometry;
    private final GpuBuffer sectionMetadata;
    private boolean uploaded;
    private int sectionCount;
    private int quadCount;
    private long geometryBytes;
    private long metadataBytes;

    public LodSectionGpuBuffers(GameVulkan.Snapshot game, long geometryCapacityBytes,
                                long metadataCapacityBytes) {
        this.geometry = GpuBuffer.allocate(game, geometryCapacityBytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                false);
        try {
            this.sectionMetadata = GpuBuffer.allocate(game, metadataCapacityBytes,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    false);
        } catch (RuntimeException | Error failure) {
            geometry.close();
            throw failure;
        }
    }

    public GpuBuffer geometry() {
        return geometry;
    }

    public GpuBuffer sectionMetadata() {
        return sectionMetadata;
    }
    public int sectionCount() { return sectionCount; }
    public int quadCount() { return quadCount; }
    public long geometryBytes() { return geometryBytes; }
    public long metadataBytes() { return metadataBytes; }
    public boolean hasSnapshot() { return uploaded; }

    /** Records a byte-preserving staging upload; retain the returned source buffers to a GPU completion point. */
    public UploadBatch upload(GameVulkan.Snapshot game, VkCommandBuffer commandBuffer,
                              SectionGeometry data) {
        if (uploaded) {
            throw new IllegalStateException("section buffers are immutable after upload; create a new generation");
        }
        byte[] quads = data.quadBytes();
        byte[] metadata = data.metadataBytes();
        if (quads.length > geometry.capacity() || metadata.length > sectionMetadata.capacity()) {
            throw new IllegalArgumentException("section geometry exceeds allocated GPU buffer capacity");
        }
        StagingBuffer quadStaging = null;
        StagingBuffer metadataStaging = null;
        try {
            if (quads.length != 0) {
                quadStaging = new StagingBuffer(game, quads.length);
                quadStaging.write(quads);
                copy(commandBuffer, quadStaging.buffer(), geometry, quads.length);
                Barriers.transferWriteToComputeVertexRead(commandBuffer, geometry, 0, quads.length);
            }
            if (metadata.length != 0) {
                metadataStaging = new StagingBuffer(game, metadata.length);
                metadataStaging.write(metadata);
                copy(commandBuffer, metadataStaging.buffer(), sectionMetadata, metadata.length);
                Barriers.transferWriteToComputeVertexRead(commandBuffer, sectionMetadata, 0, metadata.length);
            }
            sectionCount = data.sectionCount();
            quadCount = data.quadCount();
            geometryBytes = quads.length;
            metadataBytes = metadata.length;
            uploaded = true;
            return new UploadBatch(quadStaging, metadataStaging);
        } catch (RuntimeException | Error failure) {
            if (metadataStaging != null) metadataStaging.close();
            if (quadStaging != null) quadStaging.close();
            throw failure;
        }
    }

    @Override
    public void close() {
        sectionMetadata.close();
        geometry.close();
    }

    private static void copy(VkCommandBuffer commandBuffer, GpuBuffer source,
                             GpuBuffer destination, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0).dstOffset(0).size(size);
            vkCmdCopyBuffer(commandBuffer, source.handle(), destination.handle(), region);
        }
    }

    public static final class UploadBatch implements AutoCloseable {
        private final StagingBuffer quadStaging;
        private final StagingBuffer metadataStaging;
        private boolean closed;

        private UploadBatch(StagingBuffer quadStaging, StagingBuffer metadataStaging) {
            this.quadStaging = quadStaging;
            this.metadataStaging = metadataStaging;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (metadataStaging != null) metadataStaging.close();
            if (quadStaging != null) quadStaging.close();
        }
    }
}
