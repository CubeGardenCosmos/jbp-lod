package cn.cubegarden.cosmos.jbp.client.lod.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DrawIndexedIndirectCommandTest {
    @Test
    void writesFiveLittleEndianWordsInVulkanOrder() {
        DrawIndexedIndirectCommand command = new DrawIndexedIndirectCommand(36, 2, 3, -4, 5);
        ByteBuffer buffer = ByteBuffer.allocate(DrawIndexedIndirectCommand.BYTE_SIZE + 4).order(ByteOrder.LITTLE_ENDIAN);

        command.writeTo(buffer, 4);

        assertEquals(20, DrawIndexedIndirectCommand.BYTE_SIZE);
        assertEquals(36, buffer.getInt(4));
        assertEquals(2, buffer.getInt(8));
        assertEquals(3, buffer.getInt(12));
        assertEquals(-4, buffer.getInt(16));
        assertEquals(5, buffer.getInt(20));
    }

    @Test
    void forQuadRangeUsesSixIndicesAndFourVerticesPerQuad() {
        DrawIndexedIndirectCommand command = DrawIndexedIndirectCommand.forQuadRange(10, 7, 9);

        assertEquals(new DrawIndexedIndirectCommand(42, 1, 0, 40, 9), command);
    }

    @Test
    void forQuadRangeRejectsNegativeInputs() {
        assertThrows(IllegalArgumentException.class, () -> DrawIndexedIndirectCommand.forQuadRange(-1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> DrawIndexedIndirectCommand.forQuadRange(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> DrawIndexedIndirectCommand.forQuadRange(0, 1, -1));
    }
}
