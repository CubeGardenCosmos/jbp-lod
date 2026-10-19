package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import cn.cubegarden.cosmos.jbp.client.lod.data.LodSceneUniform;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuCompletion;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only, once-per-run readback of the LOD offscreen colour and the draw-command buffers.
 * Enabled with {@code -Djbp.lod.debugReadback=true}. It idles the device once, so it is not a
 * steady-state path and must never be enabled in normal play.
 */
final class LodReadbackProbe {
    static final String PROPERTY = "jbp.lod.debugReadback";
    static final int WARMUP_FRAMES = 120;
    static final int SAMPLE_INTERVAL_FRAMES = 120;
    static final int MAX_SAMPLES = 4;
    static final int COMMAND_BYTES = 20;
    static final int CONTROL_BYTES = 44;
    static final int HALF_RGBA_PIXEL_BYTES = 8;
    private static final int CONTROL_INTS = CONTROL_BYTES / 4;
    private static final long COMPLETION_TIMEOUT_NANOS = 10_000_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod-render");

    private final boolean enabled;
    private int framesSeen;
    private int samplesTaken;

    LodReadbackProbe() {
        this(Boolean.getBoolean(PROPERTY));
    }

    LodReadbackProbe(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Counts one prepared frame; returns true at the first frame at or after warmup and every
     * {@link #SAMPLE_INTERVAL_FRAMES} after it, at most {@link #MAX_SAMPLES} times when enabled.
     */
    boolean tick() {
        if (!enabled || samplesTaken >= MAX_SAMPLES) {
            return false;
        }
        framesSeen++;
        if (framesSeen < WARMUP_FRAMES || (framesSeen - WARMUP_FRAMES) % SAMPLE_INTERVAL_FRAMES != 0) {
            return false;
        }
        samplesTaken++;
        return true;
    }

    /** Colour statistics of an RGBA16F image: per-channel min/max over finite values. */
    record ColorStats(float[] min, float[] max, long nonzeroPixels, long pixels, long nonFinite) {
        @Override public String toString() {
            return "min=" + Arrays.toString(min) + " max=" + Arrays.toString(max)
                    + " nonzeroPixels=" + nonzeroPixels + "/" + pixels + " nonFinite=" + nonFinite;
        }
    }

    static ColorStats summarizeRgba16f(ByteBuffer bytes, int pixels) {
        ByteBuffer b = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        long nonzero = 0;
        long nonFinite = 0;
        for (int p = 0; p < pixels; p++) {
            int base = p * HALF_RGBA_PIXEL_BYTES;
            boolean any = false;
            for (int c = 0; c < 4; c++) {
                float v = Float.float16ToFloat(b.getShort(base + c * 2));
                if (!Float.isFinite(v)) {
                    nonFinite++;
                    continue;
                }
                min[c] = Math.min(min[c], v);
                max[c] = Math.max(max[c], v);
                if (v != 0.0f) {
                    any = true;
                }
            }
            if (any) {
                nonzero++;
            }
        }
        return new ColorStats(min, max, nonzero, pixels, nonFinite);
    }

    /** Stencil census of the seed: stencil 1 = sky (LOD may draw), stencil 0 = vanilla-masked. */
    record StencilStats(long sky, long vanilla, long other, long pixels) {
        double vanillaShare() {
            return pixels == 0 ? 0.0 : (double) vanilla / pixels;
        }
    }

    static StencilStats summarizeStencil(ByteBuffer bytes, int pixels) {
        ByteBuffer b = bytes.duplicate();
        long sky = 0;
        long vanilla = 0;
        long other = 0;
        for (int p = 0; p < pixels; p++) {
            int s = b.get(p) & 0xFF;
            if (s == 1) {
                sky++;
            } else if (s == 0) {
                vanilla++;
            } else {
                other++;
            }
        }
        return new StencilStats(sky, vanilla, other, pixels);
    }

    /** Counts commands with a non-zero index count and instance count in [firstCommand, firstCommand+count). */
    static int countDrawCommands(ByteBuffer commands, int firstCommand, int count) {
        ByteBuffer b = commands.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int nonzero = 0;
        for (int i = 0; i < count; i++) {
            int base = (firstCommand + i) * COMMAND_BYTES;
            if (b.getInt(base) != 0 && b.getInt(base + 4) != 0) {
                nonzero++;
            }
        }
        return nonzero;
    }

    static int[] describeControl(ByteBuffer control) {
        ByteBuffer b = control.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] values = new int[CONTROL_INTS];
        for (int i = 0; i < CONTROL_INTS; i++) {
            values[i] = b.getInt(i * 4);
        }
        return values;
    }

    void capture(GameVulkan.Snapshot game, LodOffscreenPass offscreen, LodFrameBuffers slot, long frameNumber) {
        int width = offscreen.width();
        int height = offscreen.height();
        long colorBytes = (long) width * height * HALF_RGBA_PIXEL_BYTES;
        long stencilBytes = (long) width * height;
        long commandBytes = slot.drawCommands().capacity();
        GpuBuffer colorReadback = null;
        GpuBuffer commandReadback = null;
        GpuBuffer controlReadback = null;
        GpuBuffer stencilReadback = null;
        try {
            colorReadback = GpuBuffer.allocateReadback(game, colorBytes);
            commandReadback = GpuBuffer.allocateReadback(game, commandBytes);
            controlReadback = GpuBuffer.allocateReadback(game, CONTROL_BYTES);
            stencilReadback = GpuBuffer.allocateReadback(game, stencilBytes);
            VkDevice device = game.device();
            vkDeviceWaitIdle(device);
            copyToHost(game, offscreen.colorImage(slot.slotIndex()), offscreen.depthTarget(slot.slotIndex()).image(),
                    width, height, slot, colorReadback, commandReadback, controlReadback, stencilReadback, commandBytes);

            colorReadback.invalidate(0, colorBytes);
            commandReadback.invalidate(0, commandBytes);
            controlReadback.invalidate(0, CONTROL_BYTES);
            stencilReadback.invalidate(0, stencilBytes);
            slot.sceneUniform().invalidate(0, LodSceneUniform.BYTE_SIZE);
            ColorStats stats = summarizeRgba16f(colorReadback.mappedBytes(), width * height);
            StencilStats seed = summarizeStencil(stencilReadback.mappedBytes(), width * height);
            ByteBuffer commands = commandReadback.mappedBytes();
            int opaque = countRegion(commands, LodFrameBuffers.DrawRegion.OPAQUE);
            int translucent = countRegion(commands, LodFrameBuffers.DrawRegion.TRANSLUCENT);
            int temporal = countRegion(commands, LodFrameBuffers.DrawRegion.TEMPORAL_OPAQUE);
            LOGGER.info("[JBP-LOD] V4 debug readback: frame={} slot={} size={}x{} nonzeroDraws[opaque={} translucent={} temporal={}] control={} colour {}",
                    frameNumber, slot.slotIndex(), width, height, opaque, translucent, temporal,
                    Arrays.toString(describeControl(controlReadback.mappedBytes())), stats);
            LOGGER.info("[JBP-LOD] V4 debug readback seed: stencilSky(1)={}/{} stencilVanilla(0)={} seedMaskedShare={} other={}",
                    seed.sky(), seed.pixels(), seed.vanilla(), String.format(java.util.Locale.ROOT, "%.4f", seed.vanillaShare()),
                    seed.other());
            LOGGER.info("[JBP-LOD] V4 debug readback first draw: opaque={} translucent={} temporal={}; scene {}",
                    describeFirstDraw(commands, LodFrameBuffers.DrawRegion.OPAQUE),
                    describeFirstDraw(commands, LodFrameBuffers.DrawRegion.TRANSLUCENT),
                    describeFirstDraw(commands, LodFrameBuffers.DrawRegion.TEMPORAL_OPAQUE),
                    describeScene(slot.sceneUniform().mappedBytes()));
        } catch (RuntimeException | Error failure) {
            LOGGER.warn("[JBP-LOD] V4 debug readback failed at frame={}", frameNumber, failure);
        } finally {
            closeQuietly(colorReadback);
            closeQuietly(commandReadback);
            closeQuietly(controlReadback);
            closeQuietly(stencilReadback);
        }
    }

    private static int countRegion(ByteBuffer commands, LodFrameBuffers.DrawRegion region) {
        int first = (int) (LodFrameBuffers.commandByteOffset(region) / COMMAND_BYTES);
        return countDrawCommands(commands, first, LodFrameBuffers.drawCapacity(region));
    }

    /** Index (relative to {@code first}) of the first command with a non-zero index and instance count, or -1. */
    static int firstDrawCommand(ByteBuffer commands, int first, int count) {
        ByteBuffer b = commands.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int base = (first + i) * COMMAND_BYTES;
            if (b.getInt(base) != 0 && b.getInt(base + 4) != 0) {
                return i;
            }
        }
        return -1;
    }

