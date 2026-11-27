#version 300 es
#extension GL_OES_EGL_image_external_essl3 : enable
#extension GL_OES_EGL_image_external : enable
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform samplerExternalOES uOesTex;
uniform mat4 uTexMatrix;

void main() {
    vec2 transformedUv = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
    fragColor = texture(uOesTex, transformedUv);
}
