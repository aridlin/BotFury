package com.botfury;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WheelMathTest {
    @Test void selectsEightDirectionsAndLeavesHubInert() {
        assertEquals(-1, WheelMath.segment(0, 0, 10));
        for (int index = 0; index < 8; ++index) {
            double angle = -Math.PI / 2 + index * Math.PI / 4;
            assertEquals(index, WheelMath.segment(Math.cos(angle) * 50, Math.sin(angle) * 50, 10));
        }
    }
}
