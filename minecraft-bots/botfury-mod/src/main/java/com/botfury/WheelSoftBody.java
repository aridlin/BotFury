package com.botfury;

import java.util.Arrays;

/** A two-ring mesh with neighboring masses, radial springs, and a damped rest shape. */
final class WheelSoftBody {
    static final int POINTS = 17;
    static final int INNER = 0;
    static final int OUTER = 1;
    static final double STEP = 1.0 / 120;
    private static final float SEAM_GAP = .030f;
    private static final float BOUNCE = .16f;
    private static final float BODY_INVERSE_INERTIA = .48f;
    private static final float NEIGHBOR = 320, CROSS = 85, DAMPING = 9.5f;
    private static final float[] REST_RADIUS = {.32f, 1f};

    private final float[][] radial = new float[2][POINTS], angular = new float[2][POINTS];
    private final float[][] radialSpeed = new float[2][POINTS], angularSpeed = new float[2][POINTS];
    private float rotation, rotationSpeed;
    private double unspent;

    void reset(int stagger) {
        for (int ring = INNER; ring <= OUTER; ring++) {
            Arrays.fill(radial[ring], -REST_RADIUS[ring] * .28f);
            Arrays.fill(angular[ring], 0);
            Arrays.fill(radialSpeed[ring], 0);
            Arrays.fill(angularSpeed[ring], 0);
        }
        rotation = rotationSpeed = 0;
        unspent = -stagger * .014;
    }

    void advance(double elapsed, float pull, float turn) {
        unspent += Math.min(elapsed, .05);
        while (unspent >= STEP) {
            integrate(pull, turn);
            unspent -= STEP;
        }
    }

    float radius(int ring, int point) { return REST_RADIUS[ring] + radial[ring][point]; }
    float angle(int ring, int point) { return rotation + angular[ring][point]; }

    private void integrate(float pull, float turn) {
        rotationSpeed += (float)STEP * (-65 * rotation - 8 * rotationSpeed);
        rotation += (float)STEP * rotationSpeed;
        for (int ring = INNER; ring <= OUTER; ring++) {
            float stiffness = ring == INNER ? 255 : 130;
            for (int point = 0; point < POINTS; point++) {
                float t = point / (POINTS - 1f);
                float pullForce = 0, turnForce = 0;
                if (ring == OUTER && pull > 0) {
                    float edge = (float)Math.sin(Math.PI * t);
                    float fromCursor = (t - (.5f + .36f * turn)) / .23f;
                    float influence = (float)Math.exp(-fromCursor * fromCursor * .5f);
                    pullForce = 35 * pull * (.45f + .55f * edge) * influence;
                    turnForce = 41 * turn * (.28f + .72f * edge) * influence
                            + 11 * pull * (2 * t - 1) * (.5f + .5f * influence);
                }
                radialSpeed[ring][point] += (float)STEP * (pullForce + force(radial, ring, point, stiffness)
                        - DAMPING * radialSpeed[ring][point]);
                angularSpeed[ring][point] += (float)STEP * (turnForce + force(angular, ring, point, stiffness)
                        - DAMPING * angularSpeed[ring][point]);
            }
        }
        for (int ring = INNER; ring <= OUTER; ring++) {
            for (int point = 0; point < POINTS; point++) {
                radial[ring][point] += (float)STEP * radialSpeed[ring][point];
                float bent = angular[ring][point] + (float)STEP * angularSpeed[ring][point];
                angular[ring][point] = Math.max(-.22f, Math.min(.22f, bent));
                if (bent != angular[ring][point]) angularSpeed[ring][point] = 0;
            }
        }
    }

    static void collide(WheelSoftBody before, WheelSoftBody after) {
        int edge = POINTS - 1;
        float beforeInner = before.radius(INNER, edge), beforeOuter = before.radius(OUTER, edge);
        float afterInner = after.radius(INNER, 0), afterOuter = after.radius(OUTER, 0);
        float inner = Math.max(beforeInner, afterInner);
        float outer = Math.min(beforeOuter, afterOuter);
        if (inner >= outer) return;
        // Each side is linear in polar space. Check both ends of its shared radial interval.
        contact(before, after, (inner - beforeInner) / (beforeOuter - beforeInner),
                (inner - afterInner) / (afterOuter - afterInner));
        contact(before, after, (outer - beforeInner) / (beforeOuter - beforeInner),
                (outer - afterInner) / (afterOuter - afterInner));
    }

    private static void contact(WheelSoftBody before, WheelSoftBody after, float a, float b) {
        int edge = POINTS - 1;
        float innerA = 1 - a, innerB = 1 - b;
        float overlap = before.rotation + sample(before.angular, edge, a)
                - after.rotation - sample(after.angular, 0, b) - SEAM_GAP;
        if (overlap <= 0) return;
        float inverseMass = 2 * BODY_INVERSE_INERTIA + innerA * innerA + a * a + innerB * innerB + b * b;
        float correction = overlap / inverseMass;
        before.angular[INNER][edge] -= correction * innerA;
        before.angular[OUTER][edge] -= correction * a;
        after.angular[INNER][0] += correction * innerB;
        after.angular[OUTER][0] += correction * b;
        before.rotation -= correction * BODY_INVERSE_INERTIA;
        after.rotation += correction * BODY_INVERSE_INERTIA;

        float approaching = before.rotationSpeed + sample(before.angularSpeed, edge, a)
                - after.rotationSpeed - sample(after.angularSpeed, 0, b);
        if (approaching <= 0) return;
        float impulse = (1 + BOUNCE) * approaching / inverseMass;
        before.angularSpeed[INNER][edge] -= impulse * innerA;
        before.angularSpeed[OUTER][edge] -= impulse * a;
        after.angularSpeed[INNER][0] += impulse * innerB;
        after.angularSpeed[OUTER][0] += impulse * b;
        before.rotationSpeed -= impulse * BODY_INVERSE_INERTIA;
        after.rotationSpeed += impulse * BODY_INVERSE_INERTIA;
    }

    private static float sample(float[][] mesh, int point, float t) {
        return mesh[INNER][point] * (1 - t) + mesh[OUTER][point] * t;
    }

    private static float force(float[][] mesh, int ring, int point, float stiffness) {
        float at = mesh[ring][point];
        float left = mesh[ring][Math.max(0, point - 1)];
        float right = mesh[ring][Math.min(POINTS - 1, point + 1)];
        return NEIGHBOR * (left + right - 2 * at) + CROSS * (mesh[1 - ring][point] - at) - stiffness * at;
    }
}
