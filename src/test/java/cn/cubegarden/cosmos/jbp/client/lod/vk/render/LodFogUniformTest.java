package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.junit.jupiter.api.Assertions.*;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class LodFogUniformTest {
    @Test
    void layoutMatchesStd140FogBlockWithoutDepthParams() {
        assertEquals(96, LodFogUniform.BYTE_SIZE);
        assertEquals(0, LodFogUniform.MODEL_VIEW_OFFSET);
        assertEquals(64, LodFogUniform.FOG_COLOR_OFFSET);
        assertEquals(80, LodFogUniform.FOG_RANGE_OFFSET);
    }

    @Test
    void writesModelViewFogColourAndRangeAtDeclaredOffsets() {
        float[] modelView = new float[16];
        for (int i = 0; i < 16; i++) {
            modelView[i] = i + 1;
        }
        LodFrameTargets.Fog fog = new LodFrameTargets.Fog(64.0f, 128.0f, 0.1f, 0.2f, 0.3f, 0.4f);
        ByteBuffer buffer = ByteBuffer.allocate(LodFogUniform.BYTE_SIZE + 8).order(ByteOrder.LITTLE_ENDIAN);

        LodFogUniform.write(buffer, 8, modelView, fog);

        for (int i = 0; i < 16; i++) {
            assertEquals(modelView[i], buffer.getFloat(8 + i * Float.BYTES), 0.0f);
        }
        assertEquals(0.1f, buffer.getFloat(8 + 64), 0.0f);
        assertEquals(0.2f, buffer.getFloat(8 + 68), 0.0f);
        assertEquals(0.3f, buffer.getFloat(8 + 72), 0.0f);
        assertEquals(0.4f, buffer.getFloat(8 + 76), 0.0f);
        assertEquals(64.0f, buffer.getFloat(8 + 80), 0.0f);
        assertEquals(128.0f, buffer.getFloat(8 + 84), 0.0f);
        assertEquals(0.0f, buffer.getFloat(8 + 88), 0.0f);
        assertEquals(0.0f, buffer.getFloat(8 + 92), 0.0f);
        assertEquals(0, buffer.getInt(0), "bytes before the block must be untouched");
    }

    @Test
    void rejectsBlockThatDoesNotFit() {
        LodFrameTargets.Fog fog = new LodFrameTargets.Fog(0, 1, 0, 0, 0, 0);
        ByteBuffer small = ByteBuffer.allocate(LodFogUniform.BYTE_SIZE - 1);
        assertThrows(IndexOutOfBoundsException.class,
                () -> LodFogUniform.write(small, 0, new float[16], fog));
    }
}
