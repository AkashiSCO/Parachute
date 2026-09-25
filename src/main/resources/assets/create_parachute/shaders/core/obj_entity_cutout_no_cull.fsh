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
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    // 双面光照：背面片元（薄片/开口结构从里侧看到的那面）把法线翻回来，
    // 否则那些零件的上下明暗正好和周围相反 —— 因为渲染是不剔除背面的。
    vec3 normal = gl_FrontFacing ? normalWorld : -normalWorld;
    vec4 vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, rawColor);
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
