package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Hierarchical traversal SceneUniform ABI: 208 bytes, std140. */
public final class TraversalSceneUniform {
    public static final int BYTE_SIZE = 208;
    public static final int MVP_OFFSET = 0;
    public static final int CAMERA_SECTION_OFFSET = 64;
    public static final int PACKED_HIZ_SIZE_OFFSET = 76;
    public static final int CAMERA_SUB_SECTION_OFFSET = 80;
    public static final int MIN_SSS_OFFSET = 92;
    public static final int FRUSTUM_PLANES_OFFSET = 96;
    public static final int RENDER_QUEUE_MAX_OFFSET = 192;
    public static final int FRAME_ID_OFFSET = 196;
    public static final int REQUEST_QUEUE_SIZE_OFFSET = 200;
    public static final int RENDER_DISTANCE_OFFSET = 204;

    private final float[] mvpColumnMajor;
    private final int cameraSectionX;
    private final int cameraSectionY;
    private final int cameraSectionZ;
    private final int packedHizSize;
    private final float cameraSubSectionX;
    private final float cameraSubSectionY;
    private final float cameraSubSectionZ;
    private final float minSss;
    private final float[] frustumPlanes;
    private final int renderQueueMaxSize;
    private final int frameId;
    private final int requestQueueSize;
    private final float renderDistance;

    public TraversalSceneUniform(float[] mvpColumnMajor,
                                 int cameraSectionX, int cameraSectionY, int cameraSectionZ,
                                 int packedHizSize, float cameraSubSectionX, float cameraSubSectionY,
                                 float cameraSubSectionZ, float minSss, float[] frustumPlanes,
                                 int renderQueueMaxSize, int frameId, int requestQueueSize,
                                 float renderDistance) {
        if (mvpColumnMajor.length != 16 || frustumPlanes.length != 24) {
            throw new IllegalArgumentException("MVP requires 16 and frustum requires 24 floats");
        }
        if (renderQueueMaxSize < 0 || requestQueueSize < 0) {
            throw new IllegalArgumentException("queue sizes must be non-negative");
        }
        this.mvpColumnMajor = mvpColumnMajor.clone();
        this.cameraSectionX = cameraSectionX;
        this.cameraSectionY = cameraSectionY;
        this.cameraSectionZ = cameraSectionZ;
        this.packedHizSize = packedHizSize;
        this.cameraSubSectionX = cameraSubSectionX;
        this.cameraSubSectionY = cameraSubSectionY;
        this.cameraSubSectionZ = cameraSubSectionZ;
        this.minSss = minSss;
        this.frustumPlanes = frustumPlanes.clone();
        this.renderQueueMaxSize = renderQueueMaxSize;
        this.frameId = frameId;
        this.requestQueueSize = requestQueueSize;
        this.renderDistance = renderDistance;
    }

    public void writeTo(ByteBuffer target, int byteOffset) {
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("TraversalSceneUniform does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < mvpColumnMajor.length; i++) {
            bytes.putFloat(byteOffset + MVP_OFFSET + i * Float.BYTES, mvpColumnMajor[i]);
        }
        bytes.putInt(byteOffset + CAMERA_SECTION_OFFSET, cameraSectionX);
        bytes.putInt(byteOffset + CAMERA_SECTION_OFFSET + Integer.BYTES, cameraSectionY);
        bytes.putInt(byteOffset + CAMERA_SECTION_OFFSET + 2 * Integer.BYTES, cameraSectionZ);
        bytes.putInt(byteOffset + PACKED_HIZ_SIZE_OFFSET, packedHizSize);
        bytes.putFloat(byteOffset + CAMERA_SUB_SECTION_OFFSET, cameraSubSectionX);
        bytes.putFloat(byteOffset + CAMERA_SUB_SECTION_OFFSET + Float.BYTES, cameraSubSectionY);
        bytes.putFloat(byteOffset + CAMERA_SUB_SECTION_OFFSET + 2 * Float.BYTES, cameraSubSectionZ);
        bytes.putFloat(byteOffset + MIN_SSS_OFFSET, minSss);
        for (int i = 0; i < frustumPlanes.length; i++) {
            bytes.putFloat(byteOffset + FRUSTUM_PLANES_OFFSET + i * Float.BYTES, frustumPlanes[i]);
        }
        bytes.putInt(byteOffset + RENDER_QUEUE_MAX_OFFSET, renderQueueMaxSize);
        bytes.putInt(byteOffset + FRAME_ID_OFFSET, frameId);
        bytes.putInt(byteOffset + REQUEST_QUEUE_SIZE_OFFSET, requestQueueSize);
        bytes.putFloat(byteOffset + RENDER_DISTANCE_OFFSET, renderDistance);
    }
}
