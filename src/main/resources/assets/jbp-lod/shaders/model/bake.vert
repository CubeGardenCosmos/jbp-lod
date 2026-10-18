layout(location = 0) in vec3 position;
layout(location = 1) in vec2 atlasUv;
layout(location = 2) in uint vertexMeta;

layout(push_constant) uniform BakeFace {
    mat4 faceMvp;
};

layout(location = 0) out vec2 outAtlasUv;
layout(location = 1) flat out uint outVertexMeta;

void main() {
    vec4 clip = faceMvp * vec4(position, 1.0);
    // Voxy's six-view projection uses OpenGL's [-1, 1] clip-space depth. Vulkan's
    // dynamic-rendering depth attachment uses [0, 1], so remap only Z here.
    clip.z = (clip.z + clip.w) * 0.5;
    gl_Position = clip;
    outAtlasUv = atlasUv;
    outVertexMeta = vertexMeta;
}
