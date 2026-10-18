package cn.cubegarden.cosmos.jbp.client.lod.model;

/** Thrown when a new baked model would need an ID beyond the 256x256 atlas capacity of 65536 tiles. */
public final class ModelAtlasFullException extends IllegalStateException {
    public ModelAtlasFullException() {
        super("the 256x256 LOD model atlas is full");
    }
}
