// Derived from MCRcortex/voxy 263: assets/voxy/shaders/hiz/blit.fsh
#import <jbp-lod:util/depthutils.glsl>

layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D depthTex;
#ifdef OUTPUT_COLOUR
layout(location = 0) out vec4 colour;
#endif

void main() {
    // No FAR->NEAR hole patch: FAR texels (sky, or unwritten LOD depth) stay the farthest value, so a tile over
    // sky is never reported as a nearer occluder. Matches hiz.comp, which reduces without patching.
    vec4 depths = textureGather(depthTex, uv, 0);
    float reduced = REDUCTION(REDUCTION(depths.x, depths.y), REDUCTION(depths.z, depths.w));

#ifdef OUTPUT_COLOUR
    colour = vec4(reduced);
#else
    gl_FragDepth = reduced;
#endif
}
