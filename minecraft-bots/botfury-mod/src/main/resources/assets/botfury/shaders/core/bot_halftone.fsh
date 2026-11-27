#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
in vec2 skinUv;
in vec4 tint;
out vec4 fragColor;

void main() {
    float terrain = texelFetch(Sampler3, ivec2(gl_FragCoord.xy), 0).r;
    if (gl_FragCoord.z <= terrain + 0.00000012 || texture(Sampler0, skinUv).a < 0.2) discard;
    // The skin coordinate keeps the press screen attached to the bot as the camera moves.
    ivec2 cell = ivec2(mod(floor(skinUv * 64.0), 8.0));
    float mark = texelFetch(Sampler4, ivec2(64, 0), 0).r;
    float ink = texelFetch(Sampler4, cell + ivec2(24, 24), 0).r;
    if (abs(mark - ink) > 0.1) discard;
    fragColor = vec4(tint.rgb, tint.a);
}
