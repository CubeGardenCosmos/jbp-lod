// Derived from MCRcortex/voxy 263: assets/voxy/shaders/lod/gl46/quads.frag

layout(location=0) flat in uvec4 interData;
layout(location=1) in vec2 uv;
layout(location=2) in float fogDistance;


#ifndef PATCHED_SHADER
layout(location=0) out vec4 outColour;
#else
#define MODEL_BUFFER_BINDING 3
#import <jbp-lod:lod/block_model.glsl>
#endif

#import <jbp-lod:lod/gl46/bindings.glsl>
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

    #ifndef PATCHED_SHADER
    #ifdef DEBUG_SOLID
    outColour = vec4(1.0, 0.0, 1.0, 1.0);
    #else
    colour = computeColour(texPos, colour);
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

