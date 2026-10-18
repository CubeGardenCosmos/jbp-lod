package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** std430 draw-count/control block shared by prep, command generation and indirect dispatch. */
public record DrawControl(
        int dispatchX,
        int dispatchY,
        int dispatchZ,
        int opaqueDrawCount,
        int translucentDrawCount,
        int temporalOpaqueDrawCount,
        DrawIndexedIndirectCommand cullCommand
) {
    public static final int BYTE_SIZE = 44;
    public static final int DISPATCH_OFFSET = 0;
    public static final int OPAQUE_COUNT_OFFSET = 12;
    public static final int TRANSLUCENT_COUNT_OFFSET = 16;
    public static final int TEMPORAL_COUNT_OFFSET = 20;
    public static final int CULL_COMMAND_OFFSET = 24;

    public DrawControl {
        if (dispatchX < 0 || dispatchY < 0 || dispatchZ < 0
                || opaqueDrawCount < 0 || translucentDrawCount < 0 || temporalOpaqueDrawCount < 0) {
            throw new IllegalArgumentException("dispatch dimensions and draw counts must be non-negative");
        }
        if (cullCommand == null) {
            throw new NullPointerException("cullCommand");
        }
    }

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("draw control does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(byteOffset, dispatchX);
        bytes.putInt(byteOffset + 4, dispatchY);
        bytes.putInt(byteOffset + 8, dispatchZ);
        bytes.putInt(byteOffset + 12, opaqueDrawCount);
        bytes.putInt(byteOffset + 16, translucentDrawCount);
        bytes.putInt(byteOffset + 20, temporalOpaqueDrawCount);
        cullCommand.writeTo(bytes, byteOffset + CULL_COMMAND_OFFSET);
    }
}
