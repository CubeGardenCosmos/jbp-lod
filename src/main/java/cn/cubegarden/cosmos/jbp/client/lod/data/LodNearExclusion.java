package cn.cubegarden.cosmos.jbp.client.lod.data;

/**
 * Source-side near-range exclusion predicate for LOD draw commands (deviation from upstream Voxy, which
 * relies on the bounding raster plus the stencil seed to hide LOD inside the vanilla chunk volume).
 *
 * <p>A section is excluded only when its <em>whole</em> horizontal AABB is nearer than the exclusion
 * radius. Sections straddling that radius still draw, so a section that touches the boundary always
 * contributes its overlap allowance and no seam opens between vanilla terrain and the first LOD section.
 * The shader mirror is {@code isExcludedByNearRange} in {@code cmdgen.comp}.
 *
 * <p><b>The excluded disk must stay inside the vanilla coverage disk.</b> Sodium traverses and draws every
 * chunk section whose nearest point is within {@code renderDistanceChunks * 16} blocks, so vanilla
 * coverage is guaranteed only inside that disk (environmental fog can shrink it further). The loaded chunk
 * grid is a square and the section grid is quantised, but neither guarantees coverage beyond the disk.
 * If the exclusion radius exceeds the vanilla radius, the annulus between them is drawn by neither:
 * vanilla is culled past its radius and LOD is excluded inside the larger radius. Which part of the
 * annulus the boundary-straddling LOD section fills depends on the 32-block section alignment, so the
 * result is a circular, camera-relative and alignment-dependent hole ring at the vanilla edge (the
 * previously observed white/flickering ring). The configured margin therefore <em>shrinks</em> the
 * exclusion radius inward; it is an overlap allowance inside the vanilla disk, never extra reach past it.
 */
public final class LodNearExclusion {
    public static final String MARGIN_PROPERTY = "jbp.lod.nearExclusionMarginBlocks";
    /**
     * Optional inward safety allowance in blocks (default {@code 0}). The exclusion disk must not exceed
     * the vanilla coverage disk; a non-zero value only pulls it further inside, at the cost of near-range
     * overdraw. It is never added to the vanilla radius.
     */
    public static final float DEFAULT_MARGIN_BLOCKS = 0.0f;
    public static final float MAX_MARGIN_BLOCKS = 512.0f;

    private LodNearExclusion() {
    }

    /** Configured inward safety margin in blocks; invalid values fall back to the default and are clamped. */
    public static float marginBlocks() {
        return marginBlocks(System.getProperty(MARGIN_PROPERTY));
    }

    static float marginBlocks(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MARGIN_BLOCKS;
        }
        try {
            float value = Float.parseFloat(configured.trim());
            if (!Float.isFinite(value) || value < 0.0f) {
                return DEFAULT_MARGIN_BLOCKS;
            }
            return Math.min(value, MAX_MARGIN_BLOCKS);
        } catch (NumberFormatException ignored) {
            return DEFAULT_MARGIN_BLOCKS;
        }
    }

    /**
     * Exclusion radius in blocks for clear air: the vanilla coverage radius
     * ({@code renderDistanceChunks * 16}) minus the inward overlap margin. Never exceeds the vanilla
     * radius, so the excluded disk cannot contain a point vanilla does not draw.
     */
    public static float radiusBlocks(int renderDistanceChunks) {
        return radiusBlocks(renderDistanceChunks, Float.NaN);
    }

    /**
     * Exclusion radius in blocks, additionally clamped by an active environmental fog end.
     *
     * <p>Sodium's fog-based culling draws vanilla terrain only up to
     * {@code min(renderDistanceChunks * 16, fogEnd)} (underwater, lava, blindness, custom dimensions).
     * The exclusion disk must not reach past that smaller coverage either. A fog end of {@code <= 0} or
     * {@code NaN} means "no environmental limit" (the LOD fog policy extends clear-air fog out of range,
     * which Sodium then reports as a very large end).
     */
    public static float radiusBlocks(int renderDistanceChunks, float environmentalFogEnd) {
        float vanillaRadius = Math.max(0, renderDistanceChunks) * 16.0f;
        if (Float.isFinite(environmentalFogEnd) && environmentalFogEnd > 0.0f) {
            vanillaRadius = Math.min(vanillaRadius, environmentalFogEnd);
        }
        return Math.max(0.0f, vanillaRadius - marginBlocks());
    }

    /**
     * True when the entire horizontal section AABB is strictly inside the exclusion radius, i.e. the
     * section must emit no draw commands. The test uses the AABB's farthest horizontal point so a section
     * that straddles the radius is still drawn (overlap, no seam). Equality draws.
     */
    public static boolean isEntirelyInside(double cameraX, double cameraZ,
                                           double sectionMinX, double sectionMinZ, double sizeBlocks,
                                           float radiusBlocks) {
        if (!(sizeBlocks > 0.0) || !(radiusBlocks > 0.0f)) {
            return false;
        }
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ)
                || !Double.isFinite(sectionMinX) || !Double.isFinite(sectionMinZ)) {
            return false;
        }
        double farX = Math.max(Math.abs(cameraX - sectionMinX),
                Math.abs(cameraX - (sectionMinX + sizeBlocks)));
        double farZ = Math.max(Math.abs(cameraZ - sectionMinZ),
                Math.abs(cameraZ - (sectionMinZ + sizeBlocks)));
        return farX * farX + farZ * farZ < (double) radiusBlocks * radiusBlocks;
    }
}
