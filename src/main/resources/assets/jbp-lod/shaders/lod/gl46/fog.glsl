// JBP-LOD Vulkan fog/depth ABI, populated from the current frame's Sodium FogParameters and
// chunk projection (std140, 112 bytes).
//   modelView   : Sodium model-view matrix (fog distance).
//   fogColor    : rgb + alpha strength.
//   fogRange    : x = environmental start, y = environmental end.
//   depthParams : x = -P22, y = P32 of Sodium's chunk projection, z = 1 for [0,1] clip depth.
//                 Converts the LOD projection's linear view distance (clip w) to the depth value
//                 Sodium wrote, so LOD fragments depth-test correctly against the game depth buffer.
layout(set = 0, binding = 8, std140) uniform LodFogBlock {
    mat4 modelView;
    vec4 fogColor;
    vec4 fogRange;
    vec4 depthParams;
} FogUniform;
