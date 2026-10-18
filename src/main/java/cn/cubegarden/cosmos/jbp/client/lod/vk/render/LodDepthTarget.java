package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Renderer-owned depth image that seeds the LOD passes from the game's previous-frame depth, as
 * upstream's setup_stencil_depth does: vanilla pixels hold NEAR depth with stencil 0, sky pixels
 * keep FAR depth with stencil 1 (the LOD draw gate). {@link #SEED_FORMAT} carries the stencil;
 * {@link #DEPTH_ONLY_FORMAT} is the depth-only variant. Feeds the HiZ pyramid and the occlusion-test
 * raster, and always stays in GENERAL layout (sampled, attachment-read and attachment-write use).
 */
final class LodDepthTarget implements AutoCloseable {
    /** Offscreen seed: depth plus the stencil gate that restricts LOD draws to sky pixels. */
    static final int SEED_FORMAT = VK_FORMAT_D32_SFLOAT_S8_UINT;
    /** Depth-only variant used by the bounding raster. */
    static final int DEPTH_ONLY_FORMAT = VK_FORMAT_D32_SFLOAT;

    private final VkDevice device;
    private final long allocator;
    private final long image;
    private final long allocation;
    private final long view;
    private final long attachmentView;
    private final int format;
    private final int width;
    private final int height;
    private boolean initialized;
    private boolean closed;

    private LodDepthTarget(VkDevice device, long allocator, long image, long allocation, long view,
                           long attachmentView, int format, int width, int height) {
        this.device = device;
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.attachmentView = attachmentView;
        this.format = format;
        this.width = width;
        this.height = height;
    }

    /**
     * @param format {@link #SEED_FORMAT} or {@link #DEPTH_ONLY_FORMAT}
     */
    static LodDepthTarget create(GameVulkan.Snapshot game, int width, int height, int format) {
        if (format != SEED_FORMAT && format != DEPTH_ONLY_FORMAT) {
            throw new IllegalArgumentException("unsupported depth target format " + format);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("depth target extent must be positive");
        }
        if (game.vmaAllocator() == 0) {
            throw new IllegalStateException("[JBP-LOD] RenderPearl VMA allocator handle is null.");
        }
        VkDevice device = game.device();
        long image = 0;
        long allocation = 0;
        long view = 0;
        long attachmentView = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
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
            view = LodImageViews.create(device, image, format, VK_IMAGE_ASPECT_DEPTH_BIT, stack, pView);
            attachmentView = VulkanLodPipeline.hasStencil(format)
                    ? LodImageViews.create(device, image, format,
                            VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, stack, pView)
                    : view;
            return new LodDepthTarget(device, game.vmaAllocator(), image, allocation, view, attachmentView,
                    format, width, height);
        } catch (RuntimeException | Error failure) {
            if (attachmentView != 0 && attachmentView != view) vkDestroyImageView(device, attachmentView, null);
            if (view != 0) vkDestroyImageView(device, view, null);
            if (image != 0) vmaDestroyImage(game.vmaAllocator(), image, allocation);
            throw failure;
        }
    }

    long image() {
        return image;
    }

    long view() {
        return view;
    }

    /** Attachment view: DEPTH|STENCIL for {@link #SEED_FORMAT}, otherwise the same handle as {@link #view()}. */
    long attachmentView() {
        return attachmentView;
    }

    /** Aspects the image carries: DEPTH, plus STENCIL for the seed format. */
    int aspectMask() {
        return VulkanLodPipeline.hasStencil(format)
                ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
                : VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Layout before the first recorded use: UNDEFINED once, GENERAL afterwards. */
    int layoutBeforeUse() {
        return initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    }

    void markUsed() {
        initialized = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (attachmentView != view) vkDestroyImageView(device, attachmentView, null);
        vkDestroyImageView(device, view, null);
        vmaDestroyImage(allocator, image, allocation);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("[JBP-LOD] " + operation + " failed with VkResult " + result);
        }
    }
}
