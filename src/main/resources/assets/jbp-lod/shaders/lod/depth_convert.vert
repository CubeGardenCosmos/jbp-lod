// JBP-LOD original: full-screen triangle for the game-depth to LOD-projection depth conversion pass.
// Conceptually replaces upstream MCRcortex/voxy 263 post/depth_cp.* (GL framebuffer depth copy).
layout(location = 0) out vec2 uv;

void main() {
    uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
