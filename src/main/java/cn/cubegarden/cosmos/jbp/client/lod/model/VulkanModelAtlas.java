package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuCompletion;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuRetireQueue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageFormatProperties;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceImageFormatProperties;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;

/**
 * The LOD model atlas: a permanently {@code GENERAL} RGBA8 image on the game's device, filled by
 * staged tile uploads on the game graphics queue. Render-thread only; nothing here waits on the GPU.
 */
public final class VulkanModelAtlas implements AutoCloseable {
    /** Bytes of one baked tile: every mip level of one 48x32 model, tightly packed, coarser levels after finer. */
    static final int TILE_BYTES = tileBytes();

    private static final int SUPERSEDED_TOKEN_GRACE = 8;

    private final Shared shared;
    private final AtomicBoolean ownerClosed = new AtomicBoolean();

    public VulkanModelAtlas(GameVulkan.Snapshot game) {
        this.shared = new Shared(game);
    }

    /** Queues one baked tile (all mip levels, {@link #TILE_BYTES} bytes); a later tile for the same id replaces it. */
    public void queueTile(int modelId, byte[] tileWithMips) {
        ensureOpen();
        shared.queueTile(modelId, tileWithMips);
    }

    /** Uploads every queued tile; returns the new completion, or the current one when nothing was queued. */
    public GpuCompletion flush() {
        ensureOpen();
        return shared.flush();
    }

    public int pendingTiles() {
        ensureOpen();
        return shared.pending.size();
    }

    /** Non-blocking release of finished upload batches. */
    public void poll() {
        ensureOpen();
        shared.poll();
    }

    /** Borrowed token of the latest submission touching the atlas; never close it. */
    public GpuCompletion readyAfter() {
        ensureOpen();
        return shared.readyAfter;
    }

    AtlasLease retain() {
        ensureOpen();
        shared.retain();
        return new AtlasLease(shared);
    }

    @Override
    public void close() {
        if (ownerClosed.compareAndSet(false, true)) {
            try {
                shared.flush();
            } finally {
                shared.release();
            }
        }
    }

    private void ensureOpen() {
        if (ownerClosed.get()) {
            throw new IllegalStateException("Vulkan model atlas is closed");
        }
    }

    private static int tileBytes() {
        int total = 0;
        for (int level = 0; level < ModelAtlasLayout.MIP_LEVELS; level++) {
            total += levelBytes(level);
        }
        return total;
    }

    private static int levelBytes(int level) {
        return (ModelAtlasLayout.TILE_WIDTH >> level) * (ModelAtlasLayout.TILE_HEIGHT >> level) * 4;
    }

    private static int mipOffsetBytes(int level) {
        int offset = 0;
        for (int i = 0; i < level; i++) {
            offset += levelBytes(i);
        }
        return offset;
    }

    static final class AtlasLease implements AutoCloseable {
        private final Shared shared;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AtlasLease(Shared shared) {
            this.shared = shared;
        }

