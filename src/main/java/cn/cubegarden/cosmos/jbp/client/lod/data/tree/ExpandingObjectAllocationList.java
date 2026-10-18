package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import java.util.function.IntFunction;

/**
 * Id-allocating object table.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code client/core/util/ExpandingObjectAllocationList.java}.
 */
public final class ExpandingObjectAllocationList<T> {
    private static final float GROWTH_FACTOR = 0.75f;

    private final IntFunction<T[]> arrayGenerator;
    private final HierarchicalBitSet bitSet;
    private T[] objects;

    public ExpandingObjectAllocationList(IntFunction<T[]> arrayGenerator) {
        this(arrayGenerator, -1);
    }

    public ExpandingObjectAllocationList(IntFunction<T[]> arrayGenerator, int limit) {
        this.arrayGenerator = arrayGenerator;
        this.objects = this.arrayGenerator.apply(16);
        this.bitSet = limit != -1 ? new HierarchicalBitSet(limit) : new HierarchicalBitSet();
    }

    public int put(T obj) {
        int id = this.bitSet.allocateNext();
        if (id < 0) {
            throw new IllegalStateException("Id over max request capacity");
        }
        if (this.objects.length <= id) {
            int newLen = this.objects.length + (int) Math.ceil(this.objects.length * GROWTH_FACTOR);
            T[] newArr = this.arrayGenerator.apply(newLen);
            System.arraycopy(this.objects, 0, newArr, 0, this.objects.length);
            this.objects = newArr;
        }
        this.objects[id] = obj;
        return id;
    }

    public void release(int id) {
        if (!this.bitSet.free(id)) {
            throw new IllegalArgumentException("Index " + id + " was already released");
        }
        this.objects[id] = null;
    }

    public T get(int index) {
        if (!this.bitSet.isSet(index)) {
            throw new IllegalArgumentException("Index " + index + " is not allocated");
        }
        return this.objects[index];
    }

    public int count() {
        return this.bitSet.getCount();
    }
}
