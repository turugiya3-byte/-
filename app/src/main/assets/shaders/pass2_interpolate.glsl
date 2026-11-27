#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexPrev;
uniform sampler2D uTexCurr;
uniform sampler2D uFlowMap;
uniform vec2 uFlowTexelSize;
uniform float uAlpha;
uniform int uEnableInterp;

vec3 sampleSmoothedFlow(vec2 uv) {
    vec2 dx = vec2(uFlowTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uFlowTexelSize.y);

    vec3 sum = texture(uFlowMap, uv).rgb * 4.0;
    sum += texture(uFlowMap, uv - dx).rgb * 2.0;
    sum += texture(uFlowMap, uv + dx).rgb * 2.0;
    sum += texture(uFlowMap, uv - dy).rgb * 2.0;
    sum += texture(uFlowMap, uv + dy).rgb * 2.0;
    sum += texture(uFlowMap, uv - dx - dy).rgb;
    sum += texture(uFlowMap, uv + dx - dy).rgb;
    sum += texture(uFlowMap, uv - dx + dy).rgb;
    sum += texture(uFlowMap, uv + dx + dy).rgb;

    return sum / 16.0;
}

void main() {
    if (uEnableInterp == 0) {
        fragColor = texture(uTexCurr, vTexCoord);
        return;
    }

    vec3 flowData = sampleSmoothedFlow(vTexCoord);
    vec2 effectiveFlow = flowData.xy * flowData.z;

    vec2 uvPrev = clamp(vTexCoord - effectiveFlow * uAlpha, vec2(0.0), vec2(1.0));
    vec2 uvCurr = clamp(vTexCoord + effectiveFlow * (1.0 - uAlpha), vec2(0.0), vec2(1.0));

    vec4 colorPrev = texture(uTexPrev, uvPrev);
    vec4 colorCurr = texture(uTexCurr, uvCurr);

    float colorDiff = length(colorPrev.rgb - colorCurr.rgb);
    if (colorDiff > 0.55) {
        fragColor = (uAlpha < 0.5) ? texture(uTexPrev, vTexCoord) : texture(uTexCurr, vTexCoord);
        return;
    }

    float occlusionWeight = smoothstep(0.05, 0.35, colorDiff);
    float nearestAlpha = (uAlpha < 0.5) ? 0.0 : 1.0;
    float finalAlpha = mix(uAlpha, nearestAlpha, occlusionWeight * 0.7);

    fragColor = mix(colorPrev, colorCurr, finalAlpha);
}