    /** First non-zero draw of a region as {indexCount, instanceCount, firstIndex, vertexOffset, firstInstance}. */
    static String describeFirstDraw(ByteBuffer commands, LodFrameBuffers.DrawRegion region) {
        int first = (int) (LodFrameBuffers.commandByteOffset(region) / COMMAND_BYTES);
        int index = firstDrawCommand(commands, first, LodFrameBuffers.drawCapacity(region));
        if (index < 0) {
            return "none";
        }
        ByteBuffer b = commands.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = (first + index) * COMMAND_BYTES;
        return "#" + index + "{indexCount=" + b.getInt(base) + ", instanceCount=" + b.getInt(base + 4)
                + ", firstIndex=" + b.getInt(base + 8) + ", vertexOffset=" + b.getInt(base + 12)
                + ", firstInstance=" + b.getInt(base + 16) + "}";
    }

    /** Scene uniform (SceneUniform ABI): column-major MVP, base section, frame id and camera sub-position. */
    static String describeScene(ByteBuffer scene) {
        ByteBuffer b = scene.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder mvp = new StringBuilder("[");
        for (int i = 0; i < 16; i++) {
            if (i > 0) {
                mvp.append(", ");
            }
            mvp.append(b.getFloat(LodSceneUniform.MVP_OFFSET + i * Float.BYTES));
        }
        mvp.append(']');
        int base = LodSceneUniform.BASE_SECTION_POSITION_OFFSET;
        int cam = LodSceneUniform.CAMERA_SUB_POSITION_OFFSET;
        return "mvp=" + mvp + " baseSection=(" + b.getInt(base) + ", " + b.getInt(base + 4) + ", "
                + b.getInt(base + 8) + ") frameId=" + b.getInt(LodSceneUniform.FRAME_ID_OFFSET)
                + " cameraSub=(" + b.getFloat(cam) + ", " + b.getFloat(cam + 4) + ", " + b.getFloat(cam + 8) + ")";
    }

