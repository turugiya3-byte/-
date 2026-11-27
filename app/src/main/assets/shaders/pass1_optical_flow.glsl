#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexPrev;
uniform sampler2D uTexCurr;
uniform vec2 uTexelSize;

float getLuma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

float sampleSmoothLuma(sampler2D tex, vec2 uv) {
    vec2 dx = vec2(uTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uTexelSize.y);
    float c  = getLuma(texture(tex, uv).rgb) * 4.0;
    float n  = getLuma(texture(tex, uv - dy).rgb) * 2.0;
    float s  = getLuma(texture(tex, uv + dy).rgb) * 2.0;
    float w  = getLuma(texture(tex, uv - dx).rgb) * 2.0;
    float e  = getLuma(texture(tex, uv + dx).rgb) * 2.0;
    float nw = getLuma(texture(tex, uv - dx - dy).rgb);
    float ne = getLuma(texture(tex, uv + dx - dy).rgb);
    float sw = getLuma(texture(tex, uv - dx + dy).rgb);
    float se = getLuma(texture(tex, uv + dx + dy).rgb);
    return (c + n + s + w + e + nw + ne + sw + se) / 16.0;
}

void main() {
    vec2 stepX = vec2(uTexelSize.x * 2.0, 0.0);
    vec2 stepY = vec2(0.0, uTexelSize.y * 2.0);

    float lumaPrev = sampleSmoothLuma(uTexPrev, vTexCoord);
    float lumaCurr = sampleSmoothLuma(uTexCurr, vTexCoord);
    float It = lumaCurr - lumaPrev;

    float IxPrev = (sampleSmoothLuma(uTexPrev, vTexCoord + stepX) - sampleSmoothLuma(uTexPrev, vTexCoord - stepX)) * 0.5;
    float IyPrev = (sampleSmoothLuma(uTexPrev, vTexCoord + stepY) - sampleSmoothLuma(uTexPrev, vTexCoord - stepY)) * 0.5;

    float IxCurr = (sampleSmoothLuma(uTexCurr, vTexCoord + stepX) - sampleSmoothLuma(uTexCurr, vTexCoord - stepX)) * 0.5;
    float IyCurr = (sampleSmoothLuma(uTexCurr, vTexCoord + stepY) - sampleSmoothLuma(uTexCurr, vTexCoord - stepY)) * 0.5;

    float Ix = (IxPrev + IxCurr) * 0.5;
    float Iy = (IyPrev + IyCurr) * 0.5;

    float gradMagSq = Ix * Ix + Iy * Iy;
    float denom = gradMagSq + 0.015;

    vec2 flow = -It * vec2(Ix, Iy) / denom;
    vec2 maxFlow = uTexelSize * 48.0;
    flow = clamp(flow, -maxFlow, maxFlow);

    float confidence = clamp(gradMagSq / (gradMagSq + 0.005), 0.0, 1.0);
    fragColor = vec4(flow, confidence, 1.0);
}
