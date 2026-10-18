package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.data.DrawControl;
import cn.cubegarden.cosmos.jbp.client.lod.data.DrawIndexedIndirectCommand;

/** CPU oracle for the upstream lod/gl46/prep.comp initialization semantics. */
public final class SectionPreparationReference {
    private SectionPreparationReference() {
    }

    public static DrawControl prepare(int sectionCount) {
        if (sectionCount < 0) {
            throw new IllegalArgumentException("section count must be non-negative");
        }
        int groupsX = (int) (((long) sectionCount + 127L) / 128L);
        return new DrawControl(groupsX, 1, 1, 0, 0, 0,
                new DrawIndexedIndirectCommand(36, sectionCount, 786_432, 0, 0));
    }
}
