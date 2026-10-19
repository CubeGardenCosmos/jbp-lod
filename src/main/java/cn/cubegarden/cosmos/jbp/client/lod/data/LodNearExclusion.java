package cn.cubegarden.cosmos.jbp.client.lod.data;

/**
 * Source-side near-range exclusion predicate for LOD draw commands (deviation from upstream Voxy, which
 * relies on the bounding raster plus the stencil seed to hide LOD inside the vanilla chunk volume).
 *
 * <p>A section is excluded only when its <em>whole</em> horizontal AABB is nearer than
 * {@code renderDistanceChunks * 16 + margin}. Sections straddling that radius still draw, so the margin is
 * an overlap allowance: it costs a little near-range overdraw but cannot open a seam between vanilla terrain
 * and the first LOD section. The shader mirror is {@code isExcludedByNearRange} in {@code cmdgen.comp}.
 */
public final class LodNearExclusion {
    public static final String MARGIN_PROPERTY = "jbp.lod.nearExclusionMarginBlocks";
    /** Start with one chunk of overlap; tune on the target device. */
    public static final float DEFAULT_MARGIN_BLOCKS = 16.0f;
    public static final float MAX_MARGIN_BLOCKS = 512.0f;

    private LodNearExclusion() {
    }

    /** Configured overlap margin in blocks; invalid values fall back to the default and are clamped. */
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

    /** Exclusion radius in blocks: the live vanilla render distance plus the overlap margin. */
    public static float radiusBlocks(int renderDistanceChunks) {
        return Math.max(0, renderDistanceChunks) * 16.0f + marginBlocks();
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
