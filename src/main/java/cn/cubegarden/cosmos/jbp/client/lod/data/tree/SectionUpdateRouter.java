package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.function.LongConsumer;

import static cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos.UPDATE_TYPE_BLOCK_BIT;
import static cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos.UPDATE_TYPE_CHILD_EXISTENCE_BIT;

/**
 * Watch registry that triggers the first mesh generation when a position starts watching block updates
 * and routes later world events to remesh/child-existence consumers.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code client/core/rendering/SectionUpdateRouter.java}.
 * The 16-slice StampedLock layout is replaced by per-slice monitors; watch/unwatch semantics (delta of
 * newly set bits, full removal result, initial-generation hook on first BLOCK bit) are unchanged.
 */
public final class SectionUpdateRouter implements SectionWatcher {
    private static final int SLICES = 1 << 4;

    /** Receives child-existence changes: position and the nonempty-child mask. */
    public interface ChildUpdate {
        void accept(long position, byte childExistence);
    }

    private final Long2ByteOpenHashMap[] slices = new Long2ByteOpenHashMap[SLICES];

    private volatile LongConsumer initialRenderMeshGen;
    private volatile LongConsumer renderMeshGen;
    private volatile ChildUpdate childUpdateCallback;

    public SectionUpdateRouter() {
        for (int i = 0; i < SLICES; i++) {
            this.slices[i] = new Long2ByteOpenHashMap();
            this.slices[i].defaultReturnValue((byte) 0);
        }
    }

    public void setCallbacks(LongConsumer initialRenderMeshGen, LongConsumer renderMeshGen,
                             ChildUpdate childUpdateCallback) {
        if (this.renderMeshGen != null) {
            throw new IllegalStateException("callbacks already set");
        }
        this.initialRenderMeshGen = initialRenderMeshGen;
        this.renderMeshGen = renderMeshGen;
        this.childUpdateCallback = childUpdateCallback;
    }

    @Override
    public boolean watch(long position, int types) {
        var set = this.slices[getSliceIndex(position)];
        byte delta;
        synchronized (set) {
            byte current = set.get(position);
            delta = (byte) (current & types);
            current |= (byte) types;
            delta ^= (byte) (current & types);
            if (delta != 0) {
                set.put(position, current);
            }
        }
        if (((delta & types) & UPDATE_TYPE_BLOCK_BIT) != 0) {
            //If we added it, immediately invoke for an update
            this.initialRenderMeshGen.accept(position);
        }
        return delta != 0;
    }

    @Override
    public boolean unwatch(long position, int types) {
        var set = this.slices[getSliceIndex(position)];
        synchronized (set) {
            byte current = set.get(position);
            if (current == 0) {
                throw new IllegalStateException("Section pos not in map " + SectionPos.pretty(position));
            }
            boolean removed = false;
            if ((current & types) != 0) {
                current &= (byte) ~types;
                if (current == 0) {
                    set.remove(position);
                    removed = true;
                } else {
                    set.put(position, current);
                }
            }
            return removed;
        }
    }

    @Override
    public int get(long position) {
        var set = this.slices[getSliceIndex(position)];
        synchronized (set) {
            return set.get(position);
        }
    }

    /** Forwards a world event for a watched section (children changed and/or blocks changed). */
    public void forwardEvent(long position, int type, byte childExistence) {
        int types = this.get(position) & type;
        if (types != 0) {
            if ((types & UPDATE_TYPE_CHILD_EXISTENCE_BIT) != 0) {
                this.childUpdateCallback.accept(position, childExistence);
            }
            if ((types & UPDATE_TYPE_BLOCK_BIT) != 0) {
                this.renderMeshGen.accept(position);
            }
        }
    }

    public void triggerRemesh(long position) {
        if ((this.get(position) & UPDATE_TYPE_BLOCK_BIT) != 0) {
            this.renderMeshGen.accept(position);
        }
    }

    /** Number of watched positions (diagnostics/tests). */
    public int watchedCount() {
        int count = 0;
        for (Long2ByteOpenHashMap slice : this.slices) {
            synchronized (slice) {
                count += slice.size();
            }
        }
        return count;
    }

    private static int getSliceIndex(long value) {
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return (int) ((value ^ value >>> 31) & (SLICES - 1));
    }
}
