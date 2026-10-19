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

    /** Light dampening of a fully opaque block state (vanilla {@code getLightDampening()}). */
    private static final int FULLY_OPAQUE = 15;
    /**
     * A mip cell only keeps a non-opaque (translucent) winner when at least this many of its eight
     * children are non-air. Upstream {@code Mipper} lets a single non-air child win the whole cell; on
     * grass plains that turns one glass pane, ice block or water source from a village/structure into a
     * cell-sized translucent quad at every coarser LoD level, which then reads as a large fog-tinted
     * rectangle over the terrain. Requiring half of the octet keeps real bodies of water/glass at coarse
     * levels while dropping isolated blocks. This is a deliberate deviation from sealed Voxy 263 (whose
     * own TODO notes the missing opacity/coverage weighting).
     */
    static final int MIN_TRANSLUCENT_CHILDREN = 4;

    /** Upstream opacity-first selection, with x/y/z tie priority and air-light averaging. */
    public static long mip(long[] data, int base, int stride, LongToIntFunction opacity) {
        long chosen = 0;
        int best = -1, block = 0, sky = 0, nonAir = 0;
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            long word = data[base + x + z * stride + y * stride * stride];
            int light = IdMapper.getLightId(word);
            block += light & 240;
            sky += light & 15;
            if (!IdMapper.isAir(word)) {
                nonAir++;
                int score = (opacity.applyAsInt(word) << 4) | (x << 2) | (y << 1) | z;
                if (score > best) { best = score; chosen = word; }
            }
        }
        if (best < 0) {
            return IdMapper.airWithLight(((block / 8) & 240) | ((sky + 7) / 8));
        }
        if ((best >>> 4) < FULLY_OPAQUE && nonAir < MIN_TRANSLUCENT_CHILDREN) {
            //An isolated translucent cell is mostly air: keep the surface material/light of the surrounding
            //terrain instead of promoting one block to a cell-wide translucent face.
            return IdMapper.airWithLight(((block / 8) & 240) | ((sky + 7) / 8));
        }
        return chosen;
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
