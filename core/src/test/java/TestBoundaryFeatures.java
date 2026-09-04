import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.skyline.BoundaryFeatures;
import com.peaknav.skyline.SkyFeatures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The boundary forest's features: one plane per name, finite, and the window means right. */
class TestBoundaryFeatures {

    private static float[][] picture(int w, int h) {
        float[] r = new float[w * h], g = new float[w * h], b = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean sky = y < h / 2 + (x % 5);
                r[y * w + x] = sky ? 0.5f : 0.3f;
                g[y * w + x] = sky ? 0.7f : 0.35f + ((x * 7 + y * 3) % 4) * 0.02f;
                b[y * w + x] = sky ? 0.95f : 0.25f;
            }
        }
        return new float[][]{r, g, b};
    }

    @Test
    @DisplayName("One plane per name, all finite, with the sky probability's step at the ridge")
    void planes() {
        int w = 48, h = 40;
        float[][] rgb = picture(w, h);
        float[][] pixel = SkyFeatures.compute(rgb[0], rgb[1], rgb[2], w, h);
        float[] pSky = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pSky[y * w + x] = y < h / 2 + (x % 5) ? 1f : 0f;
            }
        }
        float[][] planes = BoundaryFeatures.compute(pixel, pSky, w, h);
        assertEquals(BoundaryFeatures.NAMES.length, planes.length);
        assertEquals(BoundaryFeatures.COUNT, planes.length);
        for (float[] plane : planes) {
            assertEquals(w * h, plane.length);
            for (float v : plane) {
                assertTrue(Float.isFinite(v));
            }
        }
        int x = 10, ridge = h / 2 + (x % 5);
        int pAbove3 = index("pAbove3"), pBelow3 = index("pBelow3"), pAboveAll = index("pAboveAll");
        // just under the ridge: the three rows above are sky, the three below are ground
        int at = ridge * w + x;
        assertEquals(1f, planes[pAbove3][at], 1e-6);
        assertEquals(0f, planes[pBelow3][at], 1e-6);
        assertEquals(1f, planes[pAboveAll][at], 1e-6);
        // far down in the ground the window above is ground too
        int deep = (h - 2) * w + x;
        assertEquals(0f, planes[pAbove3][deep], 1e-6);
        assertTrue(planes[pAboveAll][deep] > 0.4f && planes[pAboveAll][deep] < 0.8f, "sky share of the column above");
        // the row fraction is what it says
        assertEquals(ridge / (float) h, planes[index("rowFrac")][at], 1e-6);
    }

    @Test
    @DisplayName("Window means clamp at the picture's edges instead of reading outside")
    void windowsClampAtEdges() {
        int w = 4, h = 6;
        float[] src = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                src[y * w + x] = y;
            }
        }
        double[] cum = BoundaryFeatures.columnCumulativeForTest(src, w, h);
        float[] above = new float[w * h], below = new float[w * h];
        BoundaryFeatures.windowMeansForTest(cum, w, h, 3, above, below);
        assertEquals(0f, above[0], 1e-6, "nothing above the top row");
        assertEquals((0 + 1 + 2) / 3f, below[0], 1e-6);
        assertEquals((1 + 2 + 3) / 3f, above[4 * w], 1e-6);
        assertEquals((4 + 5) / 2f, below[4 * w], 1e-6, "only two rows left below row 4");
    }

    private static int index(String name) {
        for (int i = 0; i < BoundaryFeatures.NAMES.length; i++) {
            if (BoundaryFeatures.NAMES[i].equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no plane " + name);
    }
}
