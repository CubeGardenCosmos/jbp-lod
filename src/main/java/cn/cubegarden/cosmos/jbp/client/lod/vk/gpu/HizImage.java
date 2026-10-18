package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;
import java.util.Objects;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One immutable R32_SFLOAT HiZ pyramid allocation: a VMA image, a view over all mips (the consumer
 * sampler view), and one single-level view per mip (blit attachments, compute sampled/storage views).
 *
 * <p>Upstream reference: MCRcortex/voxy 263 {@code HiZBuffer2.alloc} (R32F texture with nearest-mip /
 * clamp-to-edge sampling). Per-level views replace GL's {@code glBindImageTexture(level)} /
 * {@code GL_TEXTURE_BASE_LEVEL} juggling, keeping each subresource in exactly one layout at a time.
 * A generation is closed only after every submission that used it has retired (see
 * {@link HizPyramid#retireReplaced}).
 */
final class HizImage implements AutoCloseable {
    static final int FORMAT = VK_FORMAT_R32_SFLOAT;

    private final VkDevice device;
    private final long allocator;
    private final HizLayout layout;
    private final long image;
    private final long allocation;
    private final long fullView;
    private final long[] levelViews;
    private final HizBlitPipeline.Sets chainSets;
    private boolean closed;

    private HizImage(VkDevice device, long allocator, HizLayout layout, long image, long allocation,
                     long fullView, long[] levelViews, HizBlitPipeline.Sets chainSets) {
        this.device = device;
        this.allocator = allocator;
        this.layout = layout;
        this.image = image;
        this.allocation = allocation;
        this.fullView = fullView;
        this.levelViews = levelViews;
        this.chainSets = chainSets;
    }

    /**
     * Allocates the image/views. When the layout is not a compute chain, also allocates and writes the
     * static per-level sampler descriptors ({@code chainSet(level)} samples level {@code level-1}) used by the
     * blit-only schedule.
     */
    static HizImage create(GameVulkan.Snapshot game, HizLayout layout, long sampler, HizBlitPipeline blit) {
        Objects.requireNonNull(layout, "layout");
        if (game.vmaAllocator() == 0) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VMA allocator handle is null.");
        }
        VkDevice device = game.device();
        long image = 0;
        long allocation = 0;
        long fullView = 0;
        long[] views = new long[layout.levels()];
        HizBlitPipeline.Sets sets = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(FORMAT).mipLevels(layout.levels()).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                            | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(layout.width(), layout.height(), 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            check(vmaCreateImage(game.vmaAllocator(), imageInfo, allocationInfo, pImage, pAllocation,
                    (VmaAllocationInfo) null), "vmaCreateImage");
            image = pImage.get(0);
            allocation = pAllocation.get(0);

            LongBuffer pView = stack.mallocLong(1);
            fullView = createView(device, image, 0, layout.levels(), stack, pView);
            for (int level = 0; level < views.length; level++) {
                views[level] = createView(device, image, level, 1, stack, pView);
            }
            if (!layout.computeChain()) {
                Objects.requireNonNull(blit, "blit pipeline is required for a blit-only layout");
                if (layout.levels() > 1) {
                    sets = blit.allocateSets(layout.levels() - 1);
                    for (int level = 1; level < layout.levels(); level++) {
                        blit.writeSampler(sets.get(level - 1), sampler, views[level - 1],
                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    }
                }
            }
            return new HizImage(device, game.vmaAllocator(), layout, image, allocation, fullView, views, sets);
        } catch (RuntimeException | Error failure) {
            destroy(device, game.vmaAllocator(), image, allocation, fullView, views, sets);
            throw failure;
        }
    }

    private static long createView(VkDevice device, long image, int baseLevel, int levelCount,
                                   MemoryStack stack, LongBuffer handle) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(FORMAT);
        info.components().r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY);
        info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(baseLevel)
                .levelCount(levelCount).baseArrayLayer(0).layerCount(1);
        check(vkCreateImageView(device, info, null, handle), "vkCreateImageView");
        return handle.get(0);
    }

    HizLayout layout() {
        return layout;
    }

    long image() {
        return image;
    }

    /** View over every mip: the consumer-facing {@code sampler2D} view (supports textureQueryLevels/texelFetch). */
    long fullView() {
        return fullView;
    }

    long levelView(int level) {
        return levelViews[level];
    }

    /** Static descriptor sampling level {@code level - 1}, for the blit that produces {@code level}. */
    long blitSet(int level) {
        if (chainSets == null || level < 1 || level > chainSets.count()) {
            throw new IllegalStateException("no blit descriptor for level " + level);
        }
        return chainSets.get(level - 1);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        destroy(device, allocator, image, allocation, fullView, levelViews, chainSets);
    }

    private static void destroy(VkDevice device, long allocator, long image, long allocation, long fullView,
                                long[] views, HizBlitPipeline.Sets sets) {
        if (sets != null) sets.close();
        for (long view : views) {
            if (view != 0) vkDestroyImageView(device, view, null);
        }
        if (fullView != 0) vkDestroyImageView(device, fullView, null);
        if (image != 0 && allocation != 0) vmaDestroyImage(allocator, image, allocation);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
}
