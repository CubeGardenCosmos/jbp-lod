// Derived from MCRcortex/voxy 263: assets/voxy/shaders/chunkoutline/outline.vsh
// Rasterises one 16^3 cube per built, in-range Sodium section. The LOD draw gate samples the
// farthest of these faces (see bounding.frag), so LOD fragments inside the loaded-chunk volume are
// discarded in quads.frag.

#import <jbp-lod:util/depthutils.glsl>

layout(set = 0, binding = 0, std140) uniform BoundingUniform {
    mat4 MVP;
    // Camera block position (xyz) of the current frame.
    ivec4 cameraBlockPos;
    // xyz: camera fractional offset from cameraBlockPos; w: render distance in blocks.
    vec4 negInnerBlock;
};

layout(set = 0, binding = 1, std430) readonly restrict buffer BoundingSectionBuffer {
    // Packed SectionPos.asLong(x, y, z): low int first, high int second.
    ivec2 sectionPos[];
};

// Cube vertex order: 12 triangles over the 8 corners, corner bits (x = bit0, z = bit1, y = bit2).
const int CUBE_CORNERS[36] = int[36](
    0, 2, 6, 0, 6, 4,
    1, 3, 7, 1, 7, 5,
    0, 1, 3, 0, 3, 2,
    4, 5, 7, 4, 7, 6,
    0, 1, 5, 0, 5, 4,
    2, 3, 7, 2, 7, 6
);

ivec3 unpackSectionPos(ivec2 pos) {
    return ivec3(pos.y >> 10, (pos.x << 12) >> 12, ((pos.y << 22) | int(uint(pos.x) >> 10)) >> 10);
}

void main() {
    int corner = CUBE_CORNERS[gl_VertexIndex];
    ivec3 cornerBits = ivec3(corner & 1, (corner >> 2) & 1, (corner >> 1) & 1);

    ivec3 origin = unpackSectionPos(sectionPos[gl_InstanceIndex]) * 16 - cameraBlockPos.xyz;

    // Nearest point of the section box to the camera (expanded by one block), camera-relative.
    ivec3 nearest = min(max(origin - 1, ivec3(0)), origin + 17);
    vec3 nearestRelative = vec3(nearest) - negInnerBlock.xyz;
    float radius = negInnerBlock.w;
    if (nearestRelative.x * nearestRelative.x + nearestRelative.z * nearestRelative.z > radius * radius
            || abs(nearestRelative.y) > radius) {
        gl_Position = vec4(-100.0, -100.0, -100.0, 0.0);
        return;
    }

    vec3 position = vec3(origin + cornerBits * 16) - negInnerBlock.xyz;
    gl_Position = MVP * vec4(position, 1.0);
    gl_Position.z += CLOSER_SIGN * 0.0005;
}
