package com.peaknav.roads;

/**
 * The screen-space geometry of a road name: which way the way runs as the camera sees it, how
 * to keep the text readable while following it, and whether two tilted names collide.
 */
public final class RoadLabelGeometry {

    private RoadLabelGeometry() {
    }

    /** The principal direction of a set of screen points. */
    public static final class Fit {
        /** Direction in degrees, counter-clockwise from screen right, in (-90, 90]. */
        public float angleDeg;
        /** How far the points spread along that direction, in pixels. */
        public float extent;
        /** Their centroid. */
        public float cx;
        public float cy;
    }

    /**
     * Fits a line through points {@code [from, to)}: the principal axis of their spread, which is
     * the way's average direction on screen. Folded into (-90, 90], a text laid along it reads
     * left to right.
     *
     * @return false when there are fewer than two points, or they all coincide
     */
    public static boolean fit(float[] xs, float[] ys, int from, int to, Fit out) {
        int n = to - from;
        if (n < 2) {
            return false;
        }
        double mx = 0, my = 0;
        for (int i = from; i < to; i++) {
            mx += xs[i];
            my += ys[i];
        }
        mx /= n;
        my /= n;
        double sxx = 0, syy = 0, sxy = 0;
        for (int i = from; i < to; i++) {
            double dx = xs[i] - mx;
            double dy = ys[i] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        if (sxx + syy < 1e-6) {
            return false;
        }
        double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double ux = Math.cos(theta), uy = Math.sin(theta);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double p = (xs[i] - mx) * ux + (ys[i] - my) * uy;
            lo = Math.min(lo, p);
            hi = Math.max(hi, p);
        }
        out.angleDeg = readable((float) Math.toDegrees(theta));
        out.extent = (float) (hi - lo);
        out.cx = (float) mx;
        out.cy = (float) my;
        return true;
    }

    /**
     * How strongly labels shrink with distance: perspective itself is 1 (twice as far, half the
     * size), which would leave a label ten kilometres off unreadable; at 0.3, twice as far is
     * about four fifths the size.
     */
    static final double DISTANCE_SCALE_EXPONENT = 0.3;

    /**
     * The size a label is drawn at, this far from the camera, as a share of its full size: full
     * size out to {@code fullSizeMeters}, then shrinking gently, down to {@code minScale}. Distant
     * labels look distant, and more of them fit in the band near the horizon, where the ground
     * is most foreshortened and the labels most crowded.
     */
    public static float distanceScale(float meters, float fullSizeMeters, float minScale) {
        if (!(meters > fullSizeMeters)) {
            return 1f; // near, or not known
        }
        return Math.max(minScale,
                (float) Math.pow(fullSizeMeters / meters, DISTANCE_SCALE_EXPONENT));
    }

    /** Folds a direction into (-90, 90], the range in which text reads left to right. */
    public static float readable(float angleDeg) {
        float a = angleDeg % 180f;
        if (a <= -90f) {
            a += 180f;
        } else if (a > 90f) {
            a -= 180f;
        }
        return a;
    }

    /**
     * Keeps a label that is already on screen from spinning round. A way turning through the
     * vertical would flip its text half a turn the moment it crossed 90 degrees; this lets the
     * text lean up to {@code limitDeg} past the vertical in the direction it was already
     * facing, and flips only beyond that.
     */
    public static float continueFrom(float angleDeg, float previousDeg, float limitDeg) {
        if (Float.isNaN(previousDeg)) {
            return readable(angleDeg);
        }
        float best = readable(angleDeg);
        float alt = best > 0 ? best - 180f : best + 180f;
        if (Math.abs(alt - previousDeg) < Math.abs(best - previousDeg) && Math.abs(alt) <= limitDeg) {
            return alt;
        }
        return best;
    }

    /** A rectangle turned about its centre, in screen pixels. */
    public static final class Box {
        public float cx, cy, halfW, halfH, cos, sin;

        public Box set(float cx, float cy, float halfW, float halfH, float angleDeg) {
            this.cx = cx;
            this.cy = cy;
            this.halfW = halfW;
            this.halfH = halfH;
            double r = Math.toRadians(angleDeg);
            this.cos = (float) Math.cos(r);
            this.sin = (float) Math.sin(r);
            return this;
        }
    }

    /** Separating-axis test for two turned rectangles. */
    public static boolean overlaps(Box a, Box b) {
        float dx = b.cx - a.cx;
        float dy = b.cy - a.cy;
        return !separated(a.cos, a.sin, a, b, dx, dy)
                && !separated(-a.sin, a.cos, a, b, dx, dy)
                && !separated(b.cos, b.sin, a, b, dx, dy)
                && !separated(-b.sin, b.cos, a, b, dx, dy);
    }

    private static boolean separated(float ax, float ay, Box a, Box b, float dx, float dy) {
        float distance = Math.abs(dx * ax + dy * ay);
        float ra = a.halfW * Math.abs(a.cos * ax + a.sin * ay)
                + a.halfH * Math.abs(-a.sin * ax + a.cos * ay);
        float rb = b.halfW * Math.abs(b.cos * ax + b.sin * ay)
                + b.halfH * Math.abs(-b.sin * ax + b.cos * ay);
        return distance > ra + rb;
    }
}
