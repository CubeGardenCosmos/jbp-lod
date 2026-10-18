package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** SceneUniform ABI shared by traversal and quad shaders (std140, 96-byte allocation). */
public final class LodSceneUniform {
    public static final int BYTE_SIZE = 96;
    public static final int MVP_OFFSET = 0;
    public static final int BASE_SECTION_POSITION_OFFSET = 64;
    public static final int FRAME_ID_OFFSET = 76;
    public static final int CAMERA_SUB_POSITION_OFFSET = 80;

    private final float[] mvpColumnMajor;
    private final int baseSectionX;
    private final int baseSectionY;
    private final int baseSectionZ;
    private final int frameId;
    private final float cameraSubX;
    private final float cameraSubY;
    private final float cameraSubZ;

    public LodSceneUniform(float[] mvpColumnMajor,
                           int baseSectionX, int baseSectionY, int baseSectionZ,
                           int frameId, float cameraSubX, float cameraSubY, float cameraSubZ) {
        if (mvpColumnMajor.length != 16) {
            throw new IllegalArgumentException("MVP matrix must have sixteen column-major values");
        }
        this.mvpColumnMajor = mvpColumnMajor.clone();
        this.baseSectionX = baseSectionX;
        this.baseSectionY = baseSectionY;
        this.baseSectionZ = baseSectionZ;
        this.frameId = frameId;
        this.cameraSubX = cameraSubX;
        this.cameraSubY = cameraSubY;
        this.cameraSubZ = cameraSubZ;
    }

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("SceneUniform does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            bytes.putFloat(byteOffset + MVP_OFFSET + i * Float.BYTES, mvpColumnMajor[i]);
        }
        bytes.putInt(byteOffset + BASE_SECTION_POSITION_OFFSET, baseSectionX);
        bytes.putInt(byteOffset + BASE_SECTION_POSITION_OFFSET + 4, baseSectionY);
        bytes.putInt(byteOffset + BASE_SECTION_POSITION_OFFSET + 8, baseSectionZ);
        bytes.putInt(byteOffset + FRAME_ID_OFFSET, frameId);
        bytes.putFloat(byteOffset + CAMERA_SUB_POSITION_OFFSET, cameraSubX);
        bytes.putFloat(byteOffset + CAMERA_SUB_POSITION_OFFSET + 4, cameraSubY);
        bytes.putFloat(byteOffset + CAMERA_SUB_POSITION_OFFSET + 8, cameraSubZ);
    }
}
