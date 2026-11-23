#version 150

uniform sampler2D Sampler0;
in vec2 skinUv;
in float opacity;
out vec4 fragColor;

void main() {
    if (texture(Sampler0, skinUv).a < 0.2) discard;
    // Circular screen lattice adapted from reusable-halftone, Copyright 2026 Aridlin, MIT.
    float spacing = 7.0;
    vec2 cell = mod(gl_FragCoord.xy, spacing) - vec2(spacing * 0.5);
    float radius = spacing * 0.29;
    float alpha = 1.0 - smoothstep(radius - 0.65, radius + 0.65, length(cell));
    if (alpha < 0.02) discard;
    fragColor = vec4(0.91, 0.24, 0.32, alpha * opacity);
}
