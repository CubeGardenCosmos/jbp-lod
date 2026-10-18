// Derived from MCRcortex/voxy 263: assets/voxy/shaders/hiz/blit.fsh
#import <jbp-lod:util/depthutils.glsl>

layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D depthTex;
#ifdef OUTPUT_COLOUR
layout(location = 0) out vec4 colour;
#endif

void main() {
    vec4 depths = textureGather(depthTex, uv, 0);
    bvec4 isFar = equal(vec4(FAR), depths);
    if (any(isFar)) {
        depths = mix(vec4(NEAR), depths, isFar);
    }
    float reduced = REDUCTION(REDUCTION(depths.x, depths.y), REDUCTION(depths.z, depths.w));

#ifdef OUTPUT_COLOUR
    colour = vec4(reduced);
#else
    gl_FragDepth = reduced;
#endif
}
