// Derived from MCRcortex/voxy 263: assets/voxy/shaders/lod/gl46/cull/raster.vert
#define VISIBILITY_ACCESS

#define SECTION_METADATA_BUFFER_BINDING 1
#define VISIBILITY_BUFFER_BINDING 2
#define INDIRECT_SECTION_LOOKUP_BINDING 3

#import <jbp-lod:lod/section.glsl>
#import <jbp-lod:lod/gl46/bindings.glsl>
#import <jbp-lod:util/depthutils.glsl>

layout(location=0) flat out uint id;
layout(location=1) flat out uint value;

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint sid = indirectLookup[gl_InstanceIndex];

    SectionMeta section = sectionData[sid];

    uint detail = extractDetail(section);
    ivec3 ipos = extractPosition(section);
    ivec3 aabbOffset = extractAABBOffset(section);
    ivec3 size = extractAABBSize(section);

    //Transform ipos with respect to the vertex corner
    ivec3 pos = (((ipos<<detail)-baseSectionPos)<<5);

    //TODO maybe make the size expansion 0.5 (or maybe get rid of it all together?)
    const float EXPANSION = 1.0f;

    vec3 offset = vec3(aabbOffset)-vec3(EXPANSION);
    offset += vec3(gl_VertexIndex&1, (gl_VertexIndex>>2)&1, (gl_VertexIndex>>1)&1)*(vec3(size)+2.0*EXPANSION);

    gl_Position = MVP * vec4(vec3(pos)+offset*float(1u<<detail),1.0);

    //Bring closer to camera
    gl_Position.z += (CLOSER_SIGN*0.000001f) * gl_Position.w;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;//Apply TAA if we have it
    #endif

    //Encode whether the section was visible in the previous frame for temporal rendering.
    id = sid;
    uint previous = visibilityData[sid]&0x7fffffffu;
    bool wasVisibleLastFrame = previous==(frameId-1u);
    value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);
}

#import <jbp-lod:util/depthutils.glsl>
