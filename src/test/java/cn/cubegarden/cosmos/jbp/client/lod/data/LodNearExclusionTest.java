package cn.cubegarden.cosmos.jbp.client.lod.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodNearExclusionTest {
    @Test
    void marginDefaultsAndClamps() {
        assertEquals(16.0f, LodNearExclusion.marginBlocks(null), 0.0f);
        assertEquals(16.0f, LodNearExclusion.marginBlocks("  "), 0.0f);
        assertEquals(16.0f, LodNearExclusion.marginBlocks("nope"), 0.0f);
        assertEquals(16.0f, LodNearExclusion.marginBlocks("-1"), 0.0f);
        assertEquals(0.0f, LodNearExclusion.marginBlocks("0"), 0.0f);
        assertEquals(24.5f, LodNearExclusion.marginBlocks("24.5"), 0.0f);
        assertEquals(LodNearExclusion.MAX_MARGIN_BLOCKS,
                LodNearExclusion.marginBlocks("100000"), 0.0f);
    }

    @Test
    void radiusTracksTheLiveVanillaRenderDistanceAndMargin() {
        assertEquals(48.0f, LodNearExclusion.radiusBlocks(2), 0.0f);   // 2*16 + 16
        assertEquals(144.0f, LodNearExclusion.radiusBlocks(8), 0.0f);  // 8*16 + 16
        assertEquals(16.0f, LodNearExclusion.radiusBlocks(0), 0.0f);
        assertEquals(16.0f, LodNearExclusion.radiusBlocks(-3), 0.0f);
    }

    @Test
    void sectionsAreExcludedOnlyWhenTheirWholeAabbIsInside() {
        // Camera at the origin, RD2 => radius 48. A 16-block section at 16..32 is entirely inside.
        assertTrue(LodNearExclusion.isEntirelyInside(0, 0, 16, 0, 16, 48));
        // 32..48 touches the radius: the farthest corner is 48, equality must draw.
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 32, 0, 16, 48));
        // 40..56 straddles the radius: draw so the margin overlaps vanilla, no seam.
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 40, 0, 16, 48));
        // The camera inside a section: the whole section is within the radius.
        assertTrue(LodNearExclusion.isEntirelyInside(8, 8, 0, 0, 16, 48));
        // Diagonal distance: a section at 40,40 has farthest distance sqrt(2)*56 > 48.
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 40, 40, 16, 48));
        // Non-finite and non-positive inputs never exclude.
        assertFalse(LodNearExclusion.isEntirelyInside(Double.NaN, 0, 0, 0, 16, 48));
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 0, 0, 16, 0));
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 0, 0, 0, 48));
    }

    @Test
    void teleportAndRenderDistanceChangesMoveTheExcludedSet() {
        double sectionMinX = 200;
        // Before the teleport the section is far outside the RD2+margin radius.
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, sectionMinX, 0, 16, 48));
        // After a 180-block teleport towards it the same section is inside.
        assertTrue(LodNearExclusion.isEntirelyInside(180, 0, sectionMinX, 0, 16, 48));
        // Raising the render distance to 8 grows the radius to 144; the same section stays inside.
        float rd8 = LodNearExclusion.radiusBlocks(8);
        assertEquals(144.0f, rd8, 0.0f);
        assertTrue(LodNearExclusion.isEntirelyInside(180, 0, sectionMinX, 0, 16, rd8));
        // A section 220 blocks away is outside the enlarged radius again.
        assertFalse(LodNearExclusion.isEntirelyInside(180, 0, 400, 0, 16, rd8));
    }

    @Test
    void sizeScalesWithTheLodLevel() {
        // A level-1 (32-block) section at 0..32: farthest 32 < 48, excluded; a level-2 (64-block)
        // section at 0..64 is not.
        assertTrue(LodNearExclusion.isEntirelyInside(0, 0, 0, 0, 32, 48));
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 0, 0, 64, 48));
    }
}
