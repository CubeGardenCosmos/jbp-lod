package cn.cubegarden.cosmos.jbp.client.lod.compute;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section visibility word contract shared by {@code cull/raster.vert} (producer), {@code cmdgen.comp}
 * (consumer) and the dev readback census. The classification decides whether a visible section is drawn
 * from the previous-frame opaque range or as a temporal addition; a mismatch here is exactly the
 * frame-to-frame oscillation the readback diagnostic exists to catch.
 */
class LodVisibilityTest {
    @Test
    void aWordStoredInAFrameReportsThatFrameAndTheOneBeforeIt() {
        int frameId = LodComputeLayout.packFrameId(41);
        int stored = LodComputeLayout.encodeVisibility(frameId, true);

        assertEquals(frameId, stored & LodComputeLayout.VISIBILITY_FRAME_MASK);
        assertTrue(LodComputeLayout.visibleThisFrame(stored, frameId));
        // raster.vert of frame N+1 reads this word and compares it against frameId-1 == N.
        assertTrue(LodComputeLayout.wasVisibleLastFrame(stored, LodComputeLayout.packFrameId(42)));
        assertFalse(LodComputeLayout.wasVisibleLastFrame(stored, LodComputeLayout.packFrameId(43)));
        // The flag is the temporal decision cmdgen makes on the next frame.
        assertFalse(LodComputeLayout.rendersTemporally(stored), "drawn now and last frame, so not temporal");
        assertTrue(LodComputeLayout.rendersTemporally(
                LodComputeLayout.encodeVisibility(frameId, false)));
    }

    @Test
    void unpaintedAndStaleWordsAreNeverClassifiedAsVisibleLastFrame() {
        int frameId = LodComputeLayout.packFrameId(500);
        for (int storedWord : new int[]{-1, LodComputeLayout.encodeVisibility(frameId - 5, true),
                LodComputeLayout.encodeVisibility(frameId, true)}) {
            assertFalse(LodComputeLayout.wasVisibleLastFrame(storedWord, frameId),
                    "only the immediately previous frame may set the previous-visible flag");
        }
    }

    /**
     * A skipped LOD frame (no recorded work) advances the game submission epoch but not the recorded-frame
     * counter, so the last stored word is two or more frames old and matches nothing: every section visible
     * now is written by this frame's raster and classified temporally. The previous-opaque range is still
     * drawn, so the result is over-draw for one frame, never a silently missing section.
     */
    @Test
    void skippedFrameReDrawsVisibleSectionsInsteadOfDroppingThem() {
        int staleFrame = LodComputeLayout.packFrameId(10);
        int currentFrame = LodComputeLayout.packFrameId(12); // frame 11 was skipped
        int staleWord = LodComputeLayout.encodeVisibility(staleFrame, true);

        assertFalse(LodComputeLayout.wasVisibleLastFrame(staleWord, currentFrame));
        // This frame's raster then stores the current frame with the flag clear, so cmdgen draws it.
        int rewritten = LodComputeLayout.encodeVisibility(currentFrame,
                LodComputeLayout.wasVisibleLastFrame(staleWord, currentFrame));
        assertTrue(LodComputeLayout.visibleThisFrame(rewritten, currentFrame));
        assertTrue(LodComputeLayout.rendersTemporally(rewritten));
    }

    @Test
    void frameIdWrapKeepsTheLowBitComparisonsConsistent() {
        int last = LodComputeLayout.packFrameId(0x7fffffffL);
        int wrapped = LodComputeLayout.packFrameId(0x80000000L);
        assertEquals(0x7fffffff, last);
        assertEquals(0, wrapped);
        assertTrue(LodComputeLayout.wasVisibleLastFrame(
                LodComputeLayout.encodeVisibility(last, true), wrapped));
    }

    @Test
    void shadersUseTheSameMasksAsTheJavaContract() throws IOException {
        String raster = source("/assets/jbp-lod/shaders/lod/gl46/cull/raster.vert");
        assertTrue(raster.contains("previousVisibilityData[sid]&0x7fffffffu"));
        assertTrue(raster.contains("previous==(frameId-1u)"));
        assertTrue(raster.contains("(frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31)"));

        String cmdgen = source("/assets/jbp-lod/shaders/lod/gl46/cmdgen.comp");
        assertTrue(cmdgen.contains("(dat&0x7fffffffu) == frameId"));
        assertTrue(cmdgen.contains("(dat&0x80000000u)==0"));
    }

    private static String source(String resource) throws IOException {
        try (var input = LodVisibilityTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
