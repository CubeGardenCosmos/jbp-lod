package cn.cubegarden.cosmos.jbp.client.lod.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodNearExclusionTest {
    @Test
    void marginDefaultsAndClamps() {
        assertEquals(0.0f, LodNearExclusion.marginBlocks(null), 0.0f);
        assertEquals(0.0f, LodNearExclusion.marginBlocks("  "), 0.0f);
        assertEquals(0.0f, LodNearExclusion.marginBlocks("nope"), 0.0f);
        assertEquals(0.0f, LodNearExclusion.marginBlocks("-1"), 0.0f);
        assertEquals(0.0f, LodNearExclusion.marginBlocks("0"), 0.0f);
        assertEquals(24.5f, LodNearExclusion.marginBlocks("24.5"), 0.0f);
        assertEquals(LodNearExclusion.MAX_MARGIN_BLOCKS,
                LodNearExclusion.marginBlocks("100000"), 0.0f);
    }

    @Test
    void radiusStaysInsideTheVanillaCoverageDisk() {
        // The margin is an INWARD overlap allowance: Sodium guarantees vanilla coverage only inside the
        // disk of radius renderDistanceChunks * 16, so the excluded disk must never extend past it.
        assertEquals(32.0f, LodNearExclusion.radiusBlocks(2), 0.0f);   // 2*16
        assertEquals(128.0f, LodNearExclusion.radiusBlocks(8), 0.0f);  // 8*16
        assertEquals(16.0f, LodNearExclusion.radiusBlocks(1), 0.0f);   // 1*16
        assertEquals(0.0f, LodNearExclusion.radiusBlocks(0), 0.0f);
        assertEquals(0.0f, LodNearExclusion.radiusBlocks(-3), 0.0f);
    }

    @Test
    void exclusionNeverReachesPastTheGuaranteedVanillaDisk() {
        for (int chunks = 0; chunks <= 64; chunks++) {
            float vanilla = chunks * 16.0f;
            float radius = LodNearExclusion.radiusBlocks(chunks);
            assertTrue(radius <= vanilla,
                    "exclusion radius " + radius + " must not exceed vanilla coverage " + vanilla);
            assertTrue(radius + LodNearExclusion.marginBlocks() <= vanilla
                            || radius == 0.0f,
                    "inward margin must be preserved for chunks=" + chunks);
        }
    }

    @Test
    void environmentalFogShrinksTheExclusionDiskLikeSodiumCulling() {
        // Sodium's fog-based culling uses min(vanilla radius, environmental fog end); the exclusion must
        // follow so a fog-shrunk vanilla region cannot be surrounded by an excluded annulus.
        assertEquals(64.0f, LodNearExclusion.radiusBlocks(8, 64.0f), 0.0f);   // min(128,64)
        assertEquals(128.0f, LodNearExclusion.radiusBlocks(8, 1000.0f), 0.0f);
        assertEquals(8.0f, LodNearExclusion.radiusBlocks(8, 8.0f), 0.0f);     // min(128,8)
        assertEquals(128.0f, LodNearExclusion.radiusBlocks(8, -1.0f), 0.0f);  // no environmental limit
        assertEquals(128.0f, LodNearExclusion.radiusBlocks(8, 0.0f), 0.0f);
        assertEquals(128.0f, LodNearExclusion.radiusBlocks(8, Float.NaN), 0.0f);
    }

    @Test
    void sectionsAreExcludedOnlyWhenTheirWholeAabbIsInside() {
        // Camera at the origin, radius 48 (explicit predicate test). A 16-block section at 16..32 is inside.
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
        // Before the teleport the section is far outside a radius-48 disk.
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, sectionMinX, 0, 16, 48));
        // After a 180-block teleport towards it the same section is inside.
        assertTrue(LodNearExclusion.isEntirelyInside(180, 0, sectionMinX, 0, 16, 48));
        // Raising the render distance to 8 grows the exclusion radius to 112 blocks.
        float rd8 = LodNearExclusion.radiusBlocks(8);
        assertEquals(128.0f, rd8, 0.0f);
        assertTrue(LodNearExclusion.isEntirelyInside(180, 0, sectionMinX, 0, 16, rd8));
        // A section 220 blocks away from the camera is outside the enlarged radius again.
        assertFalse(LodNearExclusion.isEntirelyInside(180, 0, 400, 0, 16, rd8));
    }

    @Test
    void sizeScalesWithTheLodLevel() {
        // A level-1 (32-block) section at 0..32: farthest 32 < 48, excluded; a level-2 (64-block)
        // section at 0..64 is not.
        assertTrue(LodNearExclusion.isEntirelyInside(0, 0, 0, 0, 32, 48));
        assertFalse(LodNearExclusion.isEntirelyInside(0, 0, 0, 0, 64, 48));
    }

    @Test
    void outwardMarginOpensAnAlignmentDependentRingThatTheInwardMarginCannot() {
        // 1D model along the camera's section row: vanilla covers the disk of radius R = 2*16 = 32;
        // every 32-block section whose farthest point is >= the exclusion radius is drawn (with data).
        // The ring is the set of points beyond R that no drawn section covers.
        float vanillaRadius = 32.0f;
        float oldRadius = vanillaRadius + 16.0f; // pre-fix semantics: the default margin was added outward
        float fixedRadius = LodNearExclusion.radiusBlocks(2);

        double oldWorstGap = 0.0;
        for (double offset = 0.0; offset < 32.0; offset += 0.5) {
            oldWorstGap = Math.max(oldWorstGap, largestGapBlocks(offset, vanillaRadius, oldRadius));
            assertEquals(0.0, largestGapBlocks(offset, vanillaRadius, fixedRadius), 1e-9,
                    "fixed exclusion leaves no uncovered ring at offset " + offset);
        }
        assertTrue(oldWorstGap > 8.0,
                "the pre-fix outward margin must reproduce the live ring; worst gap was " + oldWorstGap);
    }

    /** Largest run of blocks in {@code [vanillaRadius, vanillaRadius + 96)} covered by neither vanilla nor a drawn section. */
    private static double largestGapBlocks(double cameraOffset, float vanillaRadius, float exclusionRadius) {
        double from = vanillaRadius;
        double to = vanillaRadius + 96.0;
        boolean[] covered = new boolean[(int) Math.ceil(to - from) + 1];
        for (int step = 0; step < covered.length; step++) {
            double point = from + step;
            // Vanilla: the guaranteed traversal disk.
            if (point * point < (double) vanillaRadius * vanillaRadius + 1e-9) {
                covered[step] = true;
                continue;
            }
            // A world-aligned 32-block LOD section containing the point is drawn unless its farthest
            // point is strictly inside the exclusion radius.
            double sectionStart = Math.floor(point / 32.0) * 32.0;
            double camera = cameraOffset;
            double far = Math.max(Math.abs(camera - sectionStart), Math.abs(camera - (sectionStart + 32.0)));
            if (far >= exclusionRadius) {
                covered[step] = true;
            }
        }
        double largest = 0.0;
        int run = 0;
        for (boolean value : covered) {
            if (value) {
                largest = Math.max(largest, run);
                run = 0;
            } else {
                run++;
            }
        }
        return Math.max(largest, run);
    }

    @Test
    void noSectionStraddlingTheDiskIsExcluded() {
        // The no-hole argument: any section containing a point at the exclusion radius has a farthest
        // corner >= the radius, so it draws. Check every 1-block offset and both section sizes.
        float radius = 48;
        for (int offset = -64; offset <= 64; offset++) {
            for (int size : new int[]{16, 32, 64}) {
                int min = Math.floorDiv(offset, size) * size;
                boolean containsRadius = min <= radius && min + size >= radius;
                if (containsRadius) {
                    assertFalse(LodNearExclusion.isEntirelyInside(0, 0, min, 0, size, radius),
                            "section " + min + ".." + (min + size) + " contains the radius and must draw");
                }
            }
        }
    }
}
