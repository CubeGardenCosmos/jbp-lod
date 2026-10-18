package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure HiZ pyramid geometry and command schedule; contains no Vulkan calls.
 *
 * <p>Derived from MCRcortex/voxy 263: {@code HiZBuffer2.java} (power-of-two floor extents, at most seven
 * levels, mip 0 produced by the depth blit, mips 1..6 produced by one {@code hiz.comp} dispatch of
 * {@code width/64 x height/64} groups, 6 storage levels) and {@code HiZBuffer.java} (one blit per level, used
 * here when an extent is smaller than the 64-texel compute tile so every mip is still produced). Level
 * counts: upstream {@code min(7, ceil(log2(max(w,h))))}; the compute chain always needs exactly seven
 * (mip 6 is written), which is valid because both extents are at least 64.
 */
public final class HizLayout {
    public static final int MAX_LEVELS = 7;
    public static final int COMPUTE_TILE = 64;
    public static final int COMPUTE_FIRST_LEVEL = 1;
    public static final int COMPUTE_LAST_LEVEL = 6;
    /** The shader consumer packs width into the upper 16 bits of a signed word. */
    public static final int MAX_DIMENSION = 16384;

    private final int width;
    private final int height;
    private final int levels;
    private final boolean computeChain;

    private HizLayout(int width, int height, int levels, boolean computeChain) {
        this.width = width;
        this.height = height;
        this.levels = levels;
        this.computeChain = computeChain;
    }

