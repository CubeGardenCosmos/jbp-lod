package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static cn.cubegarden.cosmos.jbp.client.lod.vk.render.LodDrawSequence.Stage.*;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers.DrawRegion;

class LodDrawSequenceTest {
    @Test void previousOpaquePrecedesOcclusionAndOnlyNewTemporalFollows() {
        assertEquals(List.of(PREVIOUS_OPAQUE, HIZ_TRAVERSAL_CULL_COMMANDS,
                TEMPORAL_OPAQUE, TRANSLUCENT, COMPOSITE), LodDrawSequence.stages(true));
        assertTrue(LodDrawSequence.drawsPreviousOpaque(true));
        assertEquals(List.of(DrawRegion.TEMPORAL_OPAQUE, DrawRegion.TRANSLUCENT), LodDrawSequence.CURRENT_REGIONS);
        assertFalse(LodDrawSequence.CURRENT_REGIONS.contains(DrawRegion.OPAQUE));
    }

    @Test void firstFrameNeverConsumesUninitializedHistory() {
        assertFalse(LodDrawSequence.drawsPreviousOpaque(false));
        assertEquals(List.of(HIZ_TRAVERSAL_CULL_COMMANDS, TEMPORAL_OPAQUE,
                TRANSLUCENT, COMPOSITE), LodDrawSequence.stages(false));
    }

    @Test void commandRegionsUseIndependentCountsAndCapacities() {
        assertEquals(0, LodFrameBuffers.commandByteOffset(DrawRegion.OPAQUE));
        assertEquals(8_000_000, LodFrameBuffers.commandByteOffset(DrawRegion.TRANSLUCENT));
        assertEquals(10_000_000, LodFrameBuffers.commandByteOffset(DrawRegion.TEMPORAL_OPAQUE));
        assertEquals(12, LodFrameBuffers.drawCountByteOffset(DrawRegion.OPAQUE));
        assertEquals(16, LodFrameBuffers.drawCountByteOffset(DrawRegion.TRANSLUCENT));
        assertEquals(20, LodFrameBuffers.drawCountByteOffset(DrawRegion.TEMPORAL_OPAQUE));
        for (DrawRegion region : DrawRegion.values()) {
            int capacity = LodFrameBuffers.drawCapacity(region);
            assertEquals(capacity, LodDrawPlan.choose(true, true, 1_000_000, capacity).maxDraws());
            assertEquals(LodDrawPlan.Mode.MULTI_DRAW_INDIRECT,
                    LodDrawPlan.choose(false, true, 1_000_000, capacity).mode());
            assertEquals(LodDrawPlan.Mode.SINGLE_DRAW_LOOP,
                    LodDrawPlan.choose(false, false, 1, capacity).mode());
        }
    }
}
