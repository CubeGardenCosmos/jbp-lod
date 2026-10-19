package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LodVanillaSeedHistoryTest {
    @Test
    void firstFrameHasNoSeedAndRebuildDiscardsHistory() {
        LodVanillaSeedHistory history = new LodVanillaSeedHistory();
        assertEquals(-1, history.sourceSlot());
        history.captured(2);
        assertEquals(-1, new LodVanillaSeedHistory().sourceSlot());
    }

    @Test
    void followsLatestCaptureNotDestinationSlotOrRingPredecessor() {
        LodVanillaSeedHistory history = new LodVanillaSeedHistory();
        for (int captured : new int[]{2, 0, 2, 1, 1}) {
            history.captured(captured);
            assertEquals(captured, history.sourceSlot());
            // Preparing another frame does not publish a snapshot until its vanilla pass closes.
            assertEquals(captured, history.sourceSlot());
        }
        assertThrows(IllegalArgumentException.class, () -> history.captured(-1));
        assertEquals(1, history.sourceSlot());
    }

    @Test
    void unrelatedFrontendCloseCannotConsumePendingFrameAndReleaseInvalidatesIt() throws Exception {
        String renderer = Files.readString(Path.of("src/main/java/cn/cubegarden/cosmos/jbp/client/lod/vk/render/VulkanLodRenderer.java"));
        String close = renderer.substring(renderer.indexOf("public static void afterPassClosed("), renderer.indexOf("private Frame prepare("));
        assertTrue(close.indexOf("if (INSTANCE.pendingPass != pass) return;") < close.indexOf("INSTANCE.pendingFrame = null;"));
        assertTrue(close.indexOf("INSTANCE.pendingFrame = null;") < close.indexOf("INSTANCE.record(frame)"));
        String release = renderer.substring(renderer.indexOf("private void releaseResources("), renderer.indexOf("private void release(String"));
        assertTrue(release.contains("pendingPass = null;"));
        assertTrue(release.contains("pendingFrame = null;"));
    }

    @Test
    void sourceSequenceCapturesVanillaBeforeCompositeAndNeverSamplesFinalGameDepthInPrepass() throws Exception {
        String renderer = Files.readString(Path.of("src/main/java/cn/cubegarden/cosmos/jbp/client/lod/vk/render/VulkanLodRenderer.java"));
        String prepare = renderer.substring(renderer.indexOf("private Frame prepare("), renderer.indexOf("private void record(Frame"));
        assertTrue(prepare.contains("res.vanillaDepth.view(res.seedHistory.sourceSlot())"));
        assertFalse(prepare.contains("borrowDepthView"));
        String record = renderer.substring(renderer.indexOf("private void record(Frame"), renderer.indexOf("/** Consumes only"));
        assertTrue(record.indexOf("res.vanillaDepth.record(") < record.indexOf("res.offscreen.recordComposite("));
        assertTrue(record.indexOf("res.vanillaDepth.record(") < record.indexOf("res.seedHistory.captured("));
        assertTrue(record.contains("slot.retireAfterUse(gameDepthView)"));
        String convert = Files.readString(Path.of("src/main/java/cn/cubegarden/cosmos/jbp/client/lod/vk/render/LodDepthConvertPass.java"));
        assertTrue(convert.contains("if (gameDepthView != 0)"));
        // Without a vanilla snapshot the seed must not leave the target at "LOD may draw here": an unmasked
        // first frame would inject LOD depth into the borrowed game depth and start the self-masking loop.
        assertTrue(convert.contains("stencil(seedClearStencil(gameDepthView != 0))"));
        String composite = Files.readString(Path.of("src/main/java/cn/cubegarden/cosmos/jbp/client/lod/vk/render/LodOffscreenPass.java"));
        assertTrue(composite.contains("VulkanLodPipeline.State.composite(colorFormat, gameDepthFormat, reverseZ)"));
    }
}
