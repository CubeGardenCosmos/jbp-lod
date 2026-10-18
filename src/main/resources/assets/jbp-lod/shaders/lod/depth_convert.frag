// JBP-LOD original, equivalent of upstream voxy post/setup_stencil_depth.frag: seeds the LOD
// depth/stencil target from the game's previous-frame depth. Vanilla pixels get NEAR depth and
// stencil 0; sky pixels (game depth == FAR) are discarded and keep the cleared FAR depth and
// stencil 1, where LOD geometry is drawn.
layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D gameDepth;
// std140: depthPlanes = (gameFar, lodNear) in the game's depth convention: standard FAR=1 NEAR=0,
// reverse-Z FAR=0 NEAR=1.
layout(set = 0, binding = 1, std140) uniform DepthPlanesBlock {
    vec2 depthPlanes;
} Convert;

void main() {
    float depth = texture(gameDepth, uv).r;
    if (depth == Convert.depthPlanes.x) discard;
    gl_FragDepth = Convert.depthPlanes.y;
}