    private static void copyToHost(GameVulkan.Snapshot game, long colorImage, long depthImage, int width, int height,
                                   LodFrameBuffers slot, GpuBuffer colorReadback, GpuBuffer commandReadback,
                                   GpuBuffer controlReadback, GpuBuffer stencilReadback, long commandBytes) {
        long pool = 0;
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(game.graphicsQueue().queueFamilyIndex());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(game.device(), poolInfo, null, pPool), "vkCreateCommandPool");
            pool = pPool.get(0);
            PointerBuffer pCommand = stack.mallocPointer(1);
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            check(vkAllocateCommandBuffers(game.device(), allocate, pCommand), "vkAllocateCommandBuffers");
            VkCommandBuffer cb = new VkCommandBuffer(pCommand.get(0), game.device());

            check(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            VkMemoryBarrier.Buffer producerBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    producerBarrier, null, null);

            VkBufferImageCopy.Buffer imageRegion = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            imageRegion.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            imageRegion.imageOffset().set(0, 0, 0);
            imageRegion.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(cb, colorImage, VK_IMAGE_LAYOUT_GENERAL, colorReadback.handle(), imageRegion);
            vkCmdCopyBuffer(cb, slot.drawCommands().handle(), commandReadback.handle(),
                    VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(commandBytes));
            vkCmdCopyBuffer(cb, slot.drawControl().handle(), controlReadback.handle(),
                    VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(CONTROL_BYTES));
            VkBufferImageCopy.Buffer stencilRegion = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            stencilRegion.imageSubresource().aspectMask(VK_IMAGE_ASPECT_STENCIL_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            stencilRegion.imageOffset().set(0, 0, 0);
            stencilRegion.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(cb, depthImage, VK_IMAGE_LAYOUT_GENERAL, stencilReadback.handle(), stencilRegion);

            VkMemoryBarrier.Buffer hostBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                    hostBarrier, null, null);
            check(vkEndCommandBuffer(cb), "vkEndCommandBuffer");

            try (GpuCompletion completion = GpuCompletion.submit(game, cb)) {
                long deadline = System.nanoTime() + COMPLETION_TIMEOUT_NANOS;
                while (!completion.isComplete()) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("[JBP-LOD] debug readback copy did not complete");
                    }
                    Thread.onSpinWait();
                }
            }
        } finally {
            if (pool != 0) {
                vkDestroyCommandPool(game.device(), pool, null);
            }
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }

    private static void closeQuietly(GpuBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }
}
