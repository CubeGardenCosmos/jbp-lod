// Derived from MCRcortex/voxy 263: assets/voxy/shaders/lod/gl46/quads.frag

layout(location=0) flat in uvec4 interData;
layout(location=1) in vec2 uv;
layout(location=2) in float fogDistance;
layout(location=3) flat in uint lodLevel;


#ifndef PATCHED_SHADER
layout(location=0) out vec4 outColour;
#else
#define MODEL_BUFFER_BINDING 3
#import <jbp-lod:lod/block_model.glsl>
#endif

#import <jbp-lod:lod/gl46/bindings.glsl>
#import <jbp-lod:util/depthutils.glsl>
layout(set=0, binding=9) uniform sampler2D depthTex;
#import <jbp-lod:lod/lighting.glsl>
#import <jbp-lod:lod/gl46/fog.glsl>


vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}

uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}

#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    //TODO: pass in derivative data
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else
vec4 computeColour(vec2 texturePos, vec4 colour) {
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}
#endif

vec4 applyLodFog(vec4 sourceColour) {
    float fogRangeSize = FogUniform.fogRange.y - FogUniform.fogRange.x;
    if (FogUniform.fogColor.a > 0.0 && fogRangeSize > 0.0) {
        float fogAmount = clamp((fogDistance - FogUniform.fogRange.x) / fogRangeSize, 0.0, 1.0)
                * FogUniform.fogColor.a;
        sourceColour.rgb = mix(sourceColour.rgb, FogUniform.fogColor.rgb, fogAmount);
    }
    return sourceColour;
}


// Diagnostic colours selected by LOD_DEBUG_VIEW (LodDebugView.java; codes must match):
// 1 normal, 2 uv, 3 face, 4 model, 5 depth, 6 level, 7 pass, 8 bound, 9 stencil.
// Output replaces shading/fog. Only bound bypasses bounding-depth discard; all other tests remain.
#ifdef LOD_DEBUG_VIEW
vec3 categorical(float t) {
    return 0.5 + 0.5 * cos(6.2831853 * (vec3(t) + vec3(0.0, 0.33, 0.67)));
}

vec3 lodDebugColour(bool outsideBound, vec2 texPos) {
    if (LOD_DEBUG_VIEW == 1) {
        uint face = getFace();
        uint axis = face >> 1u;
        float side = (face & 1u) != 0u ? 1.0 : -1.0;
        vec3 normal = vec3(axis == 0u ? side : 0.0, axis == 1u ? side : 0.0, axis == 2u ? side : 0.0);
        return normal * 0.5 + 0.5;
    }
    if (LOD_DEBUG_VIEW == 2) {
        return vec3(fract(uv), 0.0);
    }
    if (LOD_DEBUG_VIEW == 3) {
        return categorical(float(getFace()) / 8.0);
    }
    if (LOD_DEBUG_VIEW == 4) {
        return categorical(fract(float(getModelId()) * 0.6180339887));
    }
    if (LOD_DEBUG_VIEW == 6) {
        // Decode in the vertex shader from the actual section position, not opaque-only colour bits.
        return categorical(float(lodLevel) / 8.0);
    }
    #if LOD_DEBUG_VIEW == 7
    // A separate fragment variant tags each actual pipeline phase, not gl_FrontFacing or material.
    #if LOD_DRAW_PASS == 0
    return vec3(1.0, 0.0, 0.0); // Previous-frame opaque.
    #elif LOD_DRAW_PASS == 1
    return vec3(0.0, 1.0, 0.0); // Current temporal opaque.
    #elif LOD_DRAW_PASS == 2
    return vec3(0.0, 0.0, 1.0); // Current translucent.
    #endif
    #endif
    if (LOD_DEBUG_VIEW == 8) {
        return outsideBound ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    }
    if (LOD_DEBUG_VIEW == 9) {
        // NOT a stencil readback: only fragments surviving fixed-function stencil/depth can appear.
        // Rejected pixels retain the underlying scene; green does not reveal the stored stencil value.
        return vec3(0.0, 1.0, 0.0);
    }
    if (LOD_DEBUG_VIEW == 10) {
        // Packed lightmap colour * directional face tint, before the atlas texture is applied.
        return uint2vec4RGBA(interData.y).rgb;
    }
    if (LOD_DEBUG_VIEW == 11) {
        // Raw atlas albedo, before lighting and fog.
        return texture(blockModelAtlas, texPos).rgb;
    }
    return vec3(gl_FragCoord.z);
}
#endif

void main() {
    vec2 tile;
    vec2 uv2 = modf(uv, tile)*(1.0/(vec2(3.0,2.0)*256.0));
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();
    {
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);
        vec2 dy = dFdy(uvSmol);
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        discard;
        return;
    }


    #ifndef TRANSLUCENT
    colour.a = 1.0f;
    if (useDiscard() && (textureLod(blockModelAtlas, texPos, 0).a <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        discard;
        return;
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    bool outsideBound = DEPTH_SCALAR_COMPARE(gl_FragCoord.z,
            texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r);
    #if !defined(LOD_DEBUG_VIEW) || LOD_DEBUG_VIEW != 8
    if (outsideBound) {
        discard;
    }
    #endif

    #ifndef PATCHED_SHADER
    #if defined(LOD_DEBUG_VIEW)
    outColour = vec4(lodDebugColour(outsideBound, texPos), 1.0);
    #elif defined(DEBUG_SOLID)
    outColour = vec4(1.0, 0.0, 1.0, 1.0);
    #else
    colour = computeColour(texPos, colour);
    #ifndef TRANSLUCENT
    // computeColour encoded face | level<<3 | AO<<6 into alpha; upstream clears it in its SSAO pass
    // (voxy ssao.comp: ocolour.w = 1.0f). This port has no SSAO pass, so reset it here, after the
    // only reader of the packed bytes, to the opaque coverage the offscreen composite expects.
    colour.a = 1.0f;
    #endif
    outColour = applyLodFog(colour);
    #endif
    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if tint function == 2
    if (tintingFunction==1) {//Partial tint
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));
    colour = applyLodFog(colour);

    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId));
    #endif
}

