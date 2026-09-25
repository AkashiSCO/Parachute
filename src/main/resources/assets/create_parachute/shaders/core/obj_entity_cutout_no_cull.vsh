#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
// 烘焙缓冲里的 Normal 是**模型空间**的，用「只含物体姿态」的矩阵转到世界空间，
// 和 MC 给 Light0/Light1_Direction 的空间一致（不能用 ModelViewMat —— 含相机旋转）。
uniform mat4 ObjNormalMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec3 normalWorld;
out vec4 rawColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, FogShape);
    normalWorld = normalize(mat3(ObjNormalMat) * Normal);
    rawColor = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
