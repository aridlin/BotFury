#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
in vec2 skinUv;
in vec4 tint;
out vec4 fragColor;

void main() {
    float terrain = texelFetch(Sampler3, ivec2(gl_FragCoord.xy), 0).r;
    if (gl_FragCoord.z <= terrain + 0.00000012 || texture(Sampler0, skinUv).a < 0.2) discard;
    // Circular screen lattice adapted from reusable-halftone, Copyright 2026 Aridlin, MIT.
    float spacing = 7.0;
    vec2 cell = mod(gl_FragCoord.xy, spacing) - vec2(spacing * 0.5);
    float radius = spacing * 0.29;
    float alpha = 1.0 - smoothstep(radius - 0.65, radius + 0.65, length(cell));
    if (alpha < 0.02) discard;
    fragColor = vec4(tint.rgb, alpha * tint.a);
}
