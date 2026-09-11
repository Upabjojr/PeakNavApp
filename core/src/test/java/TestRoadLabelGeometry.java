import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.roads.RoadLabelGeometry;
import com.peaknav.roads.RoadLabelGeometry.Box;
import com.peaknav.roads.RoadLabelGeometry.Fit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tilting a road name to follow its way on screen, keeping it readable, and keeping names apart. */
class TestRoadLabelGeometry {

    /** Points along a line through (100, 200) at this angle, as screen coordinates. */
    private static void line(float angleDeg, float[] xs, float[] ys) {
        double r = Math.toRadians(angleDeg);
        for (int i = 0; i < xs.length; i++) {
            float t = (i - xs.length / 2) * 10f;
            xs[i] = 100f + t * (float) Math.cos(r);
            ys[i] = 200f + t * (float) Math.sin(r);
        }
    }

    @Test
    @DisplayName("the fitted direction is the way's direction on screen")
    void fitFollowsTheLine() {
        float[] xs = new float[11], ys = new float[11];
        Fit fit = new Fit();
        line(30f, xs, ys);
        assertTrue(RoadLabelGeometry.fit(xs, ys, 0, 11, fit));
        assertEquals(30f, fit.angleDeg, 0.01f);
        assertEquals(100f, fit.extent, 0.01f);
        assertEquals(100f, fit.cx, 0.01f);
        assertEquals(200f, fit.cy, 0.01f);
        line(-20f, xs, ys);
        RoadLabelGeometry.fit(xs, ys, 0, 11, fit);
        assertEquals(-20f, fit.angleDeg, 0.01f);
    }

    @Test
    @DisplayName("a way running up and to the left still gets text reading left to right")
    void foldedToReadable() {
        float[] xs = new float[9], ys = new float[9];
        Fit fit = new Fit();
        line(120f, xs, ys);
        RoadLabelGeometry.fit(xs, ys, 0, 9, fit);
        assertEquals(-60f, fit.angleDeg, 0.01f);
        line(90f, xs, ys);
        RoadLabelGeometry.fit(xs, ys, 0, 9, fit);
        assertEquals(90f, Math.abs(fit.angleDeg), 0.01f);
        assertEquals(-60f, RoadLabelGeometry.readable(300f), 1e-4f);
        assertEquals(10f, RoadLabelGeometry.readable(-170f), 1e-4f);
    }

    @Test
    @DisplayName("a wiggly way is followed on average")
    void averageOfACurve() {
        float[] xs = {0, 10, 20, 30, 40, 50, 60};
        float[] ys = {0, 3, -3, 3, -3, 3, 0};
        Fit fit = new Fit();
        RoadLabelGeometry.fit(xs, ys, 0, xs.length, fit);
        assertEquals(0f, fit.angleDeg, 3f);
    }

    @Test
    @DisplayName("fewer than two points, or all in one place, cannot be fitted")
    void degenerate() {
        Fit fit = new Fit();
        assertFalse(RoadLabelGeometry.fit(new float[]{1}, new float[]{1}, 0, 1, fit));
        assertFalse(RoadLabelGeometry.fit(new float[]{5, 5, 5}, new float[]{2, 2, 2}, 0, 3, fit));
    }

    @Test
    @DisplayName("a label already leaning past the vertical keeps leaning, then flips, never spins")
    void hysteresis() {
        // The way turned from 85 to 92 degrees: the text stays the way up it was.
        assertEquals(92f, RoadLabelGeometry.continueFrom(-88f, 85f, 100f), 1e-4f);
        // Much further round, it flips to read left to right again.
        assertEquals(-60f, RoadLabelGeometry.continueFrom(-60f, 85f, 100f), 1e-4f);
        // A new label simply reads left to right.
        assertEquals(-60f, RoadLabelGeometry.continueFrom(120f, Float.NaN, 100f), 1e-4f);
    }

    @Test
    @DisplayName("turned rectangles collide only where they really touch")
    void overlaps() {
        Box a = new Box().set(0, 0, 50, 8, 0);
        assertTrue(RoadLabelGeometry.overlaps(a, new Box().set(60, 0, 20, 8, 0)));
        assertFalse(RoadLabelGeometry.overlaps(a, new Box().set(80, 0, 20, 8, 0)));
        assertTrue(RoadLabelGeometry.overlaps(a, new Box().set(0, 0, 50, 8, 90)), "a cross");
        // Two long thin labels side by side on a diagonal: their upright boxes overlap
        // heavily, the labels themselves do not.
        Box d1 = new Box().set(0, 0, 60, 6, 45);
        Box d2 = new Box().set(14, -14, 60, 6, 45);
        assertFalse(RoadLabelGeometry.overlaps(d1, d2));
        assertTrue(RoadLabelGeometry.overlaps(d1, new Box().set(5, -5, 60, 6, 45)));
    }
}
