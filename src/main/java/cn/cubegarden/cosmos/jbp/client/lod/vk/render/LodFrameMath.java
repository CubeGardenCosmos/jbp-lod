package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.joml.Matrix4f;

/** Small pure helpers of the per-frame LOD pre-pass. */
final class LodFrameMath {
    /** Upstream {@code NodeCleaner}: clean when less than this much geometry memory remains. */
    static final long CLEANER_FREE_THRESHOLD_BYTES = 256_000_000L;

    private LodFrameMath() {
    }

    static boolean shouldRunCleaner(long geometryCapacityBytes, long geometryUsedBytes) {
        return geometryCapacityBytes - geometryUsedBytes < CLEANER_FREE_THRESHOLD_BYTES;
    }

    /** Column-major {@code a * b} (JOML layout). */
    static float[] multiply(float[] a, float[] b) {
        return new Matrix4f().set(a).mul(new Matrix4f().set(b)).get(new float[16]);
    }
}
