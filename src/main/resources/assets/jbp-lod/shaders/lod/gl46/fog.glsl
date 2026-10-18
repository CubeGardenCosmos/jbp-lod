// JBP-LOD Vulkan fog ABI, populated from the current frame's Sodium FogParameters and the
// chunk model-view matrix (std140, 96 bytes). Depth needs no conversion here: LOD fragments
// write their native LOD-projection depth, the same convention as the seeded LOD depth target.
//   modelView   : Sodium model-view matrix (fog distance).
//   fogColor    : rgb + alpha strength.
//   fogRange    : x = environmental start, y = environmental end.
layout(set = 0, binding = 8, std140) uniform LodFogBlock {
    mat4 modelView;
    vec4 fogColor;
    vec4 fogRange;
} FogUniform;
