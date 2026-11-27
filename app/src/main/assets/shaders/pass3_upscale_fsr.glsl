#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uInputTex;
uniform sampler2D uOriginalTex;
uniform vec2 uInputTexelSize;
uniform vec2 uOutputResolution;
uniform float uSharpness;
uniform float uSplitPos;
uniform int uEnableUpscale;

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec3 applyFsrUpscale(vec2 uv) {
    vec2 dx = vec2(uInputTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uInputTexelSize.y);

    vec3 c  = texture(uInputTex, uv).rgb;
    vec3 n  = texture(uInputTex, uv - dy).rgb;
    vec3 s  = texture(uInputTex, uv + dy).rgb;
    vec3 w  = texture(uInputTex, uv - dx).rgb;
    vec3 e  = texture(uInputTex, uv + dx).rgb;

    float lC = getLuma(c);
    float lN = getLuma(n);
    float lS = getLuma(s);
    float lW = getLuma(w);
    float lE = getLuma(e);

    float gradH = abs(lW - lC) + abs(lE - lC);
    float gradV = abs(lN - lC) + abs(lS - lC);
    float edgeDir = clamp((gradV - gradH) / (gradV + gradH + 0.0001), -1.0, 1.0);

    vec3 horizAvg = (w + e) * 0.5;
    vec3 vertAvg  = (n + s) * 0.5;
    vec3 edgeSmooth = mix(vertAvg, horizAvg, edgeDir * 0.5 + 0.5);
    vec3 baseColor = mix(c, edgeSmooth, 0.25);

    float minL = min(lC, min(min(lN, lS), min(lW, lE)));
    float maxL = max(lC, max(max(lN, lS), max(lW, lE)));

    float hitMin = minL / max(4.0 * maxL, 0.0001);
    float hitMax = (1.0 - maxL) / max(4.0 * minL - 4.0, -0.0001);
    float lobe = max(-hitMin, hitMax);

    float sharpWeight = clamp(lobe, -0.18 * uSharpness, 0.0);
    vec3 sharpColor = (baseColor + sharpWeight * (n + s + w + e)) / (1.0 + 4.0 * sharpWeight);

    vec3 minRGB = min(c, min(min(n, s), min(w, e)));
    vec3 maxRGB = max(c, max(max(n, s), max(w, e)));
    return clamp(sharpColor, minRGB, maxRGB);
}

void main() {
    if (vTexCoord.x < uSplitPos) {
        fragColor = texture(uOriginalTex, vTexCoord);
    } else {
        vec3 result = (uEnableUpscale == 1) ? applyFsrUpscale(vTexCoord) : texture(uInputTex, vTexCoord).rgb;
        fragColor = vec4(result, 1.0);
    }

    if (uSplitPos > 0.005 && uSplitPos < 0.995) {
        float distPx = abs(vTexCoord.x - uSplitPos) * uOutputResolution.x;
        if (distPx < 1.5) {
            fragColor = vec4(1.0, 1.0, 1.0, 0.85);
        }
    }
}
