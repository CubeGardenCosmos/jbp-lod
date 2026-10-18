package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import java.util.List;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers.DrawRegion;

/** Upstream order: previous opaque seeds HiZ, then current temporal fills newly visible sections. */
final class LodDrawSequence {
    enum Stage { PREVIOUS_OPAQUE, HIZ_TRAVERSAL_CULL_COMMANDS, TEMPORAL_OPAQUE, TRANSLUCENT, COMPOSITE }
    private static final List<Stage> FIRST = List.of(Stage.HIZ_TRAVERSAL_CULL_COMMANDS,
            Stage.TEMPORAL_OPAQUE, Stage.TRANSLUCENT, Stage.COMPOSITE);
    private static final List<Stage> LATER = List.of(Stage.PREVIOUS_OPAQUE, Stage.HIZ_TRAVERSAL_CULL_COMMANDS,
            Stage.TEMPORAL_OPAQUE, Stage.TRANSLUCENT, Stage.COMPOSITE);
    static final List<DrawRegion> CURRENT_REGIONS = List.of(DrawRegion.TEMPORAL_OPAQUE, DrawRegion.TRANSLUCENT);
    static List<Stage> stages(boolean historyValid) { return historyValid ? LATER : FIRST; }
    static boolean drawsPreviousOpaque(boolean historyValid) { return stages(historyValid).getFirst() == Stage.PREVIOUS_OPAQUE; }
    private LodDrawSequence() { }
}
