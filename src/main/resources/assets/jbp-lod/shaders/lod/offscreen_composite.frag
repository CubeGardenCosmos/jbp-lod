// JBP-LOD original Vulkan equivalent of upstream NormalRenderPipeline.finish /
// AbstractRenderPipeline.transformBlitDepth: reproject LOD depth and depth-test against
// the current game attachment, rather than trusting the previous-frame depth seed.
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;
layout(set = 0, binding = 0) uniform sampler2D lodColor;
layout(set = 0, binding = 1) uniform sampler2D lodDepth;
layout(set = 0, binding = 2, std140) uniform CompositeDepthBlock {
    vec4 gameParams;
    vec4 lodParams;
} Convert;

void main() {
    vec4 accumulated = texture(lodColor, uv);
    if (accumulated.a <= 0.0) discard;
    float depth = texture(lodDepth, uv).r;
    float lodNdc = Convert.lodParams.z > 0.5 ? depth : depth * 2.0 - 1.0;
    float denominator = lodNdc - Convert.lodParams.x;
    float distance = abs(denominator) < 1e-9 ? 3.0e38 : Convert.lodParams.y / denominator;
    if (!(distance > 0.0)) discard;
    float gameNdc = Convert.gameParams.x + Convert.gameParams.y / distance;
    gl_FragDepth = clamp(Convert.gameParams.z > 0.5 ? gameNdc : gameNdc * 0.5 + 0.5, 0.0, 1.0);
    // Geometry uses SRC_ALPHA / ONE_MINUS_SRC_ALPHA onto transparent black. Its stored
    // aggregate is premultiplied; recover straight alpha for the game's ALPHA pipeline.
    color = vec4(accumulated.rgb / accumulated.a, accumulated.a);
}
