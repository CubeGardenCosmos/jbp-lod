package cn.cubegarden.cosmos.jbp.client.lod.model;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Dense block-state ID domain shared with section ingest (upstream {@code Mapper}). IDs are the values
 * stored in {@code SectionMeta}/packed quads' source sections and are never renumbered by the model provider.
 */
public interface ModelStateSource {
    /** Resolves a dense source block-state ID; must not return null for IDs below {@link #blockStateCount()}. */
    BlockState blockStateForId(int blockId);

    /** Dense ID for a state, registering it in the source mapping if it is not known yet (upstream {@code getIdForBlockState}). */
    int idForBlockState(BlockState state);

    /** Number of block-state IDs currently registered. */
    int blockStateCount();
}
