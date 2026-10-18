package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LodBoundingSectionStoreTest {
    @Test
    void resetKeepsCapacityAndDropsEntries() {
        LodBoundingSectionStore store = LodBoundingSectionStore.instance();
        store.reset();
        store.add(1L);
        store.add(2L);
        store.reset();
        assertEquals(0, store.count());

        store.add(3L);
        assertEquals(1, store.count());
        assertEquals(3L, store.get(0));
        store.reset();
    }

    @Test
    void growsPastInitialCapacityPreservingOrder() {
        LodBoundingSectionStore store = LodBoundingSectionStore.instance();
        store.reset();
        int count = 5000;
        for (int i = 0; i < count; i++) {
            store.add(i * 7L);
        }
        assertEquals(count, store.count());
        for (int i = 0; i < count; i++) {
            assertEquals(i * 7L, store.get(i));
        }
        store.reset();
    }
}
