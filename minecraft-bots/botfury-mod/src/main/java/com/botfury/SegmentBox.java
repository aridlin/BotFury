package com.botfury;

/** Swept segment intersection, including fast projectiles that cross a body between ticks. */
final class SegmentBox {
    record Point(double x,double y,double z){double axis(int i){return i==0?x:i==1?y:z;}}
    static double hitFraction(Point from,Point to,Point min,Point max){
        double enter=0,exit=1;
        for(int i=0;i<3;i++){
            double a=from.axis(i),b=to.axis(i),lo=min.axis(i),hi=max.axis(i);
            if(!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(lo)||!Double.isFinite(hi)||lo>hi)return Double.NaN;
            double velocity=b-a;
            if(Math.abs(velocity)<1e-10){if(a<lo||a>hi)return Double.NaN;continue;}
            double first=(lo-a)/velocity,last=(hi-a)/velocity;
            if(first>last){double swap=first;first=last;last=swap;}
            enter=Math.max(enter,first);exit=Math.min(exit,last);
            if(enter>exit)return Double.NaN;
        }
        return enter;
    }
}
