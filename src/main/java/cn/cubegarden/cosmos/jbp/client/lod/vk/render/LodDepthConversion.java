package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure mapping between the depth values of two perspective projections that share the same view
 * direction but different near/far planes. For clip depth {@code z = P22*zEye + P32} and
 * {@code w = -zEye}, the [0,1] depth is {@code -P22 + P32 / w} (the GLSL in {@code fog.glsl} and
 * {@code depth_convert.frag} evaluates exactly this). Reverse-Z needs no special case because the
 * projection matrices already encode it.
 */
public record LodDepthConversion(float negP22, float p32, boolean zeroToOne) {
    public static final int BYTE_SIZE = 16;

    /** Reads P22/P32 from a column-major 4x4 perspective matrix (JOML layout). */
    public static LodDepthConversion fromProjection(float[] columnMajor, boolean zeroToOne) {
        if (columnMajor == null || columnMajor.length != 16) {
            throw new IllegalArgumentException("projection must contain 16 column-major floats");
        }
        float m22 = columnMajor[10];
        float m32 = columnMajor[14];
        if (!Float.isFinite(m22) || !Float.isFinite(m32)) {
            throw new IllegalArgumentException("projection depth terms must be finite");
        }
        return new LodDepthConversion(-m22, m32, zeroToOne);
    }

    /**
     * True for reverse-Z projections: P22 is positive (near/far swapped), where a standard
     * projection has P22 = f/(n-f) (or -(f+n)/(f-n)) &lt; 0.
     */
    public boolean reverseZ() {
        return negP22 < 0.0f;
    }

    /** Screen depth in [0,1] at linear view distance {@code w}; saturated like the hardware viewport. */
    public double depthAtDistance(double w) {
        double ndc = negP22 + p32 / w;
        double screen = zeroToOne ? ndc : ndc * 0.5 + 0.5;
        return Math.min(1.0, Math.max(0.0, screen));
    }

    /** Inverse of {@link #depthAtDistance}; positive infinity when the depth is not reachable. */
    public double distanceAtDepth(double screenDepth) {
        double ndc = zeroToOne ? screenDepth : screenDepth * 2.0 - 1.0;
        double denominator = ndc - negP22;
        if (Math.abs(denominator) < 1e-9) {
            return Double.POSITIVE_INFINITY;
        }
        double distance = p32 / denominator;
        return distance > 0.0 ? distance : Double.POSITIVE_INFINITY;
    }

    /** Re-expresses a depth written with this projection in {@code target}'s depth range. */
    public double convertTo(LodDepthConversion target, double screenDepth) {
        double distance = distanceAtDepth(screenDepth);
        if (Double.isInfinite(distance)) {
            double ndc = target.negP22;
            double screen = target.zeroToOne ? ndc : ndc * 0.5 + 0.5;
            return Math.min(1.0, Math.max(0.0, screen));
        }
        return target.depthAtDistance(distance);
    }

    /** Writes {@code (-P22, P32, zeroToOne, 0)} as one std140 vec4. */
    public void writeVec4(ByteBuffer target, int byteOffset) {
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        bytes.putFloat(byteOffset, negP22);
        bytes.putFloat(byteOffset + 4, p32);
        bytes.putFloat(byteOffset + 8, zeroToOne ? 1.0f : 0.0f);
        bytes.putFloat(byteOffset + 12, 0.0f);
    }
}
