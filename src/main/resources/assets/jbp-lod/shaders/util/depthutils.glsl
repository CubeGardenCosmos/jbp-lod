// Derived from MCRcortex/voxy 263: assets/voxy/shaders/util/depthutils.glsl
#ifndef UNDEFINE_DEPTH
#define UNDEFINE_DEPTH

#ifndef USE_ZERO_ONE_DEPTH
#define USE_ZERO_ONE_DEPTH
#endif

#ifdef USE_REVERSE_Z
#define REDUCTION min
#define REDUCTION2 max
#define NEAR 1.0
#define FAR 0.0
#define CLOSER_SIGN 1.0
#define DEPTH_SCALAR_COMPARE(a,b) ((a) > (b))
#define DEPTH_SCALAR_COMPARE_EQUAL(a,b) ((a) >= (b))
#else
#define REDUCTION max
#define REDUCTION2 min
#define NEAR 0.0
#define FAR 1.0
#define CLOSER_SIGN -1.0
#define DEPTH_SCALAR_COMPARE(a,b) ((a) < (b))
#define DEPTH_SCALAR_COMPARE_EQUAL(a,b) ((a) <= (b))
#endif

#ifdef USE_ZERO_ONE_DEPTH
vec3 NDC2SCREEN(vec3 value) { return vec3(value.xy * 0.5 + 0.5, value.z); }
vec3 SCREEN2NDC(vec3 value) { return vec3(value.xy * 2.0 - 1.0, value.z); }
float NDC2SCREEN_DEPTH(float value) { return value; }
float SCREEN2NDC_DEPTH(float value) { return value; }
#define NDC_FAR FAR
#else
vec3 NDC2SCREEN(vec3 value) { return value * 0.5 + 0.5; }
vec3 SCREEN2NDC(vec3 value) { return value * 2.0 - 1.0; }
float NDC2SCREEN_DEPTH(float value) { return value * 0.5 + 0.5; }
float SCREEN2NDC_DEPTH(float value) { return value * 2.0 - 1.0; }
#define NDC_FAR (FAR * 2.0 - 1.0)
#endif

#else
#undef UNDEFINE_DEPTH
#undef REDUCTION
#undef REDUCTION2
#undef NEAR
#undef FAR
#undef NDC_FAR
#undef CLOSER_SIGN
#undef DEPTH_SCALAR_COMPARE
#undef DEPTH_SCALAR_COMPARE_EQUAL
#undef USE_ZERO_ONE_DEPTH
#endif
