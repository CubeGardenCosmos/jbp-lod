package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import java.util.Arrays;

/**
 * Mesh result for one section position: packed 8-byte quads partitioned by eight face-order ranges.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/building/BuiltSection.java}. Native {@code MemoryBuffer}s become heap
 * {@code byte[]}; the occupancy buffer (only used by upstream's geometry cache) is not carried.
 * {@code offsets[i]} is the starting quad index of face-order range {@code i} (0..7, in
 * {@link cn.cubegarden.cosmos.jbp.client.lod.data.SectionMeta#quadCount} order); the final range ends at
 * {@link #quadCount()}.
 */
public final class BuiltSection {
    public final long position;
    public final byte childExistence;
    public final int aabb;
    private final byte[] quads;
    private final int[] offsets;

    private BuiltSection(long position, byte children) {
        this.position = position;
        this.childExistence = children;
        this.aabb = -1;
        this.quads = null;
        this.offsets = null;
    }

    public static BuiltSection empty(long position) {
        return new BuiltSection(position, (byte) 0);
    }

    public static BuiltSection emptyWithChildren(long position, byte children) {
        return new BuiltSection(position, children);
    }

    public BuiltSection(long position, byte childExistence, int aabb, byte[] packedQuads, int[] offsets) {
        if (packedQuads == null || offsets == null) {
            throw new NullPointerException("non-empty section requires quads and offsets");
        }
        if (packedQuads.length == 0 || packedQuads.length % 8 != 0) {
            throw new IllegalArgumentException("packed quads must be a non-empty multiple of 8 bytes");
        }
        if (offsets.length != 8) {
            throw new IllegalArgumentException("section needs exactly 8 face offsets");
        }
        int count = packedQuads.length / 8;
        for (int i = 0; i < 8; i++) {
            int next = i == 7 ? count : offsets[i + 1];
            int delta = next - offsets[i];
            if (offsets[i] < 0 || delta < 0 || delta >= (1 << 16)) {
                throw new IllegalArgumentException("Offsets out of range");
            }
        }
        this.position = position;
        this.childExistence = childExistence;
        this.aabb = aabb;
        this.quads = packedQuads.clone();
        this.offsets = offsets.clone();
    }

    public boolean isEmpty() {
        return this.quads == null;
    }

    public int quadCount() {
        return this.quads == null ? 0 : this.quads.length / 8;
    }

    /** Quads in the translucent face-order range (offset range 0); diagnostics and tests only. */
    public int translucentQuadCount() {
        return this.offsets == null ? 0 : this.offsets[1] - this.offsets[0];
    }

    /** Defensive copy. */
    public byte[] packedQuads() {
        return this.quads.clone();
    }

    byte[] quadsUnsafe() {
        return this.quads;
    }

    int[] offsetsUnsafe() {
        return this.offsets;
    }

    @Override
    public String toString() {
        return "BuiltSection[" + SectionPos.pretty(this.position) + " quads=" + this.quadCount()
                + " offsets=" + Arrays.toString(this.offsets) + "]";
    }
}
