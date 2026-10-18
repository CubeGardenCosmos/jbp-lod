package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

/** Render-thread ownership: release the owner now, destroy only after all slot leases retire. */
final class LodResourceLifetime {
    private final Runnable destroy;
    private int leases;
    private boolean released;
    private boolean destroyed;

    LodResourceLifetime(Runnable destroy) {
        this.destroy = destroy;
    }

    AutoCloseable acquire() {
        if (released) throw new IllegalStateException("resource owner already released");
        leases++;
        return new AutoCloseable() {
            private boolean closed;
            @Override public void close() {
                if (closed) return;
                closed = true;
                leases--;
                destroyIfRetired();
            }
        };
    }

    void release() {
        released = true;
        destroyIfRetired();
    }

    private void destroyIfRetired() {
        if (released && leases == 0 && !destroyed) {
            destroyed = true;
            destroy.run();
        }
    }
}
