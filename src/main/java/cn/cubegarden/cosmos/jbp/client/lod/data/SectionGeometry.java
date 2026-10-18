package cn.cubegarden.cosmos.jbp.client.lod.data;

/** Packed section metadata and two-word quad records ready for byte-preserving GPU upload. */
public final class SectionGeometry {
    private final byte[] metadataBytes;
    private final byte[] quadBytes;
    private final int sectionCount;
    private final int quadCount;

    public SectionGeometry(byte[] metadataBytes, byte[] quadBytes, int sectionCount, int quadCount) {
        if (metadataBytes.length != Math.multiplyExact(sectionCount, SectionMeta.BYTE_SIZE)) {
            throw new IllegalArgumentException("section metadata byte count does not match section count");
        }
        if (quadBytes.length != Math.multiplyExact(quadCount, PackedQuad.BYTE_SIZE)) {
            throw new IllegalArgumentException("packed quad byte count does not match quad count");
        }
        this.metadataBytes = metadataBytes.clone();
        this.quadBytes = quadBytes.clone();
        this.sectionCount = sectionCount;
        this.quadCount = quadCount;
    }

    public byte[] metadataBytes() {
        return metadataBytes.clone();
    }

    public byte[] quadBytes() {
        return quadBytes.clone();
    }

    public int sectionCount() {
        return sectionCount;
    }

    public int quadCount() {
        return quadCount;
    }
}
