package cn.cubegarden.cosmos.jbp.client.lod.data.tree;

/**
 * Converts one stored upstream-format section into packed-quad geometry plus its child-existence mask.
 * Implementations run on mesh worker threads and must not touch Minecraft/Vulkan state owned by the game
 * threads.
 */
@FunctionalInterface
public interface SectionMesher {
    /**
     * @param position      packed section position ({@link SectionPos})
     * @param storedSection raw stored section bytes, or null when no data is stored for the position
     * @return a result for the position (empty sections are valid); never null
     */
    BuiltSection mesh(long position, byte[] storedSection);
}
