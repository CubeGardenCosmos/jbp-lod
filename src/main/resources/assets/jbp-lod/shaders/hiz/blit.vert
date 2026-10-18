// Derived from MCRcortex/voxy 263: assets/voxy/shaders/hiz/blit.vsh
#import <jbp-lod:util/depthutils.glsl>

layout(location = 0) out vec2 uv;

void main() {
    const vec2 corners[4] = vec2[4](
            vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
    vec2 corner = corners[uint(gl_VertexIndex) & 3u];
    uv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, NEAR, 1.0);
}
