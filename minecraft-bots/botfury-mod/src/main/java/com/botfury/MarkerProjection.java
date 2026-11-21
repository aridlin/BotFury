package com.botfury;

final class MarkerProjection {
    record Point(float x, float y, boolean edge, boolean behind) {}
    static Point project(float clipX, float clipY, float clipW, int width, int height, float margin) {
        if (!Float.isFinite(clipX) || !Float.isFinite(clipY) || !Float.isFinite(clipW)
                || width <= 2 * margin || height <= 2 * margin) return null;
        boolean behind = clipW <= 0;
        float divisor = Math.max(Math.abs(clipW), 0.001f);
        float dx = clipX / divisor * width / 2, dy = -clipY / divisor * height / 2;
        float halfW = width / 2f - margin, halfH = height / 2f - margin;
        boolean edge = behind || Math.abs(dx) > halfW || Math.abs(dy) > halfH;
        if (behind && Math.abs(dx) + Math.abs(dy) < 0.01) dy = halfH;
        if (edge) {
            float factor = Math.min(halfW / Math.max(Math.abs(dx), 0.001f), halfH / Math.max(Math.abs(dy), 0.001f));
            dx *= factor; dy *= factor;
        }
        return new Point(width / 2f + dx, height / 2f + dy, edge, behind);
    }
}
