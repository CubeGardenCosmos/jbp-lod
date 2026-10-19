package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import net.minecraft.world.level.chunk.DataLayer;

/**
 * Sky-light values for sections the light engine has no materialized {@link DataLayer} for.
 *
 * <p>The raw storage read used by the ingest snapshot ({@code LayerLightEventListener.getDataLayerData})
 * returns {@code null} for exactly the sections the engine derives light for instead of storing it. Treating
 * that {@code null} as light 0 stores every air voxel above the terrain as pitch black; the section mesher
 * then copies that light onto the adjacent terrain and water faces, which is the black-patch artifact.
 *
 * <p>This mirrors {@code SkyLightSectionStorage.getLightValue}/{@code createDataLayer} of the sealed client:
 * a position at or above the column's top light section is fully sky lit (15), and a section below the top
 * without its own data repeats the lowest layer of the nearest section above that has data. Block light has
 * no such implicit default (0 is correct). Saved region chunks omit {@code SkyLight} under the same rule, so
 * {@code importing.WorldImporter} resolves those layers with this helper too.
 */
public final class SkyLightFallback {
    public static final int FULL_SKY_LIGHT = 15;

    /** Reads the engine's view of a section's sky-light layer; {@code null} when the engine stores none. */
    @FunctionalInterface
    public interface LayerSource {
        DataLayer layer(int sectionX, int sectionY, int sectionZ);
    }

    private SkyLightFallback() {
    }

    /**
     * Whether a section may be captured without inventing light. Non-air sections always have both light
     * layers once the light engine has data for the chunk, so a missing layer means the snapshot was taken
     * before the light packet was applied; the chunk must stay dirty and be retried.
     */
    public static boolean ready(boolean onlyAir, DataLayer skyLayer, DataLayer blockLayer) {
        return onlyAir || (skyLayer != null && blockLayer != null);
    }

    /**
     * Reconstructs the sky-light layer for one section that has none in the engine's visible data map.
     * Every call returns a layer owned by the caller (safe to hand to a worker thread).
     *
     * @param maxSectionY highest section of the level, inclusive; the upward search stops above it
     */
    public static DataLayer resolve(int sectionX, int sectionY, int sectionZ, int maxSectionY,
                                    LayerSource source) {
        for (int y = sectionY + 1; y <= maxSectionY; y++) {
            DataLayer above = source.layer(sectionX, y, sectionZ);
            if (above != null) {
                return repeatFirstLayer(above);
            }
        }
        return new DataLayer(FULL_SKY_LIGHT);
    }

    /** Vanilla {@code SkyLightSectionStorage.repeatFirstLayer}: layer 0 copied into all 16 layers. */
    static DataLayer repeatFirstLayer(DataLayer source) {
        if (source.isDefinitelyHomogenous()) {
            return source.copy();
        }
        byte[] input = source.getData();
        byte[] output = new byte[DataLayer.SIZE];
        System.arraycopy(input, 0, output, 0, DataLayer.LAYER_SIZE);
        for (int layer = 1; layer < DataLayer.LAYER_COUNT; layer++) {
            System.arraycopy(output, 0, output, layer * DataLayer.LAYER_SIZE, DataLayer.LAYER_SIZE);
        }
        return new DataLayer(output);
    }
}
