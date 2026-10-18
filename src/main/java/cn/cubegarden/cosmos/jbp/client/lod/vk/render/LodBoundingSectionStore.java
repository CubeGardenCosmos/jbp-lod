package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import java.util.Arrays;

/**
 * Built Sodium sections visited by the current traversal, as packed {@code SectionPos.asLong} values.
 * Upstream {@code StreamedBoundStore}: filled on the render thread by the Sodium visibility hooks
 * ({@code VisibleChunkCollector.visit}) and consumed by {@link LodBoundingPass} when the LOD frame is
 * prepared. Reset at the start of every Sodium render-list build.
 */
public final class LodBoundingSectionStore {
    private static final LodBoundingSectionStore INSTANCE = new LodBoundingSectionStore();
    private static final int INITIAL_CAPACITY = 1024;

    private long[] sections = new long[INITIAL_CAPACITY];
    private int count;

    private LodBoundingSectionStore() {
    }

    public static LodBoundingSectionStore instance() {
        return INSTANCE;
    }

    public void reset() {
        count = 0;
    }

    public void add(long sectionPos) {
        if (count == sections.length) {
            sections = Arrays.copyOf(sections, sections.length * 2);
        }
        sections[count++] = sectionPos;
    }

    int count() {
        return count;
    }

    long get(int index) {
        return sections[index];
    }
}
