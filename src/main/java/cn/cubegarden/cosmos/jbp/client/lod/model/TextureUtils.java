package cn.cubegarden.cosmos.jbp.client.lod.model;

import net.caffeinemc.mods.sodium.client.util.color.ColorSRGB;

import java.util.Arrays;

/** Analysis of baked face data: coverage, bounds, depth, tint and mip filtering (upstream TextureUtils). */
public final class TextureUtils {
    public static final int WRITE_CHECK_STENCIL = 1;
    public static final int WRITE_CHECK_DEPTH = 2;
    public static final int WRITE_CHECK_ALPHA = 3;

    public static final int DEPTH_MODE_AVG = 1;
    public static final int DEPTH_MODE_MAX = 2;
    public static final int DEPTH_MODE_MIN = 3;

    private TextureUtils() {
    }

    /** Returns the number of pixels that were written to. */
    public static int getWrittenPixelCount(ColourDepthTextureData texture, int checkMode) {
        int count = 0;
        for (int i = 0; i < texture.colour().length; i++) {
            count += wasPixelWritten(texture, checkMode, i) ? 1 : 0;
        }
        return count;
    }

    public static boolean isSolid(ColourDepthTextureData texture) {
        for (int pixel : texture.colour()) {
            if (((pixel >> 24) & 0xFF) != 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean wasPixelWritten(ColourDepthTextureData data, int mode, int index) {
        if (mode == WRITE_CHECK_STENCIL) {
            return (data.depth()[index] & 0xFF) != 0;
        } else if (mode == WRITE_CHECK_DEPTH) {
            return (data.depth()[index] >>> 8) != ((1 << 24) - 1);
        } else if (mode == WRITE_CHECK_ALPHA) {
            //Upstream: the rasterizer yields an alpha of 1 even where it is meant to be 0
            return ((data.colour()[index] >>> 24) & 0xff) > 1;
        }
        throw new IllegalArgumentException("unknown write check mode: " + mode);
    }

    public static boolean hasTranslucentPixel(ColourDepthTextureData data) {
        for (int i = 0; i < data.colour().length; i++) {
            int alpha = data.colour()[i] >>> 24;
            int depth = data.depth()[i];
            if ((depth & 0xFF) != 0) {//only check on written pixels
                if (alpha != 0 && alpha != 255) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isSolidWhereDrawn(ColourDepthTextureData data) {
        for (int i = 0; i < data.colour().length; i++) {
            int alpha = data.colour()[i] >>> 24;
            int depth = data.depth()[i];
            if ((depth & 0xFF) != 0) {//only check on written pixels
                if (alpha != 255) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 0: nothing written, 1: none tinted, 2: some tinted, 3: all tinted. Written pixels that are fully
     * black or have alpha 0 do not take part.
     */
    public static int computeFaceTint(ColourDepthTextureData texture, int checkMode) {
        boolean allTinted = true;
        boolean someTinted = false;
        boolean wasWritten = false;

        final int[] colourData = texture.colour();
        final int[] depthData = texture.depth();
        for (int i = 0; i < colourData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            if ((colourData[i] & 0xFFFFFF) == 0 || (colourData[i] >>> 24) == 0) {//fully black (or translucent)
                continue;
            }
            boolean pixelTinted = (depthData[i] & (1 << 7)) != 0;
            wasWritten = true;
            allTinted &= pixelTinted;
            someTinted |= pixelTinted;
        }
        if (!wasWritten) {
            return 0;
        }
        return someTinted ? (allTinted ? 3 : 2) : 1;
    }

    /** Computes depth info over written pixels; -1 when no pixel was written. Result is in [0, 1]. */
    public static float computeDepth(ColourDepthTextureData texture, int mode, int checkMode) {
        final int[] colourData = texture.colour();
        final int[] depthData = texture.depth();
        long a = 0;
        long b = 0;
        if (mode == DEPTH_MODE_MIN) {
            a = Long.MAX_VALUE;
        }
        if (mode == DEPTH_MODE_MAX) {
            a = Long.MIN_VALUE;
        }
        for (int i = 0; i < colourData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            int depth = depthData[i] >>> 8;
            if (mode == DEPTH_MODE_AVG) {
                a++;
                b += depth;
            } else if (mode == DEPTH_MODE_MAX) {
                a = Math.max(a, depth);
            } else if (mode == DEPTH_MODE_MIN) {
                a = Math.min(a, depth);
            }
        }

        if (mode == DEPTH_MODE_AVG) {
            if (a == 0) {
                return -1;
            }
            return u2fdepth((int) (b / a));
        } else if (mode == DEPTH_MODE_MAX) {
            if (a == Long.MIN_VALUE) {
                return -1;
            }
            return u2fdepth((int) a);
        } else if (mode == DEPTH_MODE_MIN) {
            if (a == Long.MAX_VALUE) {
                return -1;
            }
            return u2fdepth((int) a);
        }
        throw new IllegalArgumentException("unknown depth mode: " + mode);
    }

    private static float u2fdepth(int depth) {
        return (float) ((double) depth / ((1 << 24) - 1));
    }

    public static long[] generateMask(ColourDepthTextureData data, int checkMode) {
        return generateMask(data, checkMode, new long[data.width() * data.height() / 64]);
    }

    public static long[] generateMask(ColourDepthTextureData data, int checkMode, long[] outMsk) {
        Arrays.fill(outMsk, 0);
        int i = 0;
        for (int y = 0; y < data.height(); y++) {
            for (int x = 0; x < data.width(); x++) {
                if (wasPixelWritten(data, checkMode, i)) {
                    outMsk[i / 64] |= 1L << (i & 63);
                }
                i++;
            }
        }
        return outMsk;
    }

    /**
     * Returns inclusive {minX, maxX, minY, maxY} over written pixels (data is x first, then y). Unlike upstream,
     * a face without any written pixel yields {-1, -1, -1, -1} instead of running the scans off the edges.
     */
    public static int[] computeBounds(ColourDepthTextureData data, int checkMode) {
        final int width = data.width();
        final int height = data.height();
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (wasPixelWritten(data, checkMode, x + y * width)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return new int[]{-1, -1, -1, -1};
        }
        return new int[]{minX, maxX, minY, maxY};
    }

    /**
     * Averages four texels in linear space. The {@code darkend} flag keeps upstream's inverted meaning: it is
     * inverted on entry, and when the (inverted) flag is clear texels with zero alpha are excluded and alpha is
     * averaged in linear space; when set, all four texels count and alpha is averaged as a plain integer.
     * The sum is always divided by four.
     */
    public static int mipColours(boolean darkend, int C00, int C01, int C10, int C11) {
        darkend = !darkend;//Invert to make it easier
        float r = 0.0f;
        float g = 0.0f;
        float b = 0.0f;
        float a = 0.0f;
        if (darkend || (C00 >>> 24) != 0) {
            r += ColorSRGB.srgbToLinear((C00 >> 0) & 0xFF);
            g += ColorSRGB.srgbToLinear((C00 >> 8) & 0xFF);
            b += ColorSRGB.srgbToLinear((C00 >> 16) & 0xFF);
            a += darkend ? (C00 >>> 24) : ColorSRGB.srgbToLinear(C00 >>> 24);
        }
        if (darkend || (C01 >>> 24) != 0) {
            r += ColorSRGB.srgbToLinear((C01 >> 0) & 0xFF);
            g += ColorSRGB.srgbToLinear((C01 >> 8) & 0xFF);
            b += ColorSRGB.srgbToLinear((C01 >> 16) & 0xFF);
            a += darkend ? (C01 >>> 24) : ColorSRGB.srgbToLinear(C01 >>> 24);
        }
        if (darkend || (C10 >>> 24) != 0) {
            r += ColorSRGB.srgbToLinear((C10 >> 0) & 0xFF);
            g += ColorSRGB.srgbToLinear((C10 >> 8) & 0xFF);
            b += ColorSRGB.srgbToLinear((C10 >> 16) & 0xFF);
            a += darkend ? (C10 >>> 24) : ColorSRGB.srgbToLinear(C10 >>> 24);
        }
        if (darkend || (C11 >>> 24) != 0) {
            r += ColorSRGB.srgbToLinear((C11 >> 0) & 0xFF);
            g += ColorSRGB.srgbToLinear((C11 >> 8) & 0xFF);
            b += ColorSRGB.srgbToLinear((C11 >> 16) & 0xFF);
            a += darkend ? (C11 >>> 24) : ColorSRGB.srgbToLinear(C11 >>> 24);
        }

        return ColorSRGB.linearToSrgb(
                r / 4,
                g / 4,
                b / 4,
                darkend ? ((int) a) / 4 : LinearToSrgbChannel.apply(a / 4)
        );
    }

    /**
     * Copy of net.minecraft.util.ARGB.linearToSrgbChannel (1024-entry table, floor lookup) so that this class
     * does not load Minecraft's Util in a plain JVM.
     */
    private static final class LinearToSrgbChannel {
        private static final byte[] TABLE = new byte[1024];

        static {
            for (int i = 0; i < TABLE.length; i++) {
                float linear = i / 1023.0f;
                float srgb = linear >= 0.0031308f
                        ? (float) (1.055 * Math.pow(linear, 0.4166666666666667) - 0.055)
                        : 12.92f * linear;
                TABLE[i] = (byte) Math.round(srgb * 255.0f);
            }
        }

        static int apply(float linear) {
            return TABLE[(int) Math.floor(linear * 1023.0f)] & 0xFF;
        }
    }
}
