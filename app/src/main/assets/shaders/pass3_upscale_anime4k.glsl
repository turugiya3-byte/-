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

vec3 applyAnime4KUpscale(vec2 uv) {
    vec2 dx = vec2(uInputTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uInputTexelSize.y);

    vec3 c  = texture(uInputTex, uv).rgb;
    vec3 n  = texture(uInputTex, uv - dy).rgb;
    vec3 s  = texture(uInputTex, uv + dy).rgb;
    vec3 w  = texture(uInputTex, uv - dx).rgb;
    vec3 e  = texture(uInputTex, uv + dx).rgb;
    vec3 nw = texture(uInputTex, uv - dx - dy).rgb;
    vec3 ne = texture(uInputTex, uv + dx - dy).rgb;
    vec3 sw = texture(uInputTex, uv - dx + dy).rgb;
    vec3 se = texture(uInputTex, uv + dx + dy).rgb;

    float lC  = getLuma(c);
    float lN  = getLuma(n);
    float lS  = getLuma(s);
    float lW  = getLuma(w);
    float lE  = getLuma(e);
    float lNW = getLuma(nw);
    float lNE = getLuma(ne);
    float lSW = getLuma(sw);
    float lSE = getLuma(se);

    float gx = (lNE + 2.0 * lE + lSE) - (lNW + 2.0 * lW + lSW);
    float gy = (lSW + 2.0 * lS + lSE) - (lNW + 2.0 * lN + lNE);
    vec2 grad = vec2(gx, gy);
    float gradLen = length(grad);

    if (gradLen < 0.03) {
        return c;
    }

    vec2 gradDir = grad / gradLen;
    float thinStrength = uSharpness * 0.55 * clamp(gradLen * 2.5, 0.0, 1.0);
    vec2 warpOffset = gradDir * uInputTexelSize * thinStrength;
    vec3 thinnedColor = texture(uInputTex, uv + warpOffset).rgb;

    vec2 tangentDir = vec2(-gradDir.y, gradDir.x);
    vec3 tangentSample1 = texture(uInputTex, uv + tangentDir * uInputTexelSize * 0.5).rgb;
    vec3 tangentSample2 = texture(uInputTex, uv - tangentDir * uInputTexelSize * 0.5).rgb;
    vec3 aaColor = mix(thinnedColor, (tangentSample1 + tangentSample2) * 0.5, 0.35);

    vec3 minRGB = min(c, min(min(n, s), min(w, e)));
    vec3 maxRGB = max(c, max(max(n, s), max(w, e)));

    vec3 unsharp = aaColor + (aaColor - (n + s + w + e) * 0.25) * (uSharpness * 0.5);
    return clamp(unsharp, minRGB, maxRGB);
}

void main() {
    if (vTexCoord.x < uSplitPos) {
        fragColor = texture(uOriginalTex, vTexCoord);
    } else {
        vec3 result = (uEnableUpscale == 1)
            ? applyAnime4KUpscale(vTexCoord)
            : texture(uInputTex, vTexCoord).rgb;
        fragColor = vec4(result, 1.0);
    }

    if (uSplitPos > 0.005 && uSplitPos < 0.995) {
        float distPx = abs(vTexCoord.x - uSplitPos) * uOutputResolution.x;
        if (distPx < 1.5) {
            fragColor = vec4(1.0, 1.0, 1.0, 0.85);
        }
    }
}
