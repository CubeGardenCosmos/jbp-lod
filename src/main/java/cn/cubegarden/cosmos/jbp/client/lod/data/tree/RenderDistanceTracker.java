package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import java.util.function.LongConsumer;

/**
 * Converts camera movement into top-level (LoD 4) column insert/remove operations, one section column
 * per 512 blocks over the section Y range.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code client/core/rendering/RenderDistanceTracker.java};
 * hysteresis ({@code CHECK_DISTANCE_BLOCKS}), the +1 ring and process-rate behavior are unchanged.
 */
public final class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private final LongConsumer addTopLevelNode;
    private final LongConsumer removeTopLevelNode;
    private final int processRate;
    private final int minSec;
    private final int maxSec;
    private RingTracker tracker;
    private int renderDistance;
    private double posX;
    private double posZ;

    /**
     * @param rate    maximum column operations per {@link #setCenterAndProcess} call
     * @param minSec  lowest top-level Y section ({@code minSectionY >> 5})
     * @param maxSec  highest top-level Y section ({@code (maxSectionY - 1) >> 5})
     */
    public RenderDistanceTracker(int rate, int minSec, int maxSec, LongConsumer addTopLevelNode,
                                 LongConsumer removeTopLevelNode) {
        this.addTopLevelNode = addTopLevelNode;
        this.removeTopLevelNode = removeTopLevelNode;
        this.renderDistance = 2;
        this.tracker = new RingTracker(this.renderDistance, 0, 0, true);
        this.processRate = rate;
        this.minSec = minSec;
        this.maxSec = maxSec;
    }

    /** Distance in top-level (512-block) sections; callers add 1 to cover the outer ring as upstream does. */
    public void setRenderDistance(int renderDistance) {
        if (renderDistance == this.renderDistance) {
            return;
        }
        this.renderDistance = renderDistance;
        this.tracker.unload();//Mark all as unload
        this.tracker = new RingTracker(this.tracker, renderDistance, ((int) this.posX) >> 9, ((int) this.posZ) >> 9, true);
    }

    public boolean setCenterAndProcess(double x, double z) {
        double dx = this.posX - x;
        double dz = this.posZ - z;
        if (CHECK_DISTANCE_BLOCKS * CHECK_DISTANCE_BLOCKS < dx * dx + dz * dz) {
            this.posX = x;
            this.posZ = z;
            this.tracker.moveCenter(((int) x) >> 9, ((int) z) >> 9);
        }
        return this.tracker.process(this.processRate, this::add, this::rem) != 0;
    }

    private void add(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.addTopLevelNode.accept(SectionPos.pack(4, x, y, z));
        }
    }

    private void rem(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.removeTopLevelNode.accept(SectionPos.pack(4, x, y, z));
        }
    }
}
