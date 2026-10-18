package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

/**
 * Best-fit free/taken range arena used for the packed-quad heap.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code common/util/AllocationArena.java}. The
 * alloc/free/merge algorithm and bit packing are unchanged; the unused {@code expand}/index helpers are
 * omitted. Upstream limitation preserved: a single block above 2^30 elements is unsupported.
 */
public final class AllocationArena {
    public static final long SIZE_LIMIT = -1;

    private static final int ADDR_BITS = 34;//This gives max size per allocation of 2^30 and max address of 2^39
    private static final int SIZE_BITS = 64 - ADDR_BITS;
    private static final long SIZE_MSK = (1L << SIZE_BITS) - 1;
    private static final long ADDR_MSK = (1L << ADDR_BITS) - 1;
    private final LongRBTreeSet FREE = new LongRBTreeSet(Long::compareUnsigned);//Size Address
    private final LongRBTreeSet TAKEN = new LongRBTreeSet(Long::compareUnsigned);//Address Size

    private long sizeLimit = Long.MAX_VALUE;
    private long totalSize;

    public long getSize() {
        return this.totalSize;
    }

    public int numFreeBlocks() {
        return this.FREE.size();
    }

    public long alloc(int size) {
        if (size == 0) throw new IllegalArgumentException();
        var iter = this.FREE.iterator(((long) size << ADDR_BITS) - 1);
        if (!iter.hasNext()) {//No free space for allocation
            long addr = this.totalSize;
            if (this.totalSize + size > this.sizeLimit) {
                return SIZE_LIMIT;
            }
            this.totalSize += size;
            this.TAKEN.add((addr << SIZE_BITS) | ((long) size));
            return addr;
        } else {
            long slot = iter.nextLong();
            iter.remove();
            if ((slot >>> ADDR_BITS) == size) {//If the allocation and slot is the same size, just add it to the taken
                this.TAKEN.add((slot << SIZE_BITS) | (slot >>> ADDR_BITS));
            } else {
                this.TAKEN.add(((slot & ADDR_MSK) << SIZE_BITS) | size);
                this.FREE.add((((slot >>> ADDR_BITS) - size) << ADDR_BITS) | ((slot & ADDR_MSK) + size));
            }
            return slot & ADDR_MSK;
        }
    }

    public int free(long addr) {//Returns size of freed memory
        addr &= ADDR_MSK;//encase addr stores shit in its upper bits
        var iter = this.TAKEN.iterator(addr << SIZE_BITS);//Dont need to include -1 as size != 0
        long slot = iter.nextLong();
        if (slot >> SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        long size = slot & SIZE_MSK;
        iter.remove();

        //Note: if there is a previous entry, it means that it is guaranteed for the ending address to either
        // be the addr, or indicate a free slot that needs to be merged
        if (iter.hasPrevious()) {
            long prevSlot = iter.previousLong();
            long endAddr = (prevSlot >>> SIZE_BITS) + (prevSlot & SIZE_MSK);
            if (endAddr != addr) {//It means there is a free slot that needs to get merged into
                long delta = (addr - endAddr);
                this.FREE.remove((delta << ADDR_BITS) | endAddr);//Free the slot to be merged into
                //Generate a new slot to get put into FREE
                slot = (endAddr << SIZE_BITS) | ((slot & SIZE_MSK) + delta);
            }
            iter.nextLong();//Need to reset the iter into its state
        } else if (!this.FREE.isEmpty()) {// if free is not empty it means we must merge with block of free starting at 0
            if (this.FREE.remove(addr << ADDR_BITS)) {//Attempt to remove block 0
                slot = addr + size;//slot at address 0 and size of 0 block + new block
            }
        }

        //If there is a next element it is guarenteed to either be the next block, or indicate that there is
        // a block that needs to be merged into
        if (iter.hasNext()) {
            long nextSlot = iter.nextLong();
            long endAddr = (slot >>> SIZE_BITS) + (slot & SIZE_MSK);
            if (endAddr != nextSlot >>> SIZE_BITS) {//It means there is a memory block to be merged in FREE
                long delta = ((nextSlot >>> SIZE_BITS) - endAddr);
                this.FREE.remove((delta << ADDR_BITS) | endAddr);
                slot = (slot & (ADDR_MSK << SIZE_BITS)) | ((slot & SIZE_MSK) + delta);
            }
        } else {// if there is no next block it means that we have reached the end of the allocation sections and we can shrink the buffer
            this.totalSize -= (slot & SIZE_MSK);
            return (int) size;
        }

        //Need to swap around the slot to be in FREE format
        slot = (slot >>> SIZE_BITS) | (slot << ADDR_BITS);
        this.FREE.add(slot);//Add the free slot into segments
        return (int) size;
    }

    public long getSize(long addr) {
        addr &= ADDR_MSK;
        var iter = this.TAKEN.iterator(addr << SIZE_BITS);
        if (!iter.hasNext())
            throw new IllegalArgumentException();
        long slot = iter.nextLong();
        if (slot >> SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        return slot & SIZE_MSK;
    }

    public void setLimit(long size) {
        this.sizeLimit = size;
        if (this.sizeLimit < this.totalSize) {
            throw new IllegalStateException("Size set smaller than current size");
        }
    }

    public long getLimit() {
        return this.sizeLimit;
    }
}