    /** Layout for a depth source of the given size: extents are rounded down to powers of two like upstream. */
    public static HizLayout forSource(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("HiZ source extent must be positive");
        }
        int width = Integer.highestOneBit(sourceWidth);
        int height = Integer.highestOneBit(sourceHeight);
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("HiZ source extent exceeds " + MAX_DIMENSION);
        }
        boolean compute = Math.min(width, height) >= COMPUTE_TILE;
        int levels;
        if (compute) {
            levels = MAX_LEVELS;
        } else {
            levels = Math.max(1, Math.min(MAX_LEVELS, ceilLog2(Math.max(width, height))));
        }
        return new HizLayout(width, height, levels, compute);
    }

    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int levels() {
        return levels;
    }

    /** True when mips 1..6 come from the single compute dispatch rather than per-level blits. */
    public boolean computeChain() {
        return computeChain;
    }

    public int mipWidth(int level) {
        checkLevel(level);
        return Math.max(1, width >> level);
    }

    public int mipHeight(int level) {
        checkLevel(level);
        return Math.max(1, height >> level);
    }

    /** {@code width << 16 | height} in texels, the form read by the traversal shader's {@code packedHizSize}. */
    public int packedSize() {
        return (width << 16) | height;
    }

    public int dispatchGroupsX() {
        requireCompute();
        return width / COMPUTE_TILE;
    }

    public int dispatchGroupsY() {
        requireCompute();
        return height / COMPUTE_TILE;
    }

    /** Push constant {@code HizParams.invImSize}: reciprocal mip-0 extent. */
    public float invWidth() {
        return 1.0f / width;
    }

    public float invHeight() {
        return 1.0f / height;
    }

    /** True when the image allocation can be reused for another layout. */
    public boolean sameImage(HizLayout other) {
        return other != null && width == other.width && height == other.height
                && levels == other.levels && computeChain == other.computeChain;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof HizLayout other && sameImage(other);
    }

    @Override
    public int hashCode() {
        return (((width * 31) + height) * 31 + levels) * 31 + (computeChain ? 1 : 0);
    }

    @Override
    public String toString() {
        return "HizLayout{" + width + "x" + height + ", levels=" + levels + ", compute=" + computeChain + "}";
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= levels) {
            throw new IndexOutOfBoundsException("HiZ level " + level + " of " + levels);
        }
    }

    private void requireCompute() {
        if (!computeChain) {
            throw new IllegalStateException("layout has no compute chain");
        }
    }

    // ---- schedule ----------------------------------------------------------------------------------

    public enum ImageLayout { UNDEFINED, COLOR_ATTACHMENT, GENERAL, SHADER_READ_ONLY }

    /** Closed set of stage/access scopes; mapped to VK_KHR_synchronization2 masks by the recorder. */
    public enum Scope {
        /** Any earlier use of the image: prior compute/fragment/vertex sampling and prior writes. */
        PRIOR_USE,
        COLOR_WRITE,
        COMPUTE_STORAGE_WRITE,
        COMPUTE_SAMPLED_READ,
        /** Every consumer: compute, fragment and vertex sampled reads. */
        ALL_SAMPLED_READ
    }

    public record ImageBarrier(int baseLevel, int levelCount, ImageLayout oldLayout, ImageLayout newLayout,
                               Scope source, Scope destination) {
    }

    public enum Kind { BARRIER, BLIT, DISPATCH }

    /** One recorded step. {@code level} is the blit target for BLIT; barriers apply to BARRIER only. */
    public record Command(Kind kind, int level, List<ImageBarrier> barriers) {
    }

    /**
     * The exact ordered command list for one build. Every build starts from UNDEFINED layouts (all levels
     * are completely overwritten) with an execution+memory dependency on earlier users, which is how a
     * single image is reused across frames in flight on the one graphics queue without any CPU wait.
     */
    public List<Command> schedule() {
        List<Command> commands = new ArrayList<>();
        if (computeChain) {
            commands.add(barrier(
                    new ImageBarrier(0, 1, ImageLayout.UNDEFINED, ImageLayout.COLOR_ATTACHMENT,
                            Scope.PRIOR_USE, Scope.COLOR_WRITE),
                    new ImageBarrier(COMPUTE_FIRST_LEVEL, COMPUTE_LAST_LEVEL - COMPUTE_FIRST_LEVEL + 1,
                            ImageLayout.UNDEFINED, ImageLayout.GENERAL,
                            Scope.PRIOR_USE, Scope.COMPUTE_STORAGE_WRITE)));
            commands.add(new Command(Kind.BLIT, 0, List.of()));
            commands.add(barrier(
                    new ImageBarrier(0, 1, ImageLayout.COLOR_ATTACHMENT, ImageLayout.SHADER_READ_ONLY,
                            Scope.COLOR_WRITE, Scope.COMPUTE_SAMPLED_READ)));
            commands.add(new Command(Kind.DISPATCH, 0, List.of()));
            commands.add(barrier(
                    new ImageBarrier(COMPUTE_FIRST_LEVEL, COMPUTE_LAST_LEVEL - COMPUTE_FIRST_LEVEL + 1,
                            ImageLayout.GENERAL, ImageLayout.SHADER_READ_ONLY,
                            Scope.COMPUTE_STORAGE_WRITE, Scope.ALL_SAMPLED_READ),
                    new ImageBarrier(0, 1, ImageLayout.SHADER_READ_ONLY, ImageLayout.SHADER_READ_ONLY,
                            Scope.COLOR_WRITE, Scope.ALL_SAMPLED_READ)));
        } else {
            commands.add(barrier(new ImageBarrier(0, levels, ImageLayout.UNDEFINED,
                    ImageLayout.COLOR_ATTACHMENT, Scope.PRIOR_USE, Scope.COLOR_WRITE)));
            for (int level = 0; level < levels; level++) {
                commands.add(new Command(Kind.BLIT, level, List.of()));
                commands.add(barrier(new ImageBarrier(level, 1, ImageLayout.COLOR_ATTACHMENT,
                        ImageLayout.SHADER_READ_ONLY, Scope.COLOR_WRITE, Scope.ALL_SAMPLED_READ)));
            }
        }
        return List.copyOf(commands);
    }

    private static Command barrier(ImageBarrier... barriers) {
        return new Command(Kind.BARRIER, 0, List.of(barriers));
    }
}
