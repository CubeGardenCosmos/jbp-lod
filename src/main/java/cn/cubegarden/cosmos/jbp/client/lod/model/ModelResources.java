package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuCompletion;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodModelBuffers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Borrowed descriptor resources for one immutable model-table generation. */
public final class ModelResources implements AutoCloseable {
    private final SharedSnapshot shared;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ModelResources(SharedSnapshot shared) {
        this.shared = shared;
    }

    static ModelResources create(LodModelBuffers buffers, VulkanModelAtlas atlas,
                                 long generation, int modelCount) {
        return new ModelResources(new SharedSnapshot(buffers, atlas.retain(), generation, modelCount));
    }

    /** Retains this immutable generation for a separate in-flight frame slot. */
    public ModelResources retain() {
        ensureOpen();
        shared.retain();
        return new ModelResources(shared);
    }

    public GpuBuffer models() {
        ensureOpen();
        return shared.buffers.models();
    }

    public GpuBuffer colours() {
        ensureOpen();
        return shared.buffers.colours();
    }

    public long modelBytes() {
        ensureOpen();
        return shared.buffers.modelBytes();
    }

    /** Descriptor range; V3 guarantees at least four bytes for an empty LUT. */
    public long colourBytes() {
        ensureOpen();
        return shared.buffers.colours().capacity();
    }

    /** Number of populated uint32 entries, excluding the empty-LUT descriptor sentinel. */
    public int colourWordCount() {
        ensureOpen();
        return Math.toIntExact(shared.buffers.colourBytes() / Integer.BYTES);
    }

    public int modelCount() {
        ensureOpen();
        return shared.modelCount;
    }

    public long atlasImage() {
        ensureOpen();
        return shared.atlas.image();
    }

    public long atlasView() {
        ensureOpen();
        return shared.atlas.view();
    }

    public int atlasFormat() {
        ensureOpen();
        return shared.atlas.format();
    }

    public int atlasLayout() {
        ensureOpen();
        return shared.atlas.layout();
    }

    public int width() {
        ensureOpen();
        return ModelAtlasLayout.WIDTH;
    }

    public int height() {
        ensureOpen();
        return ModelAtlasLayout.HEIGHT;
    }

    public int mipLevels() {
        ensureOpen();
        return ModelAtlasLayout.MIP_LEVELS;
    }

    public int maxMipLod() {
        ensureOpen();
        return ModelAtlasLayout.MAX_MIP_LOD;
    }

    public long generation() {
        return shared.generation;
    }

    /** Borrowed pollable token for the latest same-graphics-queue atlas upload. Do not close it. */
    public GpuCompletion readyAfter() {
        ensureOpen();
        return shared.atlas.readyAfter();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            shared.release();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("model resource snapshot has been closed");
        }
    }

    private static final class SharedSnapshot {
        private final LodModelBuffers buffers;
        private final VulkanModelAtlas.AtlasLease atlas;
        private final long generation;
        private final int modelCount;
        private final AtomicInteger references = new AtomicInteger(1);

        private SharedSnapshot(LodModelBuffers buffers, VulkanModelAtlas.AtlasLease atlas,
                               long generation, int modelCount) {
            this.buffers = buffers;
            this.atlas = atlas;
            this.generation = generation;
            this.modelCount = modelCount;
        }

        private void retain() {
            int current;
            do {
                current = references.get();
                if (current <= 0) {
                    throw new IllegalStateException("model resource generation has already retired");
                }
            } while (!references.compareAndSet(current, current + 1));
        }

        private void release() {
            int remaining = references.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("model resource generation was released more than once");
            }
            if (remaining == 0) {
                buffers.close();
                atlas.close();
            }
        }
    }
}
