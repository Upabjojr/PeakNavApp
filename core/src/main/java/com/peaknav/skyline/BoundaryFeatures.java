package com.peaknav.skyline;

/**
 * Features for the second forest, which scores every (column, row) as "the skyline passes
 * here": what lies just above and just below the row - mean sky probability, colour,
 * texture and edge energy over windows of 3, 8 and 20 rows, from column cumulative sums -
 * the local edge measures, the horizontal coherence of the vertical gradient (a ridge is
 * coherent across columns, cloud texture is not), the step in the sky probability, and
 * how much of the column's edge energy is above the row.
 */
public final class BoundaryFeatures {

    static final int[] WINDOWS = {3, 8, 20};

    // declared before NAMES: build() reads it during static initialisation
    private static final String[] MEAN_PLANES = {"p", "lum", "sat", "chroma", "a", "b", "texFine", "texCoarse", "grad"};

    public static final String[] NAMES = build();
    public static final int COUNT = NAMES.length;

    private static String[] build() {
        java.util.List<String> names = new java.util.ArrayList<String>();
        names.add("rowFrac");
        for (String nm : MEAN_PLANES) {
            boolean split = nm.equals("p") || nm.equals("texFine") || nm.equals("texCoarse") || nm.equals("grad");
            for (int k : WINDOWS) {
                if (split) {
                    names.add(nm + "Above" + k);
                    names.add(nm + "Below" + k);
                } else {
                    names.add(nm + "Diff" + k);
                }
            }
            if (nm.equals("p")) {
                names.add("pAboveAll");
                names.add("pBelowAll");
            }
        }
        String[] local = {"gradLumFine", "gradChromaFine", "gradLumCoarse", "dLumDy", "vContrast", "vContrastFar",
                "gradNorm", "dyNorm", "maxGradAbove", "gyCoh", "gyCohSigned", "dp", "dpCoh", "gradFracAbove"};
        for (String nm : local) {
            names.add(nm);
        }
        return names.toArray(new String[names.size()]);
    }

    private BoundaryFeatures() {
    }

