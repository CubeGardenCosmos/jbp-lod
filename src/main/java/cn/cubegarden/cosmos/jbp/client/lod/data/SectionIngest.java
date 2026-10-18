package cn.cubegarden.cosmos.jbp.client.lod.data;

import java.nio.ByteBuffer;
import java.util.List;

/** Concatenates upstream-format section records and rebases only their packed-quad indices. */
public final class SectionIngest {
    private SectionIngest() {
    }

    public static SectionGeometry pack(List<SectionInput> sections) {
        int quadCount = 0;
        for (SectionInput section : sections) {
            quadCount = Math.addExact(quadCount, section.quadCount());
            int declaredCount = 0;
            for (int i = 0; i < 8; i++) {
                declaredCount = Math.addExact(declaredCount, section.metadata().quadCount(i));
            }
            if (declaredCount != section.quadCount()) {
                throw new IllegalArgumentException("section metadata declares " + declaredCount
                        + " quads but input contains " + section.quadCount());
            }
        }

        byte[] metadata = new byte[Math.multiplyExact(sections.size(), SectionMeta.BYTE_SIZE)];
        byte[] quads = new byte[Math.multiplyExact(quadCount, PackedQuad.BYTE_SIZE)];
        ByteBuffer metadataView = ByteBuffer.wrap(metadata);
        int nextQuad = 0;
        for (int i = 0; i < sections.size(); i++) {
            SectionInput section = sections.get(i);
            section.metadata().withFirstQuad(nextQuad).writeTo(metadataView, i * SectionMeta.BYTE_SIZE);
            byte[] source = section.packedQuads();
            System.arraycopy(source, 0, quads, nextQuad * PackedQuad.BYTE_SIZE, source.length);
            nextQuad += section.quadCount();
        }
        return new SectionGeometry(metadata, quads, sections.size(), quadCount);
    }

    public record SectionInput(SectionMeta metadata, byte[] packedQuads) {
        public SectionInput {
            if (metadata == null || packedQuads == null) {
                throw new NullPointerException("section metadata and quad bytes are required");
            }
            if (packedQuads.length % PackedQuad.BYTE_SIZE != 0) {
                throw new IllegalArgumentException("packed quad input must be a multiple of 8 bytes");
            }
            packedQuads = packedQuads.clone();
        }

        @Override
        public byte[] packedQuads() {
            return packedQuads.clone();
        }

        public int quadCount() {
            return packedQuads.length / PackedQuad.BYTE_SIZE;
        }
    }
}
