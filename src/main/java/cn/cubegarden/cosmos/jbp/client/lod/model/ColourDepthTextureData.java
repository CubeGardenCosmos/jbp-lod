package cn.cubegarden.cosmos.jbp.client.lod.model;

import java.util.Arrays;

/**
 * One baked face: ABGR colour (little-endian RGBA8 bytes) and depth words {@code (depth24 << 8) | stencilByte}.
 * A non-zero stencil byte marks a written pixel and bit 7 of it marks a tinted pixel. Equality is by content.
 */
public record ColourDepthTextureData(int[] colour, int[] depth, int width, int height) {
    @Override
    public boolean equals(Object obj) {
        return obj instanceof ColourDepthTextureData other
                && this.width == other.width && this.height == other.height
                && Arrays.equals(this.colour, other.colour) && Arrays.equals(this.depth, other.depth);
    }

    @Override
    public int hashCode() {
        return width * 312337173 * (Arrays.hashCode(colour) ^ Arrays.hashCode(depth)) ^ height;
    }

    @Override
    public String toString() {
        return "ColourDepthTextureData[" + width + "x" + height + "]";
    }
}
