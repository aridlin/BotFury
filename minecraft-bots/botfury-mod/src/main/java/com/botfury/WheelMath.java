package com.botfury;
final class WheelMath {
    static int segment(double x, double y, double deadZone) {
        if (x * x + y * y < deadZone * deadZone) return -1;
        double angle = (Math.atan2(y, x) + Math.PI / 2 + Math.PI / 8 + Math.PI * 2) % (Math.PI * 2);
        return (int) (angle / (Math.PI / 4));
    }
}
