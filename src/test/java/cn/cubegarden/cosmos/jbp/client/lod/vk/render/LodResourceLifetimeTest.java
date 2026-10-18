package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LodResourceLifetimeTest {
    @Test void replacementWaitsForEverySubmittedSlot() throws Exception {
        AtomicInteger destroyed = new AtomicInteger();
        LodResourceLifetime lifetime = new LodResourceLifetime(destroyed::incrementAndGet);
        AutoCloseable first = lifetime.acquire();
        AutoCloseable second = lifetime.acquire();
        lifetime.release();
        assertEquals(0, destroyed.get());
        second.close();
        assertEquals(0, destroyed.get());
        first.close();
        assertEquals(1, destroyed.get());
        first.close();
        lifetime.release();
        assertEquals(1, destroyed.get());
    }

    @Test void unusedResourcesReleaseImmediately() {
        AtomicInteger destroyed = new AtomicInteger();
        LodResourceLifetime lifetime = new LodResourceLifetime(destroyed::incrementAndGet);
        lifetime.release();
        assertEquals(1, destroyed.get());
        assertThrows(IllegalStateException.class, lifetime::acquire);
    }

    @Test void retiredSlotDoesNotDestroyActiveOwner() throws Exception {
        AtomicInteger destroyed = new AtomicInteger();
        LodResourceLifetime lifetime = new LodResourceLifetime(destroyed::incrementAndGet);
        lifetime.acquire().close();
        assertEquals(0, destroyed.get());
        lifetime.release();
        assertEquals(1, destroyed.get());
    }
}
