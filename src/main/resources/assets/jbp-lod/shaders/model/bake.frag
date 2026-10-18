layout(set = 0, binding = 0) uniform sampler2D blockAtlas;

layout(location = 0) in vec2 inAtlasUv;
layout(location = 1) flat in uint inVertexMeta;
layout(location = 0) out vec4 outColour;
layout(location = 1) out vec4 outRasterInfo;

void main() {
    vec4 sampled = textureLod(blockAtlas, inAtlasUv, 0.0);
    if ((inVertexMeta & 1u) != 0u && sampled.a <= 0.0) {
        discard;
    }
    outColour = sampled;
    // r: pixel written, g: tinted pixel (vertex meta bit 2)
    outRasterInfo = vec4(1.0, (inVertexMeta & 4u) != 0u ? 1.0 : 0.0, 0.0, 0.0);
}
