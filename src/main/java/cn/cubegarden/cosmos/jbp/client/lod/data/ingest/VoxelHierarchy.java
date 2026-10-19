package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import java.util.Arrays;
import java.util.function.LongToIntFunction;

/** Derived from sealed Voxy common/world/other/Mipper.java and WorldUpdater.java. */
public final class VoxelHierarchy {
    private VoxelHierarchy() { }

    /**
     * A section that storage has never written: upstream {@code ActiveSectionTracker.acquire} creates it
     * as {@code Mapper.composeMappingId((byte) 15, 0, 0)} (air at full sky light) before inserting the
     * captured chunk octant. jbp's storage writer must use the same initial payload, otherwise every
     * chunk-octant the capture has not reached is air at sky light 0 and blackens the faces next to it.
     * Hot path: called once per captured chunk and level (not per voxel); callers own the array.
     */
    public static long[] emptySection() {
        long[] section = new long[StoredVoxelSection.VOXELS];
        Arrays.fill(section, IdMapper.airWithLight(SkyLightFallback.FULL_SKY_LIGHT));
        return section;
    }

    /** Upstream opacity-first selection, with x/y/z tie priority and air-light averaging. */
    public static long mip(long[] data, int base, int stride, LongToIntFunction opacity) {
        long chosen = 0;
        int best = -1, block = 0, sky = 0;
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            long word = data[base + x + z * stride + y * stride * stride];
            int light = IdMapper.getLightId(word);
            block += light & 240;
            sky += light & 15;
            if (!IdMapper.isAir(word)) {
                int score = (opacity.applyAsInt(word) << 4) | (x << 2) | (y << 1) | z;
                if (score > best) { best = score; chosen = word; }
            }
        }
        return best >= 0 ? chosen : IdMapper.airWithLight(((block / 8) & 240) | ((sky + 7) / 8));
    }

    /** Upstream WorldUpdater insertion: the compact 16 >> level cube replaces only its own cells. */
    public static void insert(long[] target, long[] source, int level, int sectionX, int sectionY, int sectionZ) {
        int size = 16 >> level, mask = (1 << (level + 1)) - 1;
        int bx = (sectionX & mask) * size, by = (sectionY & mask) * size, bz = (sectionZ & mask) * size;
        for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            target[(bx + x) | (bz + z) << 5 | (by + y) << 10] = source[x + z * size + y * size * size];
        }
    }

    public static long[] downsample(long[] source, int size, LongToIntFunction opacity) {
        int nextSize = size >> 1;
        long[] next = new long[nextSize * nextSize * nextSize];
        for (int y = 0; y < nextSize; y++) for (int z = 0; z < nextSize; z++) for (int x = 0; x < nextSize; x++) {
            next[x + z * nextSize + y * nextSize * nextSize] =
                    mip(source, x * 2 + z * 2 * size + y * 2 * size * size, size, opacity);
        }
        return next;
    }

    public static byte children(long[] data) {
        int mask = 0;
        for (int i = 0; i < data.length; i++) if (!IdMapper.isAir(data[i])) {
            mask |= 1 << (((i >> 4) & 1) | ((i >> 8) & 2) | ((i >> 12) & 4));
        }
        return (byte) mask;
    }
}
