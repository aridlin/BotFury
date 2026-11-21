package com.botfury;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SegmentBoxTest {
    private static final SegmentBox.Point MIN=new SegmentBox.Point(-.3,0,-.3),MAX=new SegmentBox.Point(.3,1.8,.3);
    @Test void catchesFastArrowCrossingBetweenTicks(){
        assertEquals(.47,SegmentBox.hitFraction(new SegmentBox.Point(-5,1,0),new SegmentBox.Point(5,1,0),MIN,MAX),1e-9);
    }
    @Test void ignoresArrowsMovingAwayOrAboveTheHead(){
        assertTrue(Double.isNaN(SegmentBox.hitFraction(new SegmentBox.Point(2,1,0),new SegmentBox.Point(5,1,0),MIN,MAX)));
        assertTrue(Double.isNaN(SegmentBox.hitFraction(new SegmentBox.Point(-5,2.5,0),new SegmentBox.Point(5,2.5,0),MIN,MAX)));
    }
    @Test void handlesArrowAlreadyInBodyAndParallelMisses(){
        assertEquals(0,SegmentBox.hitFraction(new SegmentBox.Point(0,1,0),new SegmentBox.Point(0,1,3),MIN,MAX));
        assertTrue(Double.isNaN(SegmentBox.hitFraction(new SegmentBox.Point(-5,1,1),new SegmentBox.Point(5,1,1),MIN,MAX)));
    }
    @Test void handlesStationaryAndInvalidCoordinates(){
        assertEquals(0,SegmentBox.hitFraction(new SegmentBox.Point(0,1,0),new SegmentBox.Point(0,1,0),MIN,MAX));
        assertTrue(Double.isNaN(SegmentBox.hitFraction(new SegmentBox.Point(Double.NaN,1,0),new SegmentBox.Point(5,1,0),MIN,MAX)));
    }
}
