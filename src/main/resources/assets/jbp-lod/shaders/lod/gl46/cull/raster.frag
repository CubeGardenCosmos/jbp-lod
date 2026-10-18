// Derived from MCRcortex/voxy 263: assets/voxy/shaders/lod/gl46/cull/raster.frag
#define VISIBILITY_BUFFER_BINDING 2
layout(set=0, binding=VISIBILITY_BUFFER_BINDING, std430) restrict buffer VisibilityBuffer {
    uint visibilityData[];
};

layout(early_fragment_tests) in;

layout(location=0) flat in uint id;
layout(location=1) flat in uint value;

void main() {
    visibilityData[id] = value;
}
