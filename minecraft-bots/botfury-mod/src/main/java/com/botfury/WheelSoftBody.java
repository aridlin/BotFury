package com.botfury;

import java.util.Arrays;

/** A two-ring mesh with neighboring masses, radial springs, and a damped rest shape. */
final class WheelSoftBody {
    static final int POINTS = 17;
    static final int INNER = 0;
    static final int OUTER = 1;
    private static final double STEP = 1.0 / 120;
    private static final float NEIGHBOR = 320, CROSS = 85, DAMPING = 9.5f;
    private static final float[] REST_RADIUS = {.32f, 1f};

    private final float[][] radial = new float[2][POINTS], angular = new float[2][POINTS];
    private final float[][] radialSpeed = new float[2][POINTS], angularSpeed = new float[2][POINTS];
    private double unspent;

    void reset(int stagger) {
        for (int ring = INNER; ring <= OUTER; ring++) {
            Arrays.fill(radial[ring], -REST_RADIUS[ring] * .28f);
            Arrays.fill(angular[ring], 0);
            Arrays.fill(radialSpeed[ring], 0);
            Arrays.fill(angularSpeed[ring], 0);
        }
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
    float angle(int ring, int point) { return angular[ring][point]; }

    private void integrate(float pull, float turn) {
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
                    turnForce = 39 * turn * edge * influence;
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
                // Pin the two sides angularly so even a hard pull leaves a gap between wedges.
                float t = point / (POINTS - 1f);
                float boundary = .38f * Math.min(t, 1 - t);
                float bent = angular[ring][point] + (float)STEP * angularSpeed[ring][point];
                angular[ring][point] = Math.max(-boundary, Math.min(boundary, bent));
                if (bent != angular[ring][point]) angularSpeed[ring][point] = 0;
            }
        }
    }

    private static float force(float[][] mesh, int ring, int point, float stiffness) {
        float at = mesh[ring][point];
        float left = mesh[ring][Math.max(0, point - 1)];
        float right = mesh[ring][Math.min(POINTS - 1, point + 1)];
        return NEIGHBOR * (left + right - 2 * at) + CROSS * (mesh[1 - ring][point] - at) - stiffness * at;
    }
}
