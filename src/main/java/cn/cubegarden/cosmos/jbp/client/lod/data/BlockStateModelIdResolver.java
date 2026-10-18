package cn.cubegarden.cosmos.jbp.client.lod.data;

/** Maps the upstream Mapper block-state ID domain to packed-quad model IDs. */
@FunctionalInterface
public interface BlockStateModelIdResolver {
    int MISSING_MODEL_ID = -1;
    int AIR_MODEL_ID = 0;
    int MAX_PACKED_MODEL_ID = 0xffff;

    /** Input is the dense source block-state ID returned by the world's section mapper. */
    int modelIdForBlockState(int blockStateId);

    static int requirePackedModelId(int modelId) {
        if (modelId < MISSING_MODEL_ID || modelId > MAX_PACKED_MODEL_ID) {
            throw new IllegalArgumentException("model ID does not fit the upstream 16-bit packed quad field: "
                    + modelId);
        }
        return modelId;
    }
}
