package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.SpriteContentsAccessor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Collects baked-model quads in the bakery vertex layout (24 bytes: float x,y,z @0, uint meta @12, float u,v @16).
 * Port of upstream {@code bakery/ReuseVertexConsumer}; native memory is replaced by a heap buffer and the result
 * is triangulated for the Vulkan raster pass. Meta bit 0 = alpha discard, bit 2 = tinted.
 */
final class BakeVertexConsumer implements VertexConsumer {
    static final int VERTEX_SIZE = 24;
    private static final int[] QUAD_TRIANGLE_ORDER = {0, 1, 2, 2, 3, 0};

    private ByteBuffer buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
    private int count;
    private int defaultMeta;
    private final int globalOrMetadata;

    boolean anyShaded;
    boolean anyDarkendTex;
    boolean anyDiscard;

    BakeVertexConsumer() {
        this(0);
    }

    BakeVertexConsumer(int globalOrMetadata) {
        this.globalOrMetadata = globalOrMetadata;
    }

    BakeVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    int getDefaultMeta() {
        return defaultMeta;
    }

    @Override
    public BakeVertexConsumer addVertex(float x, float y, float z) {
        ensureCapacity((count + 1) * VERTEX_SIZE);
        int base = count * VERTEX_SIZE;
        count++;
        buffer.putFloat(base, x).putFloat(base + 4, y).putFloat(base + 8, z);
        buffer.putInt(base + 12, defaultMeta | globalOrMetadata);
        buffer.putFloat(base + 16, 0.0f).putFloat(base + 20, 0.0f);
        anyDiscard |= ((defaultMeta | globalOrMetadata) & 1) != 0;
        return this;
    }

    BakeVertexConsumer meta(int metadata) {
        anyDiscard |= (metadata & 1) != 0;
        buffer.putInt(lastBase() + 12, metadata);
        return this;
    }

    @Override
    public BakeVertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        return this;
    }

    @Override
    public BakeVertexConsumer setUv(float u, float v) {
        int base = lastBase();
        buffer.putFloat(base + 16, u).putFloat(base + 20, v);
        return this;
    }

    @Override
    public BakeVertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public BakeVertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public BakeVertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public BakeVertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    @Override
    public BakeVertexConsumer setLineWidth(float width) {
        return this;
    }

    BakeVertexConsumer quad(BakedQuad quad, boolean forceSolid) {
        int meta = 0;
        meta |= forceSolid ? 0 : (quad.materialInfo().layer() != ChunkSectionLayer.SOLID ? 1 : 0);
        meta |= quad.materialInfo().isTinted() ? 4 : 0;
        return quad(quad, meta);
    }

    BakeVertexConsumer quad(BakedQuad quad, int metadata) {
        anyShaded |= quad.materialInfo().shadeDirectionOverride() == null;
        anyDarkendTex |= ((SpriteContentsAccessor) quad.materialInfo().sprite().contents()).jbpLod$getMipmapStrategy()
                == MipmapStrategy.DARK_CUTOUT;
        for (int i = 0; i < 4; i++) {
            var pos = quad.position(i);
            addVertex(pos.x(), pos.y(), pos.z());
            long packedUv = quad.packedUV(i);
            setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv));
            meta(metadata | globalOrMetadata);
        }
        return this;
    }

    BakeVertexConsumer reset() {
        anyShaded = false;
        anyDarkendTex = false;
        anyDiscard = false;
        defaultMeta = 0;
        count = 0;
        return this;
    }

    boolean isEmpty() {
        return count == 0;
    }

    int quadCount() {
        if (count % 4 != 0) {
            throw new IllegalStateException("bake vertex consumer holds a partial quad");
        }
        return count / 4;
    }

    /** Appends this consumer's quads as two triangles each (0,1,2 / 2,3,0), the order upstream rasterizes. */
    void appendTriangles(ByteBuffer out) {
        int quads = quadCount();
        for (int quad = 0; quad < quads; quad++) {
            for (int corner : QUAD_TRIANGLE_ORDER) {
                int base = (quad * 4 + corner) * VERTEX_SIZE;
                for (int offset = 0; offset < VERTEX_SIZE; offset += Integer.BYTES) {
                    out.putInt(buffer.getInt(base + offset));
                }
            }
        }
    }

    /** Triangle vertices (6 per quad) in the layout consumed by the baker. */
    int triangleVertexCount() {
        return quadCount() * 6;
    }

    private int lastBase() {
        if (count == 0) {
            throw new IllegalStateException("vertex attribute set before addVertex");
        }
        return (count - 1) * VERTEX_SIZE;
    }

    private void ensureCapacity(int bytes) {
        if (bytes <= buffer.capacity()) {
            return;
        }
        int capacity = buffer.capacity();
        while (capacity < bytes) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        ByteBuffer replacement = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        replacement.put(0, buffer, 0, buffer.capacity());
        buffer = replacement;
    }
}
