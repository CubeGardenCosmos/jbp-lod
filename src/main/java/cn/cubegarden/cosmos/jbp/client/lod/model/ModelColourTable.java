package cn.cubegarden.cosmos.jbp.client.lod.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Deterministic uint32 colour LUT for constant and biome-dependent BlockTintSource results.
 *
 * <p>Models needing biome colours get consecutive rows in registration order (O(1) per registration); a
 * model's colourTint is {@code row * biomeCount}. Adding a biome therefore rebases every row, which
 * {@link #snapshot} does by rebuilding all words. Biome colours are resolved lazily through each model's
 * resolver. Biome ids below {@link #biomeCount()} that were never registered resolve to opaque white
 * (0xFFFFFFFF); upstream skips such biomes, which would shift the indices of the later ones.
 */
public final class ModelColourTable {
    public static final int NO_TINT = -1;
    public static final int MISSING_BIOME_COLOUR = 0xFFFFFFFF;

    private static final int MAX_BIOME_ID = 0xffff;

    private final Map<Integer, ModelColourSource> sourcesByModel = new HashMap<>();
    private int[] biomeRowModels = new int[16];
    private int biomeRows;
    private final BitSet registeredBiomes = new BitSet();
    private int biomeCount;
    private Snapshot cachedSnapshot;

    /** Registers the resolved colour source for a model and returns its std430 colourTint word. */
    public int registerModel(int modelId, boolean hasTintSource, boolean biomeDependent,
                             int constantRgb, IntUnaryOperator biomeColourResolver) {
        ModelAtlasLayout.checkModelId(modelId);
        ModelColourSource source;
        if (!hasTintSource) {
            source = ModelColourSource.none();
        } else if (biomeDependent) {
            if (biomeColourResolver == null) {
                throw new NullPointerException("biomeColourResolver");
            }
            source = ModelColourSource.biome(biomeColourResolver);
        } else {
            source = ModelColourSource.constant(opaqueArgb(constantRgb));
        }
        ModelColourSource previous = sourcesByModel.get(modelId);
        if (previous != null) {
            if (!previous.sameSource(source)) {
                throw new IllegalStateException("model colour source is already registered: " + modelId);
            }
            return colourTintFor(previous);
        }
        if (source.biomeResolver != null) {
            if (biomeRows == biomeRowModels.length) {
                biomeRowModels = Arrays.copyOf(biomeRowModels, biomeRows * 2);
            }
            source.row = biomeRows;
            biomeRowModels[biomeRows++] = modelId;
        }
        sourcesByModel.put(modelId, source);
        cachedSnapshot = null;
        return colourTintFor(source);
    }

    /** Registers a biome at its stable dense id; its colours come from each model's resolver at snapshot time. */
    public void registerBiome(int biomeId) {
        if (biomeId < 0 || biomeId > MAX_BIOME_ID) {
            throw new IllegalArgumentException("biome ID is outside the supported dense range: " + biomeId);
        }
        if (registeredBiomes.get(biomeId)) {
            return;
        }
        registeredBiomes.set(biomeId);
        biomeCount = Math.max(biomeCount, biomeId + 1);
        cachedSnapshot = null;
    }

    /** Width of one biome row: the highest registered biome id plus one. */
    public int biomeCount() {
        return biomeCount;
    }

    /** Number of models that own a biome colour row. */
    public int biomeRowCount() {
        return biomeRows;
    }

    public Snapshot snapshot(int modelCapacity) {
        if (modelCapacity <= 0 || modelCapacity > ModelAtlasLayout.MAX_MODEL_ID + 1) {
            throw new IllegalArgumentException("invalid model capacity: " + modelCapacity);
        }
        if (cachedSnapshot != null && cachedSnapshot.modelColourTint.length == modelCapacity) {
            return cachedSnapshot.copy();
        }
        int[] tint = new int[modelCapacity];
        Arrays.fill(tint, NO_TINT);
        for (Map.Entry<Integer, ModelColourSource> entry : sourcesByModel.entrySet()) {
            int modelId = entry.getKey();
            if (modelId >= modelCapacity) {
                throw new IllegalArgumentException("model colour table capacity omits model " + modelId);
            }
            tint[modelId] = colourTintFor(entry.getValue());
        }
        int[] words = new int[Math.multiplyExact(biomeRows, biomeCount)];
        for (int row = 0; row < biomeRows; row++) {
            IntUnaryOperator resolver = sourcesByModel.get(biomeRowModels[row]).biomeResolver;
            int base = row * biomeCount;
            for (int biomeId = 0; biomeId < biomeCount; biomeId++) {
                words[base + biomeId] = registeredBiomes.get(biomeId)
                        ? opaqueArgb(resolver.applyAsInt(biomeId))
                        : MISSING_BIOME_COLOUR;
            }
        }
        cachedSnapshot = new Snapshot(tint, words, biomeCount);
        return cachedSnapshot.copy();
    }

    public static int opaqueArgb(int rgb) {
        return rgb | 0xff000000;
    }

    private int colourTintFor(ModelColourSource source) {
        if (source.biomeResolver == null) {
            return source.constantArgb;
        }
        return Math.multiplyExact(source.row, biomeCount);
    }

    private static final class ModelColourSource {
        private final int constantArgb;
        private final IntUnaryOperator biomeResolver;
        private int row = -1;

        private ModelColourSource(int constantArgb, IntUnaryOperator biomeResolver) {
            this.constantArgb = constantArgb;
            this.biomeResolver = biomeResolver;
        }

        private static ModelColourSource none() {
            return new ModelColourSource(NO_TINT, null);
        }

        private static ModelColourSource constant(int argb) {
            return new ModelColourSource(argb, null);
        }

        private static ModelColourSource biome(IntUnaryOperator resolver) {
            return new ModelColourSource(NO_TINT, resolver);
        }

        private boolean sameSource(ModelColourSource other) {
            return constantArgb == other.constantArgb && biomeResolver == other.biomeResolver;
        }
    }

    public static final class Snapshot {
        private final int[] modelColourTint;
        private final int[] colourWords;
        private final int biomeCount;

        private Snapshot(int[] modelColourTint, int[] colourWords, int biomeCount) {
            this.modelColourTint = modelColourTint;
            this.colourWords = colourWords;
            this.biomeCount = biomeCount;
        }

        public int colourTint(int modelId) {
            return modelColourTint[modelId];
        }

        public int biomeCount() {
            return biomeCount;
        }

        public int[] modelColourTints() {
            return modelColourTint.clone();
        }

        public int[] colourWords() {
            return colourWords.clone();
        }

        public byte[] colourBytes() {
            ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(colourWords.length, Integer.BYTES))
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int word : colourWords) {
                bytes.putInt(word);
            }
            return bytes.array();
        }

        private Snapshot copy() {
            return new Snapshot(modelColourTint.clone(), colourWords.clone(), biomeCount);
        }
    }
}
