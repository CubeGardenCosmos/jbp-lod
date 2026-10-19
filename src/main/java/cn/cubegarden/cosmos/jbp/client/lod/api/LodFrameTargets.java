package cn.cubegarden.cosmos.jbp.client.lod.api;

import java.util.Objects;

/**
 * Borrowed targets, matrices, and fog parameters for one LOD draw. Instances remain valid through
 * the identified CUTOUT pass's close callback, where vanilla depth is captured before an owned
 * composite scope loads these attachments. Handles must not be retained across game frames.
 */
public record LodFrameTargets(
        ImageTarget color,
        ImageTarget depth,
        Matrices matrices,
        Fog fog,
        long renderScopeIdentity
) {
    public LodFrameTargets(ImageTarget color, ImageTarget depth, Matrices matrices, long renderScopeIdentity) {
        this(color, depth, matrices, Fog.disabled(), renderScopeIdentity);
    }

    public LodFrameTargets {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(matrices, "matrices");
        Objects.requireNonNull(fog, "fog");
        if (renderScopeIdentity == 0) {
            throw new IllegalArgumentException("renderScopeIdentity must be nonzero");
        }
    }

    /**
     * Vulkan image metadata supplied by the owning render-target provider. Layouts are the
     * layouts already established for this draw and after it; JBP-LOD leaves borrowed images in
     * those layouts. The provider keeps them alive through the CUTOUT close callback and permits
     * a subsequent owned LOAD/STORE composite scope on the same command buffer. {@code sampled}
     * states that the depth image supports sampling for the vanilla-only snapshot, captured after
     * CUTOUT closes and before LOD composition, rather than sampling the final game depth next frame.
     */
    public record ImageTarget(
            long image,
            long imageView,
            int vkFormat,
            int width,
            int height,
            int sampleCount,
            int aspectMask,
            int currentLayout,
            int layoutAfterDraw,
            boolean sampled
    ) {
        public ImageTarget {
            if (image == 0 || imageView == 0) {
                throw new IllegalArgumentException("image and imageView handles must be nonzero");
            }
        }
    }

    /**
     * Column-major matrices, borrowed for this frame only. {@code projection} is the LOD draw
     * projection (near/far widened to the LOD range); {@code depthProjection} is the projection that
     * produced the depth values stored in the depth target (the game's own chunk projection).
     */
    public static final class Matrices {
        private final float[] projection;
        private final float[] modelView;
        private final float[] depthProjection;

        public Matrices(float[] projection, float[] modelView, float[] depthProjection) {
            this.projection = matrix(projection, "projection");
            this.modelView = matrix(modelView, "modelView");
            this.depthProjection = matrix(depthProjection, "depthProjection");
        }

        /** Returns the frame-owned array; callers must not mutate or retain it. */
        public float[] projection() {
            return projection;
        }

        /** Returns the frame-owned array; callers must not mutate or retain it. */
        public float[] modelView() {
            return modelView;
        }

        /** Returns the frame-owned array; callers must not mutate or retain it. */
        public float[] depthProjection() {
            return depthProjection;
        }

        public boolean isFinite() {
            return finite(projection) && finite(modelView) && finite(depthProjection);
        }

        private static float[] matrix(float[] matrix, String name) {
            Objects.requireNonNull(matrix, name);
            if (matrix.length != 16) {
                throw new IllegalArgumentException(name + " must contain 16 floats");
            }
            return matrix;
        }

        private static boolean finite(float[] matrix) {
            for (float value : matrix) {
                if (!Float.isFinite(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Sodium's environmental fog for this draw; alpha is preserved for vanilla's color value. */
    public record Fog(float start, float end, float red, float green, float blue, float alpha) {
        public Fog {
            if (!Float.isFinite(start) || !Float.isFinite(end) || !Float.isFinite(red)
                    || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha)) {
                throw new IllegalArgumentException("fog values must be finite");
            }
        }

        public static Fog disabled() {
            return new Fog(0, 0, 0, 0, 0, 0);
        }
    }
}
