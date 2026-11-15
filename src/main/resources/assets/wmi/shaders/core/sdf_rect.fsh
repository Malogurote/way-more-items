#version 330
#extension GL_ARB_separate_shader_objects : require

// Way More Items : couverture anticrénelée d'un rectangle arrondi, calculée au pixel réel de l'écran.

#include <minecraft:dynamictransforms.glsl>

#ifdef TEXTURED
uniform sampler2D Sampler0;
#endif

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 localPos;
layout(location = 2) flat in vec4 shapeParams;
#ifdef TEXTURED
layout(location = 3) in vec2 texCoord0;
#endif

layout(location = 0) out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    vec2 halfSize = shapeParams.xy;
    float radius = min(shapeParams.z, min(halfSize.x, halfSize.y));
    float param = shapeParams.w;
    float d = sdRoundBox(localPos, halfSize, radius);
    // Largeur d'un pixel réel exprimée en unités d'interface : indépendante de l'échelle.
    float aa = max(fwidth(d), 0.0001);

    float outer = clamp(0.5 - d / aa, 0.0, 1.0);
    float inner = clamp(0.5 - (d + max(param, 0.0)) / aa, 0.0, 1.0);
    float soft = max(-param, 0.0001);
    float shadow = 1.0 - smoothstep(-soft, soft, d);

    float coverage = param < 0.0 ? shadow : (param > 0.0 ? outer - inner : outer);

    vec4 color = vertexColor;
#ifdef TEXTURED
    color *= texture(Sampler0, texCoord0);
#endif
    color.a *= coverage;
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
