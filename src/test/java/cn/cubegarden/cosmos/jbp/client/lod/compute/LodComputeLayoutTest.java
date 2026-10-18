package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers.DrawRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodComputeLayoutTest {
    private static float[] forwardPerspective() {
        float near = 0.1f;
        float far = 100.0f;
        float f = 1.0f / (float) Math.tan(Math.toRadians(35.0));
        float[] m = new float[16];
        m[0] = f / 1.5f;
        m[5] = -f;
        m[10] = far / (near - far);
        m[11] = -1.0f;
        m[14] = near * far / (near - far);
        return m;
    }

    private static float[] reverseOf(float[] forward) {
        float[] m = forward.clone();
        for (int col = 0; col < 4; col++) {
            m[col * 4 + 2] = forward[col * 4 + 3] - forward[col * 4 + 2];
        }
        return m;
    }

    private static float planeValue(float[] planes, int plane, float x, float y, float z) {
        int i = plane * 4;
        return planes[i] * x + planes[i + 1] * y + planes[i + 2] * z + planes[i + 3];
    }

    @Test
    void drawRegionConstantsMatchTheFrameBufferContract() {
        assertEquals(LodFrameBuffers.commandByteOffset(DrawRegion.TRANSLUCENT),
                (long) LodComputeLayout.TRANSLUCENT_OFFSET * 20);
        assertEquals(LodFrameBuffers.commandByteOffset(DrawRegion.TEMPORAL_OPAQUE),
                (long) LodComputeLayout.TEMPORAL_OFFSET * 20);
        assertEquals(400_000, LodComputeLayout.TRANSLUCENT_OFFSET);
        assertEquals(500_000, LodComputeLayout.TEMPORAL_OFFSET);
        assertEquals(1024, LodComputeLayout.TRANSLUCENT_WRITE_BASE);
        assertEquals(LodFrameBuffers.TRANSLUCENT_DISTANCE_BINS, LodComputeLayout.PREFIX_SUM_VALUES);
        assertEquals(12, LodFrameBuffers.drawCountByteOffset(DrawRegion.OPAQUE));
        assertEquals(24, LodComputeLayout.DRAW_CONTROL_CULL_COMMAND_OFFSET);
        assertEquals(44, LodComputeLayout.DRAW_CONTROL_CULL_COMMAND_OFFSET
                + LodComputeLayout.DRAW_CONTROL_CULL_COMMAND_BYTES);
    }

    @Test
    void queueMetadataSeedsOnlyTheFirstLayerAndRespectsGroupSize() {
        assertArrayEquals(new int[]{4, 1, 1, 100, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0},
                LodComputeLayout.traversalQueueMetadata(100));
        assertArrayEquals(new int[]{0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0},
                LodComputeLayout.traversalQueueMetadata(0));
        assertEquals(1, LodComputeLayout.firstTraversalGroups(1));
        assertEquals(1, LodComputeLayout.firstTraversalGroups(32));
        assertEquals(2, LodComputeLayout.firstTraversalGroups(33));
        assertEquals(16L, LodComputeLayout.traversalDispatchOffset(1));
        assertEquals(64L, LodComputeLayout.traversalDispatchOffset(4));
        assertThrows(IllegalArgumentException.class, () -> LodComputeLayout.traversalDispatchOffset(0));
        assertThrows(IllegalArgumentException.class,
                () -> LodComputeLayout.traversalDispatchOffset(LodComputeLayout.MAX_ITERATIONS));
        assertEquals(LodComputeLayout.MAX_ITERATIONS * 16, LodComputeLayout.QUEUE_METADATA_BYTES);
    }

    @Test
    void traversalQueuesPingPongLikeUpstream() {
        assertEquals(LodComputeLayout.QueueRole.TOP_LEVEL, LodComputeLayout.traversalSource(0));
        assertEquals(LodComputeLayout.QueueRole.B, LodComputeLayout.traversalSink(0));
        for (int i = 1; i < LodComputeLayout.MAX_ITERATIONS; i++) {
            assertEquals(LodComputeLayout.traversalSink(i - 1), LodComputeLayout.traversalSource(i),
                    "layer " + i + " must consume what layer " + (i - 1) + " produced");
            assertFalse(LodComputeLayout.traversalSource(i) == LodComputeLayout.traversalSink(i));
        }
        assertEquals(LodComputeLayout.QueueRole.B, LodComputeLayout.traversalSource(1));
        assertEquals(LodComputeLayout.QueueRole.A, LodComputeLayout.traversalSource(2));
    }

    @Test
    void sectionAndCleanerDispatchGroups() {
        assertEquals(0, LodComputeLayout.sectionGroups(0));
        assertEquals(1, LodComputeLayout.sectionGroups(1));
        assertEquals(1, LodComputeLayout.sectionGroups(128));
        assertEquals(2, LodComputeLayout.sectionGroups(129));
        assertEquals(1563, LodComputeLayout.sectionGroups(200_000));
        assertEquals(0, LodComputeLayout.cleanerGroups(0));
        assertEquals(1, LodComputeLayout.cleanerGroups(512));
        assertEquals(2, LodComputeLayout.cleanerGroups(513));
        assertEquals(3072L, LodComputeLayout.CLEANER_OUTPUT_BYTES);
        assertEquals(1024L, LodComputeLayout.CLEANER_SCRATCH_BYTES);
    }

    @Test
    void frameIdIsThirtyOneBitAndNegativeIsRejected() {
        assertEquals(5, LodComputeLayout.packFrameId(5));
        assertEquals(0x7fffffff, LodComputeLayout.packFrameId(0x7fffffffL));
        assertEquals(0, LodComputeLayout.packFrameId(0x80000000L));
        assertThrows(IllegalArgumentException.class, () -> LodComputeLayout.packFrameId(-1));
    }

    @Test
    void cameraSectionAndSubSectionFollowUpstreamViewport() {
        assertEquals(0, LodComputeLayout.cameraSection(0.0));
        assertEquals(1, LodComputeLayout.cameraSection(32.5));
        assertEquals(-1, LodComputeLayout.cameraSection(-0.5));
        assertEquals(-2, LodComputeLayout.cameraSection(-32.5));
        assertEquals(31.5f, LodComputeLayout.cameraSubSection(-0.5), 0.0f);
        assertEquals(0.5f, LodComputeLayout.cameraSubSection(32.5), 0.0f);
        assertEquals(31.5f, LodComputeLayout.cameraSubSection(-32.5), 0.0f);
    }

    @Test
    void translatedMvpEqualsMatrixTimesNegativeTranslation() {
        float[] mvp = forwardPerspective();
        float[] translated = LodComputeLayout.translateMvp(mvp, 3.0f, 4.0f, 5.0f);
        // (M * T(-sub)) * v == M * (v - sub)
        float vx = 7.0f;
        float vy = -2.0f;
        float vz = -11.0f;
        for (int row = 0; row < 4; row++) {
            float viaTranslated = translated[row] * vx + translated[4 + row] * vy + translated[8 + row] * vz
                    + translated[12 + row];
            float direct = mvp[row] * (vx - 3.0f) + mvp[4 + row] * (vy - 4.0f) + mvp[8 + row] * (vz - 5.0f)
                    + mvp[12 + row];
            assertEquals(direct, viaTranslated, 1e-4f);
        }
        assertArrayEquals(forwardPerspective(), mvp, "input matrix must not be modified");
    }

    @Test
    void frustumPlanesClassifyPointsForForwardAndReverseDepth() {
        float[] forward = forwardPerspective();
        float[] planes = LodComputeLayout.frustumPlanes(forward, false);
        assertEquals(24, planes.length);
        for (int plane = 0; plane < 6; plane++) {
            assertTrue(planeValue(planes, plane, 0, 0, -5) >= 0, "centre point inside plane " + plane);
        }
        assertTrue(planeValue(planes, 0, -100, 0, -5) < 0, "far left is outside the left plane");
        assertTrue(planeValue(planes, 1, 100, 0, -5) < 0, "far right is outside the right plane");
        assertTrue(planeValue(planes, 4, 0, 0, 5) < 0, "behind camera is outside near");
        assertTrue(planeValue(planes, 4, 0, 0, -0.05f) < 0, "closer than near is outside near");
        assertTrue(planeValue(planes, 5, 0, 0, -200) < 0, "beyond far is outside far");

        float[] reversePlanes = LodComputeLayout.frustumPlanes(reverseOf(forward), true);
        assertArrayEquals(planes, reversePlanes, 1e-4f);
    }

    @Test
    void screenSpaceThresholdAndRenderDistanceMatchUpstreamFormulas() {
        assertEquals(64.0f * 64.0f / (1920.0f * 1080.0f),
                LodComputeLayout.minimumScreenSpaceSize(64.0f, 1920, 1080), 0.0f);
        assertThrows(IllegalArgumentException.class, () -> LodComputeLayout.minimumScreenSpaceSize(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> LodComputeLayout.minimumScreenSpaceSize(64, 0, 10));
        assertEquals(-1.0f, LodComputeLayout.renderDistanceSquared(-1), 0.0f);
        assertEquals((float) Math.pow(16.0 * 16.0 * 32.0, 2), LodComputeLayout.renderDistanceSquared(16), 0.0f);
    }

    @Test
    void subgroupPrefixSumOnlyForThirtyTwoOrSixtyFourLanesOffIntel() {
        assertTrue(LodComputeLayout.useSubgroupPrefixSum(true, true, 32, 0x10de));
        assertTrue(LodComputeLayout.useSubgroupPrefixSum(true, true, 64, 0x1002));
        assertFalse(LodComputeLayout.useSubgroupPrefixSum(true, true, 16, 0x1002));
        assertFalse(LodComputeLayout.useSubgroupPrefixSum(true, true, 32, 0x8086));
        assertFalse(LodComputeLayout.useSubgroupPrefixSum(false, true, 32, 0x10de));
        assertFalse(LodComputeLayout.useSubgroupPrefixSum(true, false, 32, 0x10de));
    }
}
