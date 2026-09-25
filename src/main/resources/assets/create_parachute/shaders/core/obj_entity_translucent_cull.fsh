#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

in float vertexDistance;
in vec3 normalWorld;
in vec4 rawColor;
in vec4 lightMapColor;
in vec2 texCoord0;
in vec2 texCoord1;

out vec4 fragColor;

void main() {
    // 双面光照，同 cutout：背面片元翻法线
    vec3 normal = gl_FrontFacing ? normalWorld : -normalWorld;
    vec4 vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, rawColor);
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
