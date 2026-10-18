package cn.cubegarden.cosmos.jbp.client.lod.model;

import java.util.Arrays;
import java.util.Objects;

import static cn.cubegarden.cosmos.jbp.client.lod.model.ModelAtlasLayout.FACE_SIZE;
import static cn.cubegarden.cosmos.jbp.client.lod.model.ModelAtlasLayout.MIP_LEVELS;
import static cn.cubegarden.cosmos.jbp.client.lod.model.ModelAtlasLayout.TILE_BYTES_WITH_MIPS;
import static cn.cubegarden.cosmos.jbp.client.lod.model.ModelAtlasLayout.TILE_HEIGHT;
import static cn.cubegarden.cosmos.jbp.client.lod.model.ModelAtlasLayout.TILE_WIDTH;

/** Packs six baked faces into a 48x32 tile and builds its mip chain (upstream MipGen.putTextures). */
public final class MipGen {
    private static final int FACE_PIXELS = FACE_SIZE * FACE_SIZE;

    private MipGen() {
    }

    /**
     * Returns the baked tile with mips: level 0 (48x32) then levels 1..3, each row-major and tightly packed with
     * little-endian RGBA8 texels ({@link ModelAtlasLayout#TILE_BYTES_WITH_MIPS} bytes). Face i occupies
     * {@code ((i >> 1) * 16, (i & 1) * 16)} of level 0. When {@code !darkened}, texels with zero alpha receive the
     * colour (alpha kept at 0) of their nearest opaque texel before mipping.
     */
    public static byte[] putTextures(boolean darkened, ColourDepthTextureData[] sixFaces) {
        Objects.requireNonNull(sixFaces, "sixFaces");
        if (sixFaces.length != 6) {
            throw new IllegalArgumentException("a block model requires exactly six faces");
        }
        int[] level = new int[TILE_WIDTH * TILE_HEIGHT];
        byte solidMsk = 0;
        for (int i = 0; i < 6; i++) {
            ColourDepthTextureData face = Objects.requireNonNull(sixFaces[i], "face");
            if (face.width() != FACE_SIZE || face.height() != FACE_SIZE || face.colour().length != FACE_PIXELS) {
                throw new IllegalArgumentException("face " + i + " is not " + FACE_SIZE + "x" + FACE_SIZE);
            }
            int x = (i >> 1) * FACE_SIZE;
            int y = (i & 1) * FACE_SIZE;
            boolean anyTransparent = false;
            int j = 0;
            for (int t : face.colour()) {
                level[(y + (j / FACE_SIZE)) * TILE_WIDTH + (j & (FACE_SIZE - 1)) + x] = t;
                j++;
                anyTransparent |= (t & 0xFF000000) == 0;
            }
            solidMsk |= (byte) ((anyTransparent ? 1 : 0) << i);
        }

        if (!darkened) {
            solidify(level, solidMsk);
        }

        byte[] out = new byte[TILE_BYTES_WITH_MIPS];
        int outOffset = 0;
        outOffset = store(out, outOffset, level);
        for (int i = 0; i < MIP_LEVELS - 1; i++) {
            int sWidth = TILE_WIDTH >> i;
            int width = TILE_WIDTH >> (i + 1);
            int height = TILE_HEIGHT >> (i + 1);
            int[] next = new int[width * height];
            for (int px = 0; px < width; px++) {
                for (int py = 0; py < height; py++) {
                    int bp = px * 2 + py * 2 * sWidth;
                    int c00 = level[bp];
                    int c01 = level[bp + sWidth];
                    int c10 = level[bp + 1];
                    int c11 = level[bp + sWidth + 1];
                    next[px + py * width] = TextureUtils.mipColours(darkened, c00, c01, c10, c11);
                }
            }
            level = next;
            outOffset = store(out, outOffset, level);
        }
        return out;
    }

    private static int store(byte[] out, int offset, int[] texels) {
        for (int texel : texels) {
            out[offset++] = (byte) texel;
            out[offset++] = (byte) (texel >>> 8);
            out[offset++] = (byte) (texel >>> 16);
            out[offset++] = (byte) (texel >>> 24);
        }
        return offset;
    }

    private static int offset(int bx, int by, int i) {
        bx += i & (FACE_SIZE - 1);
        by += i / FACE_SIZE;
        return bx + by * TILE_WIDTH;
    }

    /** Flood fills the rgb of the nearest non-zero-alpha texel into zero-alpha texels, per masked face. */
    private static void solidify(int[] tile, byte msk) {
        short[] scratch = new short[FACE_PIXELS];
        int[] queue = new int[FACE_PIXELS * 4];
        for (int idx = 0; idx < 6; idx++) {
            if (((msk >> idx) & 1) == 0) {
                continue;
            }
            int bx = (idx >> 1) * FACE_SIZE;
            int by = (idx & 1) * FACE_SIZE;
            Arrays.fill(scratch, (short) -1);
            int head = 0;
            int tail = 0;
            for (int y = 0; y < FACE_SIZE; y++) {
                for (int x = 0; x < FACE_SIZE; x++) {
                    int colour = tile[bx + x + (by + y) * TILE_WIDTH];
                    if ((colour & 0xFF000000) != 0) {
                        int pos = x + y * FACE_SIZE;
                        scratch[pos] = (short) pos;
                        queue[tail++] = pos;
                    }
                }
            }

            while (head != tail) {
                int pos = queue[head++];
                int x = pos & (FACE_SIZE - 1);
                int y = pos / FACE_SIZE;
                short newVal = (short) (scratch[pos] + (short) 0x0100);
                for (int d4 = 3; d4 != -1; d4--) {
                    int d = 2 * (d4 & 1) - 1;
                    int x2 = x + (((d4 & 2) == 2) ? d : 0);
                    int y2 = y + (((d4 & 2) == 0) ? d : 0);
                    if (x2 < 0 || x2 >= FACE_SIZE || y2 < 0 || y2 >= FACE_SIZE) {
                        continue;
                    }
                    int pos2 = x2 + y2 * FACE_SIZE;
                    if ((newVal & 0xFF00) < (scratch[pos2] & 0xFF00)) {
                        scratch[pos2] = newVal;
                        if (tail == queue.length) {
                            queue = Arrays.copyOf(queue, queue.length * 2);
                        }
                        queue[tail++] = pos2;
                    }
                }
            }

            for (int i = 0; i < FACE_PIXELS; i++) {
                int d = Short.toUnsignedInt(scratch[i]);
                if ((d & 0xFF00) != 0) {
                    int c = tile[offset(bx, by, d & 0xFF)] & 0x00FFFFFF;
                    tile[offset(bx, by, i)] = c;
                }
            }
        }
    }
}
