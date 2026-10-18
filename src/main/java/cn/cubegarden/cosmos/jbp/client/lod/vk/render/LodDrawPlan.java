package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

/**
 * Pure choice of how the GPU-generated opaque command span is submitted. The span is always
 * consumed without CPU readback: either the GPU count word bounds it ({@link Mode#INDIRECT_COUNT}),
 * or a fixed number of commands is issued and V3's per-frame zero-clear makes unused entries empty.
 */
public record LodDrawPlan(Mode mode, int maxDraws, boolean truncated) {
    /** Command count of the issue loop used when the device lacks multiDrawIndirect. */
    public static final int SINGLE_DRAW_LOOP_LIMIT = 4096;

    public enum Mode {
        INDIRECT_COUNT,
        MULTI_DRAW_INDIRECT,
        SINGLE_DRAW_LOOP
    }

    /**
     * @param drawIndirectCount   core feature {@code drawIndirectCount} is ENABLED on the game device
     * @param multiDrawIndirect   core feature {@code multiDrawIndirect} is ENABLED on the game device
     * @param maxDrawIndirectCount device limit (unsigned 32-bit as an int)
     * @param capacity            command capacity of the span (400000 for opaque)
     */
    public static LodDrawPlan choose(boolean drawIndirectCount, boolean multiDrawIndirect,
                                     int maxDrawIndirectCount, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (drawIndirectCount) {
            return new LodDrawPlan(Mode.INDIRECT_COUNT, capacity, false);
        }
        if (multiDrawIndirect) {
            long limit = Integer.toUnsignedLong(maxDrawIndirectCount);
            int draws = (int) Math.min(capacity, limit);
            return new LodDrawPlan(Mode.MULTI_DRAW_INDIRECT, draws, draws < capacity);
        }
        int draws = Math.min(capacity, SINGLE_DRAW_LOOP_LIMIT);
        return new LodDrawPlan(Mode.SINGLE_DRAW_LOOP, draws, draws < capacity);
    }

    public String describe() {
        return switch (mode) {
            case INDIRECT_COUNT -> "indirect-count";
            case MULTI_DRAW_INDIRECT -> "multi-draw-indirect(" + maxDraws + (truncated ? ",truncated" : "") + ")";
            case SINGLE_DRAW_LOOP -> "single-draw-loop(" + maxDraws + (truncated ? ",truncated" : "") + ")";
        };
    }
}
