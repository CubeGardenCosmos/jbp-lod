package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

/** Latest recorded vanilla-only snapshot, independent of which retired frame slot is reused next. */
final class LodVanillaSeedHistory {
    private int sourceSlot = -1;

    int sourceSlot() {
        return sourceSlot;
    }

    /** Publish only after the vanilla capture has been recorded, before the LOD composite. */
    void captured(int slot) {
        if (slot < 0) throw new IllegalArgumentException("negative snapshot slot");
        sourceSlot = slot;
    }
}
