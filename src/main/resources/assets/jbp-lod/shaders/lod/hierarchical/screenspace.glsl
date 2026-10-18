// Derived from MCRcortex/voxy 263: assets/voxy/shaders/lod/hierarchical/screenspace.glsl
#import <jbp-lod:util/depthutils.glsl>

layout(set = 0, binding = HIZ_BINDING) uniform sampler2D hizDepthSampler;

bool within(vec2 a, vec2 b, vec2 c) { return all(lessThan(a, b)) && all(lessThan(b, c)); }
bool within(vec3 a, vec3 b, vec3 c) { return all(lessThan(a, b)) && all(lessThan(b, c)); }
bool within(float a, float b, float c) { return a < b && b < c; }
float crossMag(vec2 a, vec2 b) { return abs(a.x * b.y - b.x * a.y); }

bool checkPointInView(vec4 point) {
    return within(vec3(-point.w, -point.w, 0.0), point.xyz, vec3(point.w));
}

vec3 _minBB = vec3(0.0);
vec3 _maxBB = vec3(0.0);
bool _frustumCulled = false;
float _screenSize = 0.0;
#ifdef TAA
vec2 getTAA();
#endif
UnpackedNode node22;

void setupScreenspace(in UnpackedNode node) {
    node22 = node;
    vec3 basePos = vec3(((node.pos << node.lodLevel) - camSecPos) << 5) - camSubSecPos;
    _frustumCulled = outsideFrustum(frustum, basePos, float(32 << node.lodLevel));
    if (_frustumCulled) return;

    vec4 p000 = MVP * vec4(basePos, 1.0);
    mat3x4 axis = mat3x4(MVP) * float(32 << node.lodLevel);
    vec4 p100 = axis[0] + p000;
    vec4 p001 = axis[2] + p000;
    vec4 p101 = axis[2] + p100;
    vec4 p010 = axis[1] + p000;
    vec4 p110 = axis[1] + p100;
    vec4 p011 = axis[1] + p001;
    vec4 p111 = axis[1] + p101;

    vec3 s000 = NDC2SCREEN(p000.xyz / p000.w);
    vec3 s100 = NDC2SCREEN(p100.xyz / p100.w);
    vec3 s001 = NDC2SCREEN(p001.xyz / p001.w);
    vec3 s101 = NDC2SCREEN(p101.xyz / p101.w);
    vec3 s010 = NDC2SCREEN(p010.xyz / p010.w);
    vec3 s110 = NDC2SCREEN(p110.xyz / p110.w);
    vec3 s011 = NDC2SCREEN(p011.xyz / p011.w);
    vec3 s111 = NDC2SCREEN(p111.xyz / p111.w);

    float screenSize = 0.0;
    vec2 a = s100.xy - s000.xy;
    vec2 b = s010.xy - s000.xy;
    vec2 c = s001.xy - s000.xy;
    screenSize += crossMag(a, b) + crossMag(a, c) + crossMag(c, b);
    a = s011.xy - s111.xy;
    b = s101.xy - s111.xy;
    c = s110.xy - s111.xy;
    screenSize += crossMag(a, b) + crossMag(a, c) + crossMag(c, b);
    _screenSize = screenSize * 0.5;

    _minBB = min(min(min(s000, s100), min(s001, s101)), min(min(s010, s110), min(s011, s111)));
    _maxBB = max(max(max(s000, s100), max(s001, s101)), max(max(s010, s110), max(s011, s111)));
#ifdef TAA
    vec2 taaValue = getTAA() * 0.5;
    _minBB.xy += taaValue;
    _maxBB.xy += taaValue;
#endif
    _minBB = clamp(_minBB, vec3(0), vec3(1));
    _maxBB = clamp(_maxBB, vec3(0), vec3(1));
}

bool outsideFrustum() { return _frustumCulled; }

bool isCulledByHiz() {
    if (any(lessThan(abs(_maxBB.xy - _minBB.xy - vec2(1.0)), vec2(0.000001)))) return false;
    ivec2 size = ivec2(packedHizSize >> 16, packedHizSize & 0xFFFF);
    vec2 pixelSize = (_maxBB.xy - _minBB.xy) * size;
    float mipLevel = floor(log2(max(max(pixelSize.x, pixelSize.y), 1.0))) - 1.0;
    mipLevel = clamp(mipLevel, 0.0, float(textureQueryLevels(hizDepthSampler) - 1));
    int mip = int(mipLevel);
    size = max(ivec2(1), size >> mip);
    ivec2 maxBB = min(ivec2(ceil(_maxBB.xy * size)), size - 1);
    ivec2 minBB = ivec2(floor(_minBB.xy * size));

    float pointSample = (NEAR * 3.0) - 1.0;
    for (int x = minBB.x; x <= maxBB.x; ++x) {
        for (int y = minBB.y; y <= maxBB.y; ++y) {
            float sampleDepth = texelFetch(hizDepthSampler, ivec2(x, y), mip).r;
            pointSample = REDUCTION(sampleDepth, pointSample);
        }
    }
#ifdef USE_REVERSE_Z
    float depthTestAgainst = _maxBB.z;
#else
    float depthTestAgainst = _minBB.z;
#endif
    return DEPTH_SCALAR_COMPARE_EQUAL(pointSample, depthTestAgainst);
}

bool shouldDecend() { return _screenSize > minSSS; }
