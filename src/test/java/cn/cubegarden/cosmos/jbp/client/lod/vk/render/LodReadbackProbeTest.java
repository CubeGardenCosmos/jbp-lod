package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.compute.LodComputeLayout;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodReadbackProbeTest {
    @Test
    void disabledProbeNeverFires() {
        LodReadbackProbe probe = new LodReadbackProbe(false);
        for (int i = 0; i < LodReadbackProbe.WARMUP_FRAMES * 3; i++) {
            assertFalse(probe.tick());
        }
    }

    @Test
    void enabledProbeSamplesConsecutiveFramePairsAfterWarmup() {
        LodReadbackProbe probe = new LodReadbackProbe(true);
        java.util.List<Integer> fired = new java.util.ArrayList<>();
        for (int i = 1; i <= LodReadbackProbe.WARMUP_FRAMES + 10 * LodReadbackProbe.SAMPLE_INTERVAL_FRAMES; i++) {
            if (probe.tick()) {
                fired.add(i);
            }
        }
        // Pairs of adjacent frames: pass-count oscillation between frame N and N+1 is visible per sample.
        assertEquals(java.util.List.of(120, 121, 240, 241, 360, 361, 480, 481), fired);
        assertEquals(LodReadbackProbe.MAX_SAMPLES, fired.size());
    }

    @Test
    void visibilityCensusSplitsPreviousCurrentAndTemporalNextSections() {
        int frameId = LodComputeLayout.packFrameId(7);
        ByteBuffer words = ByteBuffer.allocate(5 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        // Absolute puts: the buffer position stays 0, so no flip is needed before reading.
        words.putInt(0, LodComputeLayout.encodeVisibility(frameId, true));   // drawn now and last frame
        words.putInt(4, LodComputeLayout.encodeVisibility(frameId, false));  // temporal addition next frame
        words.putInt(8, LodComputeLayout.encodeVisibility(frameId - 1, true)); // stale: not in this frame
        words.putInt(12, -1);                                                // never visited
        words.putInt(16, LodComputeLayout.encodeVisibility(frameId, true));  // drawn now and last frame

        LodReadbackProbe.VisibilityStats stats = LodReadbackProbe.summarizeVisibility(words, 5, frameId);
        assertEquals(3, stats.visibleNow());
        assertEquals(2, stats.previousFlagSet());
        assertEquals(1, stats.temporalNext());
        assertEquals(2, stats.other());
    }

    @Test
    void summarizesSeedStencilSkyAndVanillaCounts() {
        ByteBuffer stencil = ByteBuffer.allocate(6).put((byte) 1).put((byte) 1).put((byte) 0)
                .put((byte) 1).put((byte) 0).put((byte) 2);
        stencil.flip();
        LodReadbackProbe.StencilStats stats = LodReadbackProbe.summarizeStencil(stencil, 6);

        assertEquals(3, stats.sky());
        assertEquals(2, stats.vanilla());
        assertEquals(1, stats.other());
        assertEquals(6, stats.pixels());
        assertEquals(2.0 / 6.0, stats.vanillaShare(), 1e-12);
    }

    @Test
    void summarizesHalfFloatChannelsAndNonzeroPixels() {
        ByteBuffer bytes = ByteBuffer.allocate(3 * LodReadbackProbe.HALF_RGBA_PIXEL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        putPixel(bytes, 0, 0.0f, 0.0f, 0.0f, 0.0f);
        putPixel(bytes, 1, 1.0f, 0.5f, -2.0f, 1.0f);
        putPixel(bytes, 2, 0.25f, 0.0f, 0.0f, 0.0f);

        LodReadbackProbe.ColorStats stats = LodReadbackProbe.summarizeRgba16f(bytes, 3);

        assertArrayEquals(new float[]{0.0f, 0.0f, -2.0f, 0.0f}, stats.min());
        assertArrayEquals(new float[]{1.0f, 0.5f, 0.0f, 1.0f}, stats.max());
        assertEquals(2, stats.nonzeroPixels());
        assertEquals(3, stats.pixels());
        assertEquals(0, stats.nonFinite());
    }

    @Test
    void countsNonFiniteChannelsWithoutPollutingRange() {
        ByteBuffer bytes = ByteBuffer.allocate(LodReadbackProbe.HALF_RGBA_PIXEL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        putPixel(bytes, 0, Float.NaN, 0.5f, Float.POSITIVE_INFINITY, 0.0f);

        LodReadbackProbe.ColorStats stats = LodReadbackProbe.summarizeRgba16f(bytes, 1);

        assertEquals(2, stats.nonFinite());
        assertEquals(0.5f, stats.min()[1]);
        assertEquals(0.5f, stats.max()[1]);
        assertEquals(1, stats.nonzeroPixels());
    }

    @Test
    void countsOnlyCommandsWithIndicesAndInstancesInRegion() {
        int commandCount = 4;
        ByteBuffer commands = ByteBuffer.allocate(commandCount * LodReadbackProbe.COMMAND_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        putCommand(commands, 0, 6, 1);   // drawn
        putCommand(commands, 1, 0, 1);   // empty index range
        putCommand(commands, 2, 36, 0);  // zero instances
        putCommand(commands, 3, 12, 4);  // drawn

        assertEquals(2, LodReadbackProbe.countDrawCommands(commands, 0, commandCount));
        assertEquals(1, LodReadbackProbe.countDrawCommands(commands, 3, 1));
        assertEquals(0, LodReadbackProbe.countDrawCommands(commands, 1, 2));
    }

    @Test
    void describesDrawControlAsElevenLittleEndianInts() {
        ByteBuffer control = ByteBuffer.allocate(LodReadbackProbe.CONTROL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < LodReadbackProbe.CONTROL_BYTES / 4; i++) {
            control.putInt(i * 4, i + 1);
        }

        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, LodReadbackProbe.describeControl(control));
    }

    private static void putPixel(ByteBuffer bytes, int pixel, float r, float g, float b, float a) {
        int base = pixel * LodReadbackProbe.HALF_RGBA_PIXEL_BYTES;
        bytes.putShort(base, Float.floatToFloat16(r));
        bytes.putShort(base + 2, Float.floatToFloat16(g));
        bytes.putShort(base + 4, Float.floatToFloat16(b));
        bytes.putShort(base + 6, Float.floatToFloat16(a));
    }

    private static void putCommand(ByteBuffer commands, int index, int indexCount, int instanceCount) {
        int base = index * LodReadbackProbe.COMMAND_BYTES;
        commands.putInt(base, indexCount);
        commands.putInt(base + 4, instanceCount);
    }
}
