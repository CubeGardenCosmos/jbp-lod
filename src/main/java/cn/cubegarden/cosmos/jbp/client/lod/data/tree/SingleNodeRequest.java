package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Pending creation of a top-level node.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263
 * {@code client/core/rendering/hierachical/SingleNodeRequest.java}; logic unchanged.
 */
final class SingleNodeRequest {
    private final long nodePos;
    private int mesh;
    private byte childExistence;
    private int setMsk;

    SingleNodeRequest(long nodePos) {
        this.nodePos = nodePos;
        this.mesh = -1;
    }

    public void setChildExistence(byte childExistence) {
        this.setMsk |= 2;
        this.childExistence = childExistence;
    }

    public int setMesh(int mesh) {
        this.setMsk |= 1;
        int prev = this.mesh;
        this.mesh = mesh;
        return prev;
    }

    public boolean isSatisfied() {
        return this.setMsk == 3;
    }

    public long getPosition() {
        return this.nodePos;
    }

    public int getMesh() {
        return this.mesh;
    }

    public byte getChildExistence() {
        return this.childExistence;
    }

    public boolean hasChildExistenceSet() {
        return (this.setMsk & 2) != 0;
    }

    public boolean hasMeshSet() {
        return (this.setMsk & 1) != 0;
    }
}
