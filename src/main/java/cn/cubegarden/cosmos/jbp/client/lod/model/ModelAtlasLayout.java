package cn.cubegarden.cosmos.jbp.client.lod.model;

/** Byte-for-byte atlas addressing shared with the upstream LOD quad shaders. */
public final class ModelAtlasLayout {
    public static final int FACE_SIZE = 16;
    public static final int FACE_COLUMNS = 3;
    public static final int FACE_ROWS = 2;
    public static final int MODELS_PER_AXIS = 256;
    public static final int TILE_WIDTH = FACE_SIZE * FACE_COLUMNS;
    public static final int TILE_HEIGHT = FACE_SIZE * FACE_ROWS;
    public static final int WIDTH = TILE_WIDTH * MODELS_PER_AXIS;
    public static final int HEIGHT = TILE_HEIGHT * MODELS_PER_AXIS;
    /** Upstream LAYERS = numberOfTrailingZeros(FACE_SIZE) = 4: levels 0..3 are 48x32, 24x16, 12x8, 6x4. */
    public static final int MIP_LEVELS = 4;
    public static final int MAX_MIP_LOD = MIP_LEVELS - 1;
    /** One baked tile with its mip chain: 6144 + 1536 + 384 + 96 bytes of tightly packed RGBA8 texels. */
    public static final int TILE_BYTES_WITH_MIPS = mipChainBytes();
    public static final int MAX_MODEL_ID = 0xffff;

    private ModelAtlasLayout() {
    }

    /** Byte offset of a mip level inside a baked tile; levels are row-major and tightly packed. */
    public static int mipOffsetBytes(int level) {
        checkLevel(level);
        int offset = 0;
        for (int i = 0; i < level; i++) {
            offset += mipBytes(i);
        }
        return offset;
    }

    public static int mipWidth(int level) {
        checkLevel(level);
        return TILE_WIDTH >> level;
    }

    public static int mipHeight(int level) {
        checkLevel(level);
        return TILE_HEIGHT >> level;
    }

    public static int mipBytes(int level) {
        return mipWidth(level) * mipHeight(level) * Integer.BYTES;
    }

    public static int tileX(int modelId) {
        checkModelId(modelId);
        return (modelId & 0xff) * TILE_WIDTH;
    }

    public static int tileY(int modelId) {
        checkModelId(modelId);
        return ((modelId >>> 8) & 0xff) * TILE_HEIGHT;
    }

    /** Face order is DOWN, UP, NORTH, SOUTH, WEST, EAST, matching Direction's data values. */
    public static int faceX(int modelId, int face) {
        checkFace(face);
        return tileX(modelId) + (face >>> 1) * FACE_SIZE;
    }

    public static int faceY(int modelId, int face) {
        checkFace(face);
        return tileY(modelId) + (face & 1) * FACE_SIZE;
    }

    public static int faceIndex(int directionDataValue) {
        if (directionDataValue < 0 || directionDataValue >= FACE_ROWS * FACE_COLUMNS) {
            throw new IllegalArgumentException("direction is not one of the six block faces: " + directionDataValue);
        }
        return directionDataValue;
    }

    public static void checkModelId(int modelId) {
        if (modelId < 0 || modelId > MAX_MODEL_ID) {
            throw new IllegalArgumentException("model ID does not fit the 256x256 atlas: " + modelId);
        }
    }

    private static void checkLevel(int level) {
        if (level < 0 || level >= MIP_LEVELS) {
            throw new IllegalArgumentException("mip level must be in [0, " + (MIP_LEVELS - 1) + "]: " + level);
        }
    }

    private static int mipChainBytes() {
        int total = 0;
        for (int level = 0; level < MIP_LEVELS; level++) {
            total += (TILE_WIDTH >> level) * (TILE_HEIGHT >> level) * Integer.BYTES;
        }
        return total;
    }

    private static void checkFace(int face) {
        if (face < 0 || face >= FACE_ROWS * FACE_COLUMNS) {
            throw new IllegalArgumentException("face index must be in [0, 5]: " + face);
        }
    }
}
