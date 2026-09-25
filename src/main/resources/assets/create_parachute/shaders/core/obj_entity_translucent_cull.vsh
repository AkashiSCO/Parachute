#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
// 见 obj_entity_cutout_no_cull.vsh 的说明：只含物体姿态的法线矩阵
uniform mat4 ObjNormalMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec3 normalWorld;
out vec4 rawColor;
out vec4 lightMapColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, FogShape);
    normalWorld = normalize(mat3(ObjNormalMat) * Normal);
    rawColor = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = UV2;
}
