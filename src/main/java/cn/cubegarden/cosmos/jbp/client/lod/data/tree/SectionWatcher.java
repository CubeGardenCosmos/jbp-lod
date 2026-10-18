package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Per-position update-type subscription registry.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code client/core/rendering/ISectionWatcher.java}.
 */
public interface SectionWatcher {
    /** Adds update types; returns true when at least one new type bit was added. */
    boolean watch(long position, int types);

    /** Removes update types; returns true when the position is no longer watched at all. */
    boolean unwatch(long position, int types);

    int get(long position);
}
