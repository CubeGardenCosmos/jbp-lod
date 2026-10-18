package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Packed section position helpers and update-flag constants.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code common/world/WorldEngine.java}
 * ({@code getWorldSectionId}, {@code getLevel}, {@code getX/Y/Z}, {@code pprintPos}, update-type constants).
 * The bit layout is byte-identical so positions interoperate with the stored RocksDB keys and shader
 * {@code pos_util.glsl}.
 */
public final class SectionPos {
    public static final int MAX_LOD_LAYER = 4;

    public static final int UPDATE_TYPE_BLOCK_BIT = 1;
    public static final int UPDATE_TYPE_CHILD_EXISTENCE_BIT = 2;
    public static final int UPDATE_TYPE_DONT_SAVE = 4;
    public static final int DEFAULT_UPDATE_FLAGS = UPDATE_TYPE_BLOCK_BIT | UPDATE_TYPE_CHILD_EXISTENCE_BIT;

    private SectionPos() {
    }

    public static long pack(int lvl, int x, int y, int z) {
        return ((long) lvl << 60) | ((long) (y & 0xFF) << 52) | ((long) (z & ((1 << 24) - 1)) << 28)
                | ((long) (x & ((1 << 24) - 1)) << 4);
    }

    public static int level(long id) {
        return (int) ((id >> 60) & 0xf);
    }

    public static int x(long id) {
        return (int) ((id << 36) >> 40);
    }

    public static int y(long id) {
        return (int) ((id << 4) >> 56);
    }

    public static int z(long id) {
        return (int) ((id << 12) >> 40);
    }

    public static String pretty(long pos) {
        return level(pos) + "@[" + x(pos) + ", " + y(pos) + ", " + z(pos) + "]";
    }

    /** Child octant index, matching NodeManager.getChildIdx: x | z<<1 | y<<2. */
    public static int childIndex(long pos) {
        return (x(pos) & 1) | ((y(pos) & 1) << 2) | ((z(pos) & 1) << 1);
    }

    public static long childPos(long basePos, int addin) {
        int lvl = level(basePos);
        if (lvl == 0) {
            throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
        }
        return pack(lvl - 1,
                (x(basePos) << 1) | (addin & 1),
                (y(basePos) << 1) | ((addin >> 2) & 1),
                (z(basePos) << 1) | ((addin >> 1) & 1));
    }

    public static long parentPos(long pos) {
        int lvl = level(pos);
        if (lvl == MAX_LOD_LAYER) {
            throw new IllegalArgumentException("Cannot create a parent higher than LoD " + MAX_LOD_LAYER);
        }
        return pack(lvl + 1, x(pos) >> 1, y(pos) >> 1, z(pos) >> 1);
    }
}
