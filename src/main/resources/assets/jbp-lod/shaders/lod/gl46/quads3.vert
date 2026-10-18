// Derived from MCRcortex/voxy 263: assets/voxy/shaders/lod/gl46/quads3.vert

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#ifndef LIGHTING_SAMPLER_BINDING
#define LIGHTING_SAMPLER_BINDING 7
#endif

#import <jbp-lod:lod/quad_format.glsl>
#import <jbp-lod:lod/block_model.glsl>
#import <jbp-lod:lod/gl46/bindings.glsl>
#import <jbp-lod:lod/quad_util.glsl>
#import <jbp-lod:lod/gl46/fog.glsl>


layout(location=0) flat out uvec4 interData;
layout(location=1) out vec2 uv;
layout(location=2) out float fogDistance;


#ifdef TAA_PATCH
vec2 taaShift();
#endif

void main() {
    #ifdef TAA_PATCH
    taaOffset = taaShift();
    #else
    taaOffset = vec2(0.0);
    #endif

    QuadData quad;
    uvec2 pos = positionBuffer[gl_InstanceIndex];
    setupQuad(quad, quadData[uint(gl_VertexIndex)>>2], pos, (gl_VertexIndex&3)==1);

    uint cornerId = uint(gl_VertexIndex)&3u;

    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);
    vec2 cornerMask = vec2((cornerId >> 1) & 1u, cornerId & 1u) * quad.lodScale;
    vec3 cornerPoint = quad.basePoint
            + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * cornerMask, 0.0));
    // Match Voxy's spherical environmental fog distance in view space.
    fogDistance = length((FogUniform.modelView * vec4(cornerPoint, 1.0)).xyz);


    interData = quad.attributeData;
}
