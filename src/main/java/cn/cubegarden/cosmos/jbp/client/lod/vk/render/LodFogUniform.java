package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** std140 {@code LodFogBlock} at set 0 / binding 8 of the LOD draw shaders (112 bytes). */
public final class LodFogUniform {
    public static final int BYTE_SIZE = 112;
    public static final int MODEL_VIEW_OFFSET = 0;
    public static final int FOG_COLOR_OFFSET = 64;
    public static final int FOG_RANGE_OFFSET = 80;
    public static final int DEPTH_PARAMS_OFFSET = 96;

    private LodFogUniform() {
    }

    public static void write(ByteBuffer target, int byteOffset, float[] modelView,
                             LodFrameTargets.Fog fog, LodDepthConversion gameDepth) {
        if (modelView.length != 16) {
            throw new IllegalArgumentException("modelView must contain 16 column-major floats");
        }
        if (byteOffset < 0 || byteOffset > target.capacity() - BYTE_SIZE) {
            throw new IndexOutOfBoundsException("LodFogBlock does not fit at byte offset " + byteOffset);
        }
        ByteBuffer bytes = target.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            bytes.putFloat(byteOffset + MODEL_VIEW_OFFSET + i * Float.BYTES, modelView[i]);
        }
        bytes.putFloat(byteOffset + FOG_COLOR_OFFSET, fog.red());
        bytes.putFloat(byteOffset + FOG_COLOR_OFFSET + 4, fog.green());
        bytes.putFloat(byteOffset + FOG_COLOR_OFFSET + 8, fog.blue());
        bytes.putFloat(byteOffset + FOG_COLOR_OFFSET + 12, fog.alpha());
        bytes.putFloat(byteOffset + FOG_RANGE_OFFSET, fog.start());
        bytes.putFloat(byteOffset + FOG_RANGE_OFFSET + 4, fog.end());
        bytes.putFloat(byteOffset + FOG_RANGE_OFFSET + 8, 0.0f);
        bytes.putFloat(byteOffset + FOG_RANGE_OFFSET + 12, 0.0f);
        gameDepth.writeVec4(bytes, byteOffset + DEPTH_PARAMS_OFFSET);
    }
}
