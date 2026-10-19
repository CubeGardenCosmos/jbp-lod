package cn.cubegarden.cosmos.jbp.client.lod.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class LodSceneUniformTest {
    @Test
    void writesStd140OffsetsForMvpBaseSectionFrameAndCameraSubPosition() {
        float[] mvp = new float[16];
        for (int i = 0; i < 16; i++) {
            mvp[i] = 0.5f * i;
        }
        LodSceneUniform scene = new LodSceneUniform(mvp, -3, 4, 5, 77, 0.25f, 0.5f, 0.75f, 48.0f);
        ByteBuffer buffer = ByteBuffer.allocate(LodSceneUniform.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        scene.writeTo(buffer, 0);

        assertEquals(96, LodSceneUniform.BYTE_SIZE);
        assertEquals(0, LodSceneUniform.MVP_OFFSET);
        assertEquals(64, LodSceneUniform.BASE_SECTION_POSITION_OFFSET);
        assertEquals(76, LodSceneUniform.FRAME_ID_OFFSET);
        assertEquals(80, LodSceneUniform.CAMERA_SUB_POSITION_OFFSET);
        for (int i = 0; i < 16; i++) {
            assertEquals(mvp[i], buffer.getFloat(i * Float.BYTES), 0.0f, "mvp element " + i);
        }
        assertEquals(-3, buffer.getInt(64));
        assertEquals(4, buffer.getInt(68));
        assertEquals(5, buffer.getInt(72));
        assertEquals(77, buffer.getInt(76));
        assertEquals(0.25f, buffer.getFloat(80), 0.0f);
        assertEquals(0.5f, buffer.getFloat(84), 0.0f);
        assertEquals(0.75f, buffer.getFloat(88), 0.0f);
        assertEquals(92, LodSceneUniform.EXCLUSION_RADIUS_OFFSET);
        assertEquals(48.0f, buffer.getFloat(92), 0.0f);
    }

    @Test
    void exclusionRadiusIsClampedToTheFiniteTailPadding() {
        LodSceneUniform scene = new LodSceneUniform(new float[16], 0, 0, 0, 0, 0f, 0f, 0f, -4.0f);
        ByteBuffer buffer = ByteBuffer.allocate(LodSceneUniform.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        scene.writeTo(buffer, 0);
        assertEquals(0.0f, buffer.getFloat(92), 0.0f);
        assertEquals(96, LodSceneUniform.BYTE_SIZE);
    }

    @Test
    void rejectsMatrixOfWrongLengthAndBlockThatDoesNotFit() {
        assertThrows(IllegalArgumentException.class,
                () -> new LodSceneUniform(new float[15], 0, 0, 0, 0, 0f, 0f, 0f, 0f));
        LodSceneUniform scene = new LodSceneUniform(new float[16], 0, 0, 0, 0, 0f, 0f, 0f, 0f);
        assertThrows(IndexOutOfBoundsException.class,
                () -> scene.writeTo(ByteBuffer.allocate(LodSceneUniform.BYTE_SIZE - 1), 0));
    }
}
