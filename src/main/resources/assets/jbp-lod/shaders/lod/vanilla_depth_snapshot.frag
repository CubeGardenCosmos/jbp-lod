// JBP-LOD original: preserve vanilla depth exactly, without HiZ reduction or depth remapping.
layout(set = 0, binding = 0) uniform sampler2D gameDepth;

void main() {
    gl_FragDepth = texelFetch(gameDepth, ivec2(gl_FragCoord.xy), 0).r;
}