    /**
     * Returns {@code COUNT} planes from the pixel feature planes and the sky probability.
     */
    public static float[][] compute(float[][] planes, float[] pSky, int width, int height) {
        final int n = width * height;
        float[][] out = new float[COUNT][];
        int k = 0;
        float[] rowFrac = new float[n];
        for (int i = 0; i < n; i++) {
            rowFrac[i] = (i / width) / (float) height;
        }
        out[k++] = rowFrac;
        float[][] sources = {pSky, planes[SkyFeatures.LUM], planes[SkyFeatures.SAT], planes[SkyFeatures.CHROMA],
                planes[SkyFeatures.LAB_A], planes[SkyFeatures.LAB_B], planes[SkyFeatures.TEX_FINE],
                planes[SkyFeatures.TEX_COARSE], planes[SkyFeatures.GRAD_LUM_FINE]};
        for (int s = 0; s < sources.length; s++) {
            String nm = MEAN_PLANES[s];
            boolean split = nm.equals("p") || nm.equals("texFine") || nm.equals("texCoarse") || nm.equals("grad");
            double[] cum = columnCumulative(sources[s], width, height);
            for (int w : WINDOWS) {
                float[] above = new float[n], below = new float[n];
                windowMeans(cum, width, height, w, above, below);
                if (split) {
                    out[k++] = above;
                    out[k++] = below;
                } else {
                    float[] diff = new float[n];
                    for (int i = 0; i < n; i++) {
                        diff[i] = above[i] - below[i];
                    }
                    out[k++] = diff;
                }
            }
            if (nm.equals("p")) {
                float[] aboveAll = new float[n], belowAll = new float[n];
                int iw = width;
                for (int x = 0; x < width; x++) {
                    double total = cum[height * iw + x];
                    for (int y = 0; y < height; y++) {
                        double c = cum[y * iw + x];
                        aboveAll[y * width + x] = (float) (c / Math.max(y, 1));
                        belowAll[y * width + x] = (float) ((total - c) / Math.max(height - y, 1));
                    }
                }
                out[k++] = aboveAll;
                out[k++] = belowAll;
            }
        }
        out[k++] = planes[SkyFeatures.GRAD_LUM_FINE];
        out[k++] = planes[SkyFeatures.GRAD_CHROMA_FINE];
        out[k++] = planes[SkyFeatures.GRAD_LUM_COARSE];
        out[k++] = planes[SkyFeatures.D_LUM_DY];
        out[k++] = planes[SkyFeatures.V_CONTRAST];
        out[k++] = planes[SkyFeatures.V_CONTRAST_FAR];
        out[k++] = planes[SkyFeatures.GRAD_NORM];
        out[k++] = planes[SkyFeatures.DY_NORM];
        out[k++] = planes[SkyFeatures.MAX_GRAD_ABOVE];

        // vertical gradient of the blurred luminance and its coherence across columns
        float[] lumB = SkylineExtractor.gaussianBlur(planes[SkyFeatures.LUM], width, height, 1.2);
        float[] gy = new float[n], gyAbs = new float[n];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                float v = lumB[(y + 1) * width + x] - lumB[(y - 1) * width + x];
                gy[y * width + x] = v;
                gyAbs[y * width + x] = Math.abs(v);
            }
        }
        out[k++] = SkylineExtractor.gaussianBlur(gyAbs, width, height, 6.0, 1.0);
        out[k++] = SkylineExtractor.gaussianBlur(gy, width, height, 6.0, 1.0);
        float[] pB = SkylineExtractor.gaussianBlur(pSky, width, height, 1.0);
        float[] dp = new float[n];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                dp[y * width + x] = pB[(y - 1) * width + x] - pB[(y + 1) * width + x];
            }
        }
        out[k++] = dp;
        out[k++] = SkylineExtractor.gaussianBlur(dp, width, height, 6.0, 1.0);

        double[] cg = columnCumulative(planes[SkyFeatures.GRAD_LUM_FINE], width, height);
        float[] frac = new float[n];
        for (int x = 0; x < width; x++) {
            double total = cg[height * width + x] + 1e-3;
            for (int y = 0; y < height; y++) {
                frac[y * width + x] = (float) (cg[y * width + x] / total);
            }
        }
        out[k++] = frac;
        if (k != COUNT) {
            throw new IllegalStateException("boundary planes: " + k + " of " + COUNT);
        }
        return out;
    }

    // Test seams: the package-private helpers, reachable from the default-package tests.
    public static double[] columnCumulativeForTest(float[] src, int width, int height) {
        return columnCumulative(src, width, height);
    }

    public static void windowMeansForTest(double[] cum, int width, int height, int k, float[] above, float[] below) {
        windowMeans(cum, width, height, k, above, below);
    }

    /** {@code (height + 1) x width} cumulative sums down each column; row 0 is zero. */
    static double[] columnCumulative(float[] src, int width, int height) {
        double[] cum = new double[(height + 1) * width];
        for (int x = 0; x < width; x++) {
            double s = 0;
            for (int y = 0; y < height; y++) {
                s += src[y * width + x];
                cum[(y + 1) * width + x] = s;
            }
        }
        return cum;
    }

    /** Means over rows [y-k, y) and [y, y+k), clamped to the image. */
    static void windowMeans(double[] cum, int width, int height, int k, float[] above, float[] below) {
        for (int y = 0; y < height; y++) {
            int ya = Math.max(0, y - k), yb = Math.min(height, y + k);
            int na = Math.max(y - ya, 1), nb = Math.max(yb - y, 1);
            for (int x = 0; x < width; x++) {
                double cy = cum[y * width + x];
                above[y * width + x] = (float) ((cy - cum[ya * width + x]) / na);
                below[y * width + x] = (float) ((cum[yb * width + x] - cy) / nb);
            }
        }
    }
}
