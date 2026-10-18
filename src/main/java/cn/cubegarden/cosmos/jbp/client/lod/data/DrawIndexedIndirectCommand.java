package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Five-word Vulkan VkDrawIndexedIndirectCommand, matching the upstream std430 DrawCommand. */
public record DrawIndexedIndirectCommand(
        int indexCount,
        int instanceCount,
        int firstIndex,
        int vertexOffset,
        int firstInstance
) {
    public static final int BYTE_SIZE = 20;

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("indirect command does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(byteOffset, indexCount);
        bytes.putInt(byteOffset + 4, instanceCount);
        bytes.putInt(byteOffset + 8, firstIndex);
        bytes.putInt(byteOffset + 12, vertexOffset);
        bytes.putInt(byteOffset + 16, firstInstance);
    }

    public static DrawIndexedIndirectCommand forQuadRange(int firstQuad, int quadCount, int drawId) {
        if (firstQuad < 0 || quadCount < 0 || drawId < 0) {
            throw new IllegalArgumentException("quad range and draw ID must be non-negative");
        }
        return new DrawIndexedIndirectCommand(Math.multiplyExact(quadCount, 6), 1, 0,
                Math.multiplyExact(firstQuad, 4), drawId);
    }
}
