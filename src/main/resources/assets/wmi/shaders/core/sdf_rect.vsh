#version 330
#extension GL_ARB_separate_shader_objects : require

// Way More Items : rectangle arrondi vectoriel (champ de distance signée).
// UV3 = position locale par rapport au centre (unités d'interface),
// UV1 = demi-dimensions x16, UV2 = (rayon x256, paramètre x256).
// Paramètre : 0 = plein, > 0 = contour de cette épaisseur, < 0 = ombre douce de ce flou.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
#ifdef TEXTURED
layout(location = 2) in vec2 UV0;
#endif
layout(location = 3) in vec2 UV3;
layout(location = 4) in ivec2 UV1;
layout(location = 5) in ivec2 UV2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 localPos;
layout(location = 2) flat out vec4 shapeParams;
#ifdef TEXTURED
layout(location = 3) out vec2 texCoord0;
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV3;
    shapeParams = vec4(vec2(UV1) / 16.0, vec2(UV2) / 256.0);
#ifdef TEXTURED
    texCoord0 = UV0;
#endif
}
