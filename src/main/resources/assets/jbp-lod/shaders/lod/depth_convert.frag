// JBP-LOD original: re-expresses the game's (previous-frame) depth buffer in the LOD projection's
// depth range so the HiZ pyramid and the visibility raster can be built on the same device without
// readback. Conceptually replaces upstream MCRcortex/voxy 263 post/depth_cp.frag.
layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D gameDepth;
// std140: gameParams = (-P22, P32, zeroToOne, 0) of the game's projection, lodParams the same for
// the LOD projection. Depth = -P22 + P32 / viewDistance for [0,1] clip depth.
layout(set = 0, binding = 1, std140) uniform DepthConvertBlock {
    vec4 gameParams;
    vec4 lodParams;
} Convert;

void main() {
    float depth = texture(gameDepth, uv).r;
    float ndc = Convert.gameParams.z > 0.5 ? depth : depth * 2.0 - 1.0;
    float denominator = ndc - Convert.gameParams.x;
    float viewDistance = abs(denominator) < 1e-9 ? 3.0e38 : Convert.gameParams.y / denominator;
    if (!(viewDistance > 0.0)) {
        viewDistance = 3.0e38;
    }
    float lodNdc = Convert.lodParams.x + Convert.lodParams.y / viewDistance;
    gl_FragDepth = clamp(Convert.lodParams.z > 0.5 ? lodNdc : lodNdc * 0.5 + 0.5, 0.0, 1.0);
}
