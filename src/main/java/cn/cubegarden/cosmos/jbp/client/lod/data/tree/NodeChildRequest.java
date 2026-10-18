package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Pending expansion of one node into its child sections.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/hierachical/NodeChildRequest.java}; logic unchanged.
 */
final class NodeChildRequest {
    private final long nodePos;

    private final int[] childStates = new int[]{-1, -1, -1, -1, -1, -1, -1, -1};
    private final byte[] childChildExistence = new byte[8];

    private byte results;
    private byte mask;
    private byte existenceMask = 0;

    NodeChildRequest(long nodePos) {
        this.nodePos = nodePos;
    }

    public int getChildMesh(int childIdx) {
        if ((this.mask & (1 << childIdx)) == 0) {
            throw new IllegalStateException("Tried getting mesh result of child not in mask");
        }
        return this.childStates[childIdx];
    }

    public void setChildChildExistence(int childIdx, byte childExistence) {
        if ((this.mask & (1 << childIdx)) == 0) {
            throw new IllegalStateException("Tried setting child child existence in request when child isnt in mask");
        }
        this.childChildExistence[childIdx] = childExistence;
        this.existenceMask |= (byte) (1 << childIdx);
    }

    public boolean hasChildChildExistence(int childId) {
        if ((this.mask & (1 << childId)) == 0) {
            throw new IllegalStateException("Tried getting child child existence set of child not in mask");
        }
        return (this.existenceMask & (1 << childId)) != 0;
    }

    public byte getChildChildExistence(int childIdx) {
        if (!this.hasChildChildExistence(childIdx)) {
            throw new IllegalStateException("Tried getting child child existence when child child existence for child was not set");
        }
        return this.childChildExistence[childIdx];
    }

    public int setChildMesh(int childIdx, int mesh) {
        if ((this.mask & (1 << childIdx)) == 0) {
            throw new IllegalStateException("Tried setting child mesh when child isnt in mask");
        }
        //Note the mesh can be -ve meaning empty mesh, but we should still mark that node as having a result
        boolean isFirstInsert = (this.results & (1 << childIdx)) == 0;
        this.results |= (byte) (1 << childIdx);

        int prev = this.childStates[childIdx];
        this.childStates[childIdx] = mesh;
        return isFirstInsert ? -1 : prev;
    }

    public int removeAndUnRequire(int childIdx) {
        byte msk = (byte) (1 << childIdx);
        if ((this.mask & msk) == 0) {
            throw new IllegalStateException("Tried removing and unmasking child that was never masked");
        }
        byte prev = this.results;
        this.results &= (byte) ~msk;
        this.mask &= (byte) ~msk;
        this.existenceMask &= (byte) ~msk;
        int mesh = this.childStates[childIdx];
        this.childStates[childIdx] = -1;
        return (prev & msk) == 0 ? -1 : mesh;
    }

    public void addChildRequirement(int childIdx) {
        byte msk = (byte) (1 << childIdx);
        if ((this.mask & msk) != 0) {
            throw new IllegalStateException("Child already required!");
        }
        this.mask |= msk;
    }

    public boolean isSatisfied() {
        return (this.results & this.mask) == this.mask;
    }

    public long getPosition() {
        return this.nodePos;
    }

    public byte getMsk() {
        return this.mask;
    }

    /** Bit mask of required children whose mesh result has not arrived yet. */
    public int missingMask() {
        return (this.mask & ~this.results) & 0xFF;
    }
}
