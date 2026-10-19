package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic regression coverage for the seed self-masking loop.
 *
 * <p>The renderer's vanilla mask is a snapshot of the borrowed game depth attachment. Anything the LOD
 * writes into that attachment is therefore read back as vanilla coverage in a later frame. The composite
 * used to write its reprojected LOD depth there, so a pixel covered by LOD at frame N became stencil 0 at
 * frame N+1 and N+2 and stopped drawing LOD: a one-frame flicker, and once the composite started reading a
 * snapshot one frame further back, a near-total coverage collapse.</p>
 */
class LodDepthSeedInvariantTest {
    @Test
    void compositeTestsDepthButNeverWritesItIntoTheBorrowedGameDepth() {
        for (boolean reverseZ : new boolean[]{false, true}) {
            VulkanLodPipeline.State state = VulkanLodPipeline.State.composite(37, 126, reverseZ);
            assertTrue(state.depthTest(), "vanilla geometry must still occlude the LOD");
            assertFalse(state.depthWrite(), "the composite must not write LOD depth into the borrowed depth");
            assertEquals(reverseZ ? org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL
                            : org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL,
                    state.depthCompareOp());
            assertEquals(VulkanLodPipeline.BlendMode.ALPHA, state.blendMode());
            assertFalse(state.stencil().test(), "the composite runs against the game stencil, not the LOD gate");
        }
    }

    @Test
    void seedClearSuppressesLodUntilAVanillaSnapshotExists() {
        assertEquals(0, LodDepthConvertPass.seedClearStencil(false));
        assertEquals(1, LodDepthConvertPass.seedClearStencil(true));
    }

    /**
     * Models one screen pixel across frames. The pixel's vanilla classification comes from the game depth,
     * which reports non-FAR whenever the composite wrote LOD depth there. Returns whether the pixel drew LOD
     * on every frame.
     */
    @Test
    void lodDepthInTheGameDepthMakesTheLodMaskItselfAndStopDrawing() {
        assertTrue(drawsEveryFrame(true), "with no depth write the pixel keeps drawing every frame");
        assertFalse(drawsEveryFrame(false),
                "writing LOD depth into the game depth suppresses the pixel on a later frame");
    }

    /** @param gameDepthStaysVanillaOnly the invariant {@link VulkanLodPipeline.State#composite} enforces */
    private static boolean drawsEveryFrame(boolean gameDepthStaysVanillaOnly) {
        boolean gameDepthNonFar = false;
        boolean drewLod;
        boolean drewEveryFrame = true;
        for (int frame = 0; frame < 4; frame++) {
            drewLod = !gameDepthNonFar;
            drewEveryFrame &= drewLod;
            if (drewLod && !gameDepthStaysVanillaOnly) {
                gameDepthNonFar = true;
            }
        }
        return drewEveryFrame;
    }
}