        long image() { return shared.atlas.image(); }
        long view() { return shared.atlas.view(); }
        int format() { return VK_FORMAT_R8G8B8A8_UNORM; }
        int layout() { return VK_IMAGE_LAYOUT_GENERAL; }
        GpuCompletion readyAfter() { return shared.readyAfter; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                shared.release();
            }
        }
    }

    private record Batch(GpuCompletion completion, VkCommandBuffer commandBuffer, GpuBuffer staging) {
    }

    /** Everything the atlas owns; destroyed once the owner and all leases are gone and the queue has drained it. */
    private static final class Shared implements AutoCloseable {
        private final GameVulkan.Snapshot game;
        private final VkDevice device;
        private final VmaImage atlas;
        private final long commandPool;
        private final LinkedHashMap<Integer, byte[]> pending = new LinkedHashMap<>();
        private final List<Batch> batches = new ArrayList<>();
        /** Every token not yet closed (latest, superseded-but-graced, and tokens of unreleased batches). */
        private final List<GpuCompletion> liveTokens = new ArrayList<>();
        private final ArrayDeque<GpuCompletion> superseded = new ArrayDeque<>();
        private final AtomicInteger references = new AtomicInteger(1);
        private GpuCompletion readyAfter;
        private boolean destroyed;

        private Shared(GameVulkan.Snapshot game) {
            if (game == null) {
                throw new NullPointerException("game Vulkan snapshot");
            }
            this.game = game;
            this.device = game.device();
            ModelVk.requireOptimalFeatures(game.physicalDevice(), VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT, "model atlas");
            checkImageLimits();
            VmaImage image = null;
            long pool = 0;
            try {
                image = VmaImage.create(game, ModelAtlasLayout.WIDTH, ModelAtlasLayout.HEIGHT,
                        ModelAtlasLayout.MIP_LEVELS, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT, "model atlas");
                pool = ModelVk.createCommandPool(game, "model atlas");
                this.atlas = image;
                this.commandPool = pool;
                submitInitialization();
            } catch (RuntimeException | Error failure) {
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                if (image != null) image.close();
                throw failure;
            }
        }

        private void checkImageLimits() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageFormatProperties properties = VkImageFormatProperties.calloc(stack);
                int result = vkGetPhysicalDeviceImageFormatProperties(game.physicalDevice(),
                        VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL,
                        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 0, properties);
                if (result != VK_SUCCESS) {
                    throw new IllegalStateException("[JBP-LOD] model atlas image format is unsupported (VkResult "
                            + result + ")");
                }
                if (properties.maxExtent().width() < ModelAtlasLayout.WIDTH
                        || properties.maxExtent().height() < ModelAtlasLayout.HEIGHT
                        || properties.maxMipLevels() < ModelAtlasLayout.MIP_LEVELS) {
                    throw new IllegalStateException("[JBP-LOD] the game device cannot create a "
                            + ModelAtlasLayout.WIDTH + "x" + ModelAtlasLayout.HEIGHT + " model atlas with "
                            + ModelAtlasLayout.MIP_LEVELS + " mip levels");
                }
            }
        }

        private void submitInitialization() {
            VkCommandBuffer command = ModelVk.allocateCommandBuffer(device, commandPool, "model atlas init");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ModelVk.beginOneTime(command, "model atlas init");
                ModelVk.imageBarrier(command, atlas, 0, ModelAtlasLayout.MIP_LEVELS,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
                VkClearColorValue clear = VkClearColorValue.calloc(stack)
                        .float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(ModelAtlasLayout.MIP_LEVELS).baseArrayLayer(0).layerCount(1);
                vkCmdClearColorImage(command, atlas.image(), VK_IMAGE_LAYOUT_GENERAL, clear, range);
                recordUploadVisible(command);
                ModelVk.check(vkEndCommandBuffer(command), "vkEndCommandBuffer(model atlas init)");
            }
            GpuCompletion completion = GpuCompletion.submit(game, command);
            liveTokens.add(completion);
            batches.add(new Batch(completion, command, null));
            readyAfter = completion;
        }

        private void retain() {
            int current;
            do {
                current = references.get();
                if (current <= 0) throw new IllegalStateException("model atlas has already retired");
            } while (!references.compareAndSet(current, current + 1));
        }

        private void release() {
            int remaining = references.decrementAndGet();
            if (remaining < 0) throw new IllegalStateException("model atlas was released more than once");
            if (remaining != 0) return;
            boolean busy = false;
            for (Batch batch : batches) {
                if (!batch.completion.isComplete()) {
                    busy = true;
                    break;
                }
            }
            if (busy) {
                GpuRetireQueue.retire(GpuCompletion.submitMarker(game), this);
            } else {
                close();
            }
        }

        private void queueTile(int modelId, byte[] tile) {
            ModelAtlasLayout.checkModelId(modelId);
            if (tile == null) throw new NullPointerException("tileWithMips");
            if (tile.length != TILE_BYTES) {
                throw new IllegalArgumentException("model tile must be " + TILE_BYTES + " bytes, got " + tile.length);
            }
            pending.remove(modelId);
            pending.put(modelId, tile.clone());
        }

        private GpuCompletion flush() {
            if (pending.isEmpty()) {
                return readyAfter;
            }
            int levels = ModelAtlasLayout.MIP_LEVELS;
            long size = Math.multiplyExact((long) pending.size(), TILE_BYTES);
            GpuBuffer staging = GpuBuffer.allocate(game, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
            VkCommandBuffer command = null;
            VkBufferImageCopy.Buffer regions = null;
            try {
                ByteBuffer mapped = staging.mappedBytes();
                regions = VkBufferImageCopy.calloc(pending.size() * levels);
                int tileIndex = 0;
                int region = 0;
                for (Map.Entry<Integer, byte[]> entry : pending.entrySet()) {
                    int modelId = entry.getKey();
                    long tileOffset = (long) tileIndex * TILE_BYTES;
                    mapped.put((int) tileOffset, entry.getValue(), 0, TILE_BYTES);
                    for (int level = 0; level < levels; level++) {
                        VkBufferImageCopy copy = regions.get(region++);
                        copy.bufferOffset(tileOffset + mipOffsetBytes(level)).bufferRowLength(0).bufferImageHeight(0);
                        copy.imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1);
                        copy.imageOffset().set(ModelAtlasLayout.tileX(modelId) >> level,
                                ModelAtlasLayout.tileY(modelId) >> level, 0);
                        copy.imageExtent().set(ModelAtlasLayout.TILE_WIDTH >> level,
                                ModelAtlasLayout.TILE_HEIGHT >> level, 1);
                    }
                    tileIndex++;
                }
                staging.flush(0, size);

                command = ModelVk.allocateCommandBuffer(device, commandPool, "model atlas upload");
                ModelVk.beginOneTime(command, "model atlas upload");
                // GENERAL write-after-read/write hazard against earlier sampling and uploads.
                ModelVk.imageBarrier(command, atlas, 0, levels, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                                | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_COPY_BIT,
                        VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
                vkCmdCopyBufferToImage(command, staging.handle(), atlas.image(), VK_IMAGE_LAYOUT_GENERAL, regions);
                recordUploadVisible(command);
                ModelVk.check(vkEndCommandBuffer(command), "vkEndCommandBuffer(model atlas upload)");
                GpuCompletion completion = GpuCompletion.submit(game, command);
                if (readyAfter != null) superseded.addLast(readyAfter);
                liveTokens.add(completion);
                batches.add(new Batch(completion, command, staging));
                readyAfter = completion;
                pending.clear();
                return completion;
            } catch (RuntimeException | Error failure) {
                // Nothing was submitted: the pool owns the command buffer, the staging buffer is ours.
                if (command != null) {
                    vkFreeCommandBuffers(device, commandPool, command);
                }
                staging.close();
                throw failure;
            } finally {
                if (regions != null) regions.free();
            }
        }

        private void recordUploadVisible(VkCommandBuffer command) {
            ModelVk.imageBarrier(command, atlas, 0, ModelAtlasLayout.MIP_LEVELS,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }

        private void poll() {
            Iterator<Batch> iterator = batches.iterator();
            while (iterator.hasNext()) {
                Batch batch = iterator.next();
                if (!batch.completion.isComplete()) continue;
                iterator.remove();
                releaseBatch(batch);
            }
            // Borrowers may still hold a recently superseded token; close only well-aged, finished ones.
            while (superseded.size() > SUPERSEDED_TOKEN_GRACE && superseded.peekFirst().isComplete()) {
                GpuCompletion old = superseded.pollFirst();
                liveTokens.remove(old);
                old.close();
            }
        }

        private void releaseBatch(Batch batch) {
            if (batch.commandBuffer != null) {
                vkFreeCommandBuffers(device, commandPool, batch.commandBuffer);
            }
            if (batch.staging != null) batch.staging.close();
        }

        /** Runs only when no references remain and every submission has completed. */
        @Override
        public void close() {
            if (destroyed) return;
            destroyed = true;
            for (Batch batch : batches) {
                if (batch.staging != null) batch.staging.close();
            }
            batches.clear();
            superseded.clear();
            vkDestroyCommandPool(device, commandPool, null);
            atlas.close();
            for (GpuCompletion token : liveTokens) token.close();
            liveTokens.clear();
        }
    }
}
