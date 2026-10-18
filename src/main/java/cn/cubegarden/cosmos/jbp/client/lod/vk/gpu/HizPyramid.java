package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.compute.ComputePipeline;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Hierarchical-Z pyramid built on the game's own {@code VkDevice} from a borrowed depth view each frame.
 *
 * <p>Derived from MCRcortex/voxy 263: {@code HiZBuffer2.java} (R32F pyramid of power-of-two floor extents,
 * depth blit into mip 0, one {@code hiz/hiz.comp} dispatch of {@code width/64 x height/64} writing mips 1..6,
 * nearest-mip clamp-to-edge sampler, packed size for the traversal shader) and {@code HiZBuffer.java}
 * (per-level blit chain, used for pyramids smaller than 64 texels in either extent). Shaders:
 * {@code hiz/blit.vert|frag} ({@code OUTPUT_COLOUR}) and {@code hiz/hiz.comp}, optionally {@code USE_SUBGROUP}
 * and {@code USE_REVERSE_Z}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>All work is recorded into the caller's command buffer; nothing waits on the device or reads back.
 *       {@link #record} MUST be outside any open rendering scope (it begins/ends its own dynamic rendering).
 *       The result is ready for sampled reads from compute, fragment and vertex stages after the
 *       recorded final barrier; every mip is in {@link #sampledLayout()} (SHADER_READ_ONLY_OPTIMAL).</li>
 *   <li>The caller owns the source depth image: it MUST already be in {@code sourceLayout}, readable as a
 *       sampled image (depth writes made available to FRAGMENT_SHADER sampled reads) and sampled only on the
 *       depth aspect through {@code sourceDepthView}.</li>
 *   <li>Mip content is always completely rewritten, so each build starts from UNDEFINED with an execution and
 *       memory dependency on any earlier user. A single image therefore serves every frame in flight on the one
 *       graphics queue. Per-frame-slot descriptor sets are rewritten only for the supplied {@code frameSlot},
 *       which the caller must have retired (the same rule that lets {@code LodFrameBuffers} reuse a slot).</li>
 *   <li>A size change replaces the image. The replaced generation is queued; after submitting the command
 *       buffer, hand it to {@link #retireReplaced} with the frame slot's {@code retireAfterUse}. Consumers
 *       rebind descriptors when {@link #generationId()} changes.</li>
 * </ul>
 */
public final class HizPyramid implements AutoCloseable {
    /** Layout every mip is left in; use it for the consumer's combined-image-sampler descriptor. */
    public static final int SAMPLED_LAYOUT = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    private static final String COMPUTE_SHADER = "hiz/hiz.comp";
    private static final int PUSH_BYTES = 2 * Float.BYTES;

    private final GameVulkan.Snapshot game;
    private final int framesInFlight;
    private final long sampler;
    private final HizBlitPipeline blit;
    private final ComputePipeline compute;
    private final HizBlitPipeline.Sets sourceSets;
    private final long[] sourceView;
    private final int[] sourceLayout;
    private final long[] computeGeneration;
    private final List<HizImage> replaced = new ArrayList<>();
    private HizImage current;
    private long generationId;
    private boolean built;
    private boolean closed;

    /**
     * @param useSubgroupReduction {@code DeviceCaps.subgroupHizReductionSupported()}; selects USE_SUBGROUP
     * @param reverseZ             depth convention of the borrowed depth buffer (min reduction when true)
     */
    public HizPyramid(GameVulkan.Snapshot game, ShaderCompiler compiler, int framesInFlight,
                      boolean useSubgroupReduction, boolean reverseZ) {
        this.game = Objects.requireNonNull(game, "game");
        Objects.requireNonNull(compiler, "compiler");
        if (framesInFlight <= 0) {
            throw new IllegalArgumentException("framesInFlight must be positive");
        }
        this.framesInFlight = framesInFlight;
        long createdSampler = 0;
        HizBlitPipeline createdBlit = null;
        ComputePipeline createdCompute = null;
        HizBlitPipeline.Sets createdSets = null;
        try {
            createdSampler = createSampler(game.device());
            createdBlit = new HizBlitPipeline(game, compiler, reverseZ);
            Map<String, String> defines = new HashMap<>();
            if (reverseZ) {
                defines.put("USE_REVERSE_Z", "1");
                defines.put("USE_ZERO_ONE_DEPTH", "1");
            }
            if (useSubgroupReduction) {
                defines.put("USE_SUBGROUP", "1");
            }
            List<ComputePipeline.Binding> bindings = new ArrayList<>();
            bindings.add(new ComputePipeline.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER));
            for (int level = HizLayout.COMPUTE_FIRST_LEVEL; level <= HizLayout.COMPUTE_LAST_LEVEL; level++) {
                bindings.add(new ComputePipeline.Binding(level, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
            }
            createdCompute = new ComputePipeline(game, compiler, COMPUTE_SHADER, Map.copyOf(defines),
                    bindings, framesInFlight, PUSH_BYTES);
            createdSets = createdBlit.allocateSets(framesInFlight);
        } catch (RuntimeException | Error failure) {
            if (createdSets != null) createdSets.close();
            if (createdCompute != null) createdCompute.close();
            if (createdBlit != null) createdBlit.close();
            if (createdSampler != 0) vkDestroySampler(game.device(), createdSampler, null);
            throw failure;
        }
        this.sampler = createdSampler;
        this.blit = createdBlit;
        this.compute = createdCompute;
        this.sourceSets = createdSets;
        this.sourceView = new long[framesInFlight];
        this.sourceLayout = new int[framesInFlight];
        this.computeGeneration = new long[framesInFlight];
    }

    /**
     * Ensures an image exists for the source extent without recording anything (so consumers can bind it
     * early). Returns true if a new generation was allocated. The image content is undefined until the
     * {@link #record} of the same frame executes, which the caller must order before any sampling.
     */
    public boolean prepare(int sourceWidth, int sourceHeight) {
        ensureOpen();
        HizLayout wanted = HizLayout.forSource(sourceWidth, sourceHeight);
        if (current != null && current.layout().sameImage(wanted)) {
            return false;
        }
        HizImage image = HizImage.create(game, wanted, sampler, blit);
        if (current != null) {
            replaced.add(current);
        }
        current = image;
        generationId++;
        built = false;
        return true;
    }

    /**
     * Records the full pyramid build for {@code frameSlot}. Returns false only if the source extent is empty.
     *
     * @param sourceDepthView  sampled view of the depth aspect of the game's depth buffer
     * @param sourceLayout     layout the depth image is in at this point (e.g. DEPTH_STENCIL_READ_ONLY_OPTIMAL)
     */
    public boolean record(VkCommandBuffer commandBuffer, int frameSlot, long sourceDepthView, int sourceLayout,
                          int sourceWidth, int sourceHeight) {
        ensureOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (frameSlot < 0 || frameSlot >= framesInFlight) {
            throw new IndexOutOfBoundsException("HiZ frame slot " + frameSlot);
        }
        if (sourceDepthView == 0) {
            throw new IllegalArgumentException("source depth view is null");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false;
        }
        prepare(sourceWidth, sourceHeight);
        HizImage image = current;
        HizLayout layout = image.layout();

        if (this.sourceView[frameSlot] != sourceDepthView || this.sourceLayout[frameSlot] != sourceLayout) {
            blit.writeSampler(sourceSets.get(frameSlot), sampler, sourceDepthView, sourceLayout);
            this.sourceView[frameSlot] = sourceDepthView;
            this.sourceLayout[frameSlot] = sourceLayout;
        }
        if (layout.computeChain() && computeGeneration[frameSlot] != generationId) {
            bindCompute(frameSlot, image);
            computeGeneration[frameSlot] = generationId;
        }

        for (HizLayout.Command command : layout.schedule()) {
            switch (command.kind()) {
                case BARRIER -> recordBarrier(commandBuffer, image, command.barriers());
                case BLIT -> {
                    int level = command.level();
                    long set = level == 0 ? sourceSets.get(frameSlot) : image.blitSet(level);
                    blit.draw(commandBuffer, set, image.levelView(level),
                            layout.mipWidth(level), layout.mipHeight(level));
                }
                case DISPATCH -> {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        ByteBuffer push = stack.malloc(PUSH_BYTES);
                        push.putFloat(0, layout.invWidth()).putFloat(Float.BYTES, layout.invHeight());
                        compute.dispatch(commandBuffer, frameSlot, layout.dispatchGroupsX(),
                                layout.dispatchGroupsY(), 1, push);
                    }
                }
            }
        }
        built = true;
        return true;
    }

    /**
     * Moves every replaced generation to {@code retireAfterSubmit} (e.g. {@code slot::retireAfterUse}). Call
     * only after the command buffer that last used the previous images was submitted; the sink closes the
     * resource once that submission's fence has signaled.
     */
    public void retireReplaced(Consumer<AutoCloseable> retireAfterSubmit) {
        ensureOpen();
        Objects.requireNonNull(retireAfterSubmit, "retireAfterSubmit");
        for (HizImage image : replaced) {
            retireAfterSubmit.accept(image);
        }
        replaced.clear();
    }

    // ---- consumer API --------------------------------------------------------------------------------

    /** Increments whenever the image/views change; rewrite consumer descriptors when it differs from yours. */
    public long generationId() {
        return generationId;
    }

    public boolean hasImage() {
        return current != null;
    }

    /** True once a build has been recorded for the current generation. */
    public boolean isBuilt() {
        return built;
    }

    /** View over all mips for {@code sampler2D} (texelFetch with LOD, textureQueryLevels). */
    public long sampledView() {
        return requireCurrent().fullView();
    }

    /** Nearest/nearest-mip clamp-to-edge sampler with no depth comparison. */
    public long sampler() {
        ensureOpen();
        return sampler;
    }

    public int sampledLayout() {
        return SAMPLED_LAYOUT;
    }

    public int levels() {
        return requireCurrent().layout().levels();
    }

    public int width() {
        return requireCurrent().layout().width();
    }

    public int height() {
        return requireCurrent().layout().height();
    }

    /** {@code width << 16 | height} in texels for {@code TraversalSceneUniform.packedHizSize}. */
    public int packedSize() {
        return requireCurrent().layout().packedSize();
    }

    /** Raw image handle for external barriers/diagnostics; owned by this pyramid. */
    public long imageHandle() {
        return requireCurrent().image();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (HizImage image : replaced) {
            image.close();
        }
        replaced.clear();
        if (current != null) {
            current.close();
            current = null;
        }
        sourceSets.close();
        compute.close();
        blit.close();
        vkDestroySampler(game.device(), sampler, null);
    }

    // ---- recording helpers ---------------------------------------------------------------------------

    private void bindCompute(int frameSlot, HizImage image) {
        compute.setCombinedImageSampler(frameSlot, 0, sampler, image.levelView(0),
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        int setIndex = compute.descriptorSetIndex(frameSlot, 0);
        for (int level = HizLayout.COMPUTE_FIRST_LEVEL; level <= HizLayout.COMPUTE_LAST_LEVEL; level++) {
            compute.setStorageImage(setIndex, level, image.levelView(level), VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private static void recordBarrier(VkCommandBuffer commandBuffer, HizImage image,
                                      List<HizLayout.ImageBarrier> barriers) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer buffer = VkImageMemoryBarrier2.calloc(barriers.size(), stack);
            for (int i = 0; i < barriers.size(); i++) {
                HizLayout.ImageBarrier barrier = barriers.get(i);
                VkImageMemoryBarrier2 entry = buffer.get(i).sType$Default()
                        .srcStageMask(stageMask(barrier.source()))
                        .srcAccessMask(accessMask(barrier.source()))
                        .dstStageMask(stageMask(barrier.destination()))
                        .dstAccessMask(accessMask(barrier.destination()))
                        .oldLayout(vkLayout(barrier.oldLayout()))
                        .newLayout(vkLayout(barrier.newLayout()))
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                entry.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(barrier.baseLevel()).levelCount(barrier.levelCount())
                        .baseArrayLayer(0).layerCount(1);
            }
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(buffer);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }

    static long stageMask(HizLayout.Scope scope) {
        return switch (scope) {
            case PRIOR_USE -> VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
            case COLOR_WRITE -> VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
            case COMPUTE_STORAGE_WRITE, COMPUTE_SAMPLED_READ -> VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            case ALL_SAMPLED_READ -> VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                    | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;
        };
    }

    static long accessMask(HizLayout.Scope scope) {
        return switch (scope) {
            case PRIOR_USE -> VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
            case COLOR_WRITE -> VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
            case COMPUTE_STORAGE_WRITE -> VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
            case COMPUTE_SAMPLED_READ, ALL_SAMPLED_READ -> VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
        };
    }

    static int vkLayout(HizLayout.ImageLayout layout) {
        return switch (layout) {
            case UNDEFINED -> VK_IMAGE_LAYOUT_UNDEFINED;
            case COLOR_ATTACHMENT -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case GENERAL -> VK_IMAGE_LAYOUT_GENERAL;
            case SHADER_READ_ONLY -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        };
    }

    private static long createSampler(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .mipLodBias(0.0f).anisotropyEnable(false).maxAnisotropy(1.0f)
                    .compareEnable(false).minLod(0.0f).maxLod(VK_LOD_CLAMP_NONE)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK).unnormalizedCoordinates(false);
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreateSampler(device, info, null, handle);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("[JBP-LOD] vkCreateSampler failed with VkResult " + result);
            }
            return handle.get(0);
        }
    }

    private HizImage requireCurrent() {
        ensureOpen();
        if (current == null) {
            throw new IllegalStateException("HiZ pyramid has no image yet; call prepare or record first");
        }
        return current;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("HiZ pyramid is closed");
        }
    }
}
