package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;

/**
 * Vanilla fog handling for far LoD terrain.
 *
 * <p>Without this, Sodium's vanilla environmental/render-distance fog (which is tuned to hide the edge of
 * the loaded chunk grid) is applied to LoD fragments as well. Every stored section beyond that edge then
 * blends to the fog colour, which is the sky colour at the horizon, so distant terrain looks "missing".
 * Sealed upstream Voxy removes the same fog in {@code MixinFogRenderer} while its renderer is active.
 */
public final class LodFogPolicy {
    private LodFogPolicy() {
    }

    /**
     * @param renderEnabled {@code -Djbp.lod.render} is not false
     * @param runtimeActive a scope runtime is published and may draw
     * @return true when vanilla environmental and render-distance fog must be pushed out of range
     */
    public static boolean shouldExtendLodFog(boolean renderEnabled, boolean runtimeActive) {
        return renderEnabled && runtimeActive;
    }

    /**
     * Close-range environmental fog (underwater, lava, custom dimensions) stays untouched, matching
     * upstream's {@code environmentalEnd < 10} guard.
     */
    public static boolean shouldExtendEnvironmentalFog(float environmentalEnd) {
        return environmentalEnd >= 10.0f;
    }

    /**
     * Fog block the LoD shader is allowed to use.
     *
     * <p>The extension policy above makes vanilla (and Sodium) fog ranges enormous while the LoD draws, and
     * {@code quads.frag}'s {@code applyLodFog} treats a zero/negative range as "no fog". Relying on the
     * captured Sodium {@code FogParameters} alone made the LoD shader inherit the vanilla distance fog on
     * frames where the capture still held the unextended range (for example the first frame after the
     * runtime becomes active, or a build whose mixin ordering differs): every distant translucent LoD quad
     * then blended to the sky colour and showed as a pale rectangle over the terrain. While the extension is
     * active the LoD must therefore be explicitly unfogged instead of merely relying on the captured range.
     *
     * @param captured the frame's captured LoD fog block (environmental range and colour)
     * @return {@link cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets.Fog#disabled()} when the
     *         extension policy is active, otherwise {@code captured}
     */
    public static LodFrameTargets.Fog lodFog(boolean renderEnabled, boolean runtimeActive,
                                             LodFrameTargets.Fog captured) {
        return shouldExtendLodFog(renderEnabled, runtimeActive)
                ? LodFrameTargets.Fog.disabled() : captured;
    }
}
