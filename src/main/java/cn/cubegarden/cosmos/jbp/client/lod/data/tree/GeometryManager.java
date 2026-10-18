package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Slot allocator for section geometry used by {@link NodeManager}.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/section/geometry/IGeometryManager.java} ({@code downloadAndRemove} was never
 * implemented upstream for the async manager and is not part of this contract).
 */
public interface GeometryManager {
    int uploadSection(BuiltSection section);

    int uploadReplaceSection(int oldId, BuiltSection section);

    void removeSection(int id);
}
