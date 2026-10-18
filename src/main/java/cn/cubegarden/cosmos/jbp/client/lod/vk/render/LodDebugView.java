package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import java.util.Locale;
import java.util.Optional;

/**
 * Opt-in diagnostic colour views of LOD quad fragments, selected with {@code -Djbp.lod.debugView=<name>}.
 * Each non-{@link #OFF} view replaces the shaded fragment colour with a per-fragment attribute so
 * geometry, UV, face, model and depth problems can be told apart on screen. The fragment shader selects
 * the view through the {@code LOD_DEBUG_VIEW} define carrying {@link #shaderCode()}.
 */
public enum LodDebugView {
    OFF(0),
    /** Face normal: axis direction mapped to RGB (n * 0.5 + 0.5). */
    NORMAL(1),
    /** Tile-local UV: fract(uv) in red/green. */
    UV(2),
    /** Quad face index (0..5) as a categorical colour. */
    FACE(3),
    /** Block model id as a categorical colour. */
    MODEL(4),
    /** Raw fragment depth (gl_FragCoord.z) as grey. */
    DEPTH(5);

    public static final String PROPERTY = "jbp.lod.debugView";

    private final int shaderCode;

    LodDebugView(int shaderCode) {
        this.shaderCode = shaderCode;
    }

    /** Value of the {@code LOD_DEBUG_VIEW} shader define; 0 means no debug view. */
    public int shaderCode() {
        return shaderCode;
    }

    /**
     * Accepts names case-insensitively. Null, blank or {@code off} yield {@link #OFF}; unknown names yield
     * empty so the caller can report them.
     */
    public static Optional<LodDebugView> parse(String raw) {
        if (raw == null) {
            return Optional.of(OFF);
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("off")) {
            return Optional.of(OFF);
        }
        for (LodDebugView view : values()) {
            if (view != OFF && view.name().toLowerCase(Locale.ROOT).equals(name)) {
                return Optional.of(view);
            }
        }
        return Optional.empty();
    }
}
