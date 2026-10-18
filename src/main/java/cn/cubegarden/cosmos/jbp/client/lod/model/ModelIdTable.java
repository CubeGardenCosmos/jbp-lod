package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.data.BlockStateModelIdResolver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Stable dense block-state-to-atlas-ID mapping with signature-based baked-model deduplication.
 *
 * <p>Threading: a single writer (the render thread) calls {@link #register}, {@link #alias} and
 * {@link #isMapped}; {@link #modelIdForBlockState} may be read from other threads. The backing array reference
 * is volatile and replaced (after copying) when it grows, so a reader always sees a fully initialised array;
 * element reads of a published array are plain int loads and observe either the missing marker or the final ID.
 */
public final class ModelIdTable implements BlockStateModelIdResolver {
    private static final int MAX_BLOCK_STATE_ID = (1 << 20) - 1;

    private volatile int[] modelByBlockState = {AIR_MODEL_ID};
    private final Map<ModelKey, Integer> modelByKey = new HashMap<>();
    private int modelCount = 1;

    /** Air is model ID 0 and is registered under {@code airSignature}. */
    public ModelIdTable(byte[] airSignature) {
        if (airSignature == null) {
            throw new NullPointerException("airSignature");
        }
        modelByKey.put(new ModelKey(airSignature), AIR_MODEL_ID);
    }

    public ModelIdTable() {
        this(ModelSignature.empty());
    }

    /** Returns an existing ID for a repeated source state or equivalent model signature, else a new dense ID. */
    public int register(int blockStateId, byte[] modelSignature) {
        checkBlockState(blockStateId);
        if (modelSignature == null) {
            throw new NullPointerException("modelSignature");
        }
        ensureBlockStateCapacity(blockStateId);
        int[] table = modelByBlockState;
        if (table[blockStateId] != MISSING_MODEL_ID) {
            return table[blockStateId];
        }

        ModelKey key = new ModelKey(modelSignature);
        Integer existing = modelByKey.get(key);
        if (existing != null) {
            table[blockStateId] = existing;
            return existing;
        }
        if (modelCount > ModelAtlasLayout.MAX_MODEL_ID) {
            throw new ModelAtlasFullException();
        }
        int modelId = modelCount++;
        modelByKey.put(key, modelId);
        table[blockStateId] = modelId;
        return modelId;
    }

    /** Maps a block state to an already existing model (for example air when the atlas overflowed). */
    public int alias(int blockStateId, int modelId) {
        checkBlockState(blockStateId);
        if (modelId < 0 || modelId >= modelCount) {
            throw new IllegalArgumentException("model ID is not allocated: " + modelId);
        }
        ensureBlockStateCapacity(blockStateId);
        int[] table = modelByBlockState;
        int current = table[blockStateId];
        if (current != MISSING_MODEL_ID) {
            if (current != modelId) {
                throw new IllegalStateException("block state " + blockStateId + " already maps to model " + current);
            }
            return current;
        }
        table[blockStateId] = modelId;
        return modelId;
    }

    public boolean isMapped(int blockStateId) {
        return modelIdForBlockState(blockStateId) != MISSING_MODEL_ID;
    }

    @Override
    public int modelIdForBlockState(int blockStateId) {
        int[] table = modelByBlockState;
        if (blockStateId < 0 || blockStateId >= table.length) {
            return MISSING_MODEL_ID;
        }
        return table[blockStateId];
    }

    public int modelCount() {
        return modelCount;
    }

    private static void checkBlockState(int blockStateId) {
        if (blockStateId < 0 || blockStateId > MAX_BLOCK_STATE_ID) {
            throw new IllegalArgumentException("block-state ID is outside the upstream mapper domain: " + blockStateId);
        }
    }

    private void ensureBlockStateCapacity(int blockStateId) {
        int[] old = modelByBlockState;
        if (blockStateId < old.length) {
            return;
        }
        int capacity = old.length;
        while (capacity <= blockStateId) {
            capacity = Math.min(MAX_BLOCK_STATE_ID + 1, Math.max(capacity * 2, 16));
        }
        int[] grown = Arrays.copyOf(old, capacity);
        Arrays.fill(grown, old.length, capacity, MISSING_MODEL_ID);
        modelByBlockState = grown;
    }

    private static final class ModelKey {
        private final byte[] signature;
        private final int hash;

        private ModelKey(byte[] signature) {
            this.signature = signature.clone();
            this.hash = Arrays.hashCode(this.signature);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ModelKey key && Arrays.equals(signature, key.signature);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
