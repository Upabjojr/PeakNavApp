package com.peaknav.skyline;

import java.util.Arrays;

/**
 * Hand-designed per-pixel features for telling sky from ground: colour (RGB, Lab, hue),
 * position, edge strength at several scales and the ratio between them (a cloud's edge is
 * soft, a ridge's sharp), local texture (snow has shadows and rock in it, cloud does not),
 * signed vertical contrast (sky above a ridge is brighter than the ground below), the
 * pixel's colour relative to the top rows of its own column and to a per-column linear
 * model of the sky (a hazy far ridge is a small, consistent negative residual once the
 * sky's own brightening towards the horizon is discounted), edges normalised by the local
 * contrast (a faint edge in a flat hazy sky still counts), and column context - the
 * strongest edge, darkest pixel and coarsest texture above this pixel (glare and cloud sit
 * under nothing; a snow field sits under a ridge).
 *
 * <p>These are what {@link SkyClassifier}'s trees split on. The same code produces the
 * training rows ({@code SkylineTrainingDump} in the tools source set), so training and
 * inference cannot drift apart. The set and its constants follow the skyline study in the
 * dataset workspace ({@code study/ALGORITHM.md}).
 */
public final class SkyFeatures {

    public static final String[] NAMES = {
            "red", "green", "blue", "lum", "sat", "chroma", "rowFrac",
            "gradLumFine", "gradChromaFine", "gradLumCoarse", "softness",
            "texFine", "texCoarse", "chromaTex", "dLumDy", "vContrast",
            "lumRelTop", "satRelTop", "chromaRelTop", "lumRelImage",
            "L", "a", "b_", "skyResLum", "skyResChroma", "skyResSat", "skyFitSlope",
            "gradNorm", "dyNorm", "gradVeryCoarse", "vContrastFar",
            "maxGradAbove", "sumGradAbove", "minLumAbove", "maxTexAbove", "colFracDark",
            "texVeryCoarse", "satLocal", "hueCos", "hueSin", "lumRelTopWide", "chromaRelTopWide"};
    public static final int COUNT = NAMES.length;

    // Plane indices other code reads back.
    public static final int LUM = 3, SAT = 4, CHROMA = 5, GRAD_LUM_FINE = 7, GRAD_CHROMA_FINE = 8,
            GRAD_LUM_COARSE = 9, TEX_FINE = 11, TEX_COARSE = 12, D_LUM_DY = 14, V_CONTRAST = 15,
            LAB_A = 21, LAB_B = 22, GRAD_NORM = 27, DY_NORM = 28, V_CONTRAST_FAR = 30, MAX_GRAD_ABOVE = 31;

    private static final double FINE_SIGMA = 1.2;
    private static final double COARSE_SIGMA = 4.0;
    private static final double VERY_COARSE_SIGMA = 8.0;
    private static final double VERTICAL_SIGMA = 2.0;
    private static final double CONTRAST_SIGMA = 6.0;
    private static final int CONTRAST_OFFSET = 8;
    private static final int CONTRAST_OFFSET_FAR = 20;
    private static final int TEX_FINE_RADIUS = 2;
    private static final int TEX_COARSE_RADIUS = 7;
    private static final int TEX_VERY_COARSE_RADIUS = 15;
    private static final int NORM_RADIUS = 12;
    private static final float TOP_FRACTION = 0.05f;
    private static final int TOP_COLUMNS = 4;
    private static final float TOP_FRACTION_WIDE = 0.08f;
    private static final int TOP_COLUMNS_WIDE = 24;
    private static final float SKY_FIT_FRACTION = 0.12f;
    private static final int SKY_FIT_SMOOTH = 4;   // coefficients box-smoothed over 2*4+1 columns

    private SkyFeatures() {
    }

    /** Returns {@code COUNT} planes of {@code width * height} floats, row-major. */
    public static float[][] compute(float[] r, float[] g, float[] b, int width, int height) {
        final int n = width * height;
        float[][] f = new float[COUNT][];
        f[0] = r;
        f[1] = g;
        f[2] = b;
        float[] lum = new float[n], sat = new float[n], chroma = new float[n], rowFrac = new float[n];
        for (int i = 0; i < n; i++) {
            float max = Math.max(r[i], Math.max(g[i], b[i]));
            float min = Math.min(r[i], Math.min(g[i], b[i]));
            lum[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i];
            sat[i] = (max - min) / (max + 0.02f);
            chroma[i] = b[i] - 0.5f * (r[i] + g[i]);
            rowFrac[i] = (i / width) / (float) height;
        }
        f[LUM] = lum;
        f[SAT] = sat;
        f[CHROMA] = chroma;
        f[6] = rowFrac;

        float[] lumFine = SkylineExtractor.gaussianBlur(lum, width, height, FINE_SIGMA);
        float[] gradLumFine = SkylineExtractor.gradientMagnitude(lumFine, width, height);
        float[] gradChromaFine = SkylineExtractor.gradientMagnitude(
                SkylineExtractor.gaussianBlur(chroma, width, height, FINE_SIGMA), width, height);
        float[] gradLumCoarse = SkylineExtractor.gradientMagnitude(
                SkylineExtractor.gaussianBlur(lum, width, height, COARSE_SIGMA), width, height);
        float[] softness = new float[n];
        for (int i = 0; i < n; i++) {
            softness[i] = gradLumCoarse[i] / (gradLumFine[i] + 0.01f);
        }
        f[GRAD_LUM_FINE] = gradLumFine;
        f[GRAD_CHROMA_FINE] = gradChromaFine;
        f[GRAD_LUM_COARSE] = gradLumCoarse;
        f[10] = softness;

        float[] texCoarse = localStd(lum, width, height, TEX_COARSE_RADIUS);
        f[TEX_FINE] = localStd(lum, width, height, TEX_FINE_RADIUS);
        f[TEX_COARSE] = texCoarse;
        f[13] = localStd(chroma, width, height, TEX_FINE_RADIUS);

        float[] lumV = SkylineExtractor.gaussianBlur(lum, width, height, VERTICAL_SIGMA);
        f[D_LUM_DY] = centralVertical(lumV, width, height);
        float[] lumC = SkylineExtractor.gaussianBlur(lum, width, height, CONTRAST_SIGMA);
        f[V_CONTRAST] = verticalContrast(lumC, width, height, CONTRAST_OFFSET);

        float[] lumTop = topRows(lum, width, height, TOP_FRACTION, TOP_COLUMNS);
        float[] satTop = topRows(sat, width, height, TOP_FRACTION, TOP_COLUMNS);
        float[] chromaTop = topRows(chroma, width, height, TOP_FRACTION, TOP_COLUMNS);
        float[] lumTopWide = topRows(lum, width, height, TOP_FRACTION_WIDE, TOP_COLUMNS_WIDE);
        float[] chromaTopWide = topRows(chroma, width, height, TOP_FRACTION_WIDE, TOP_COLUMNS_WIDE);
        float[] lumRelTop = new float[n], satRelTop = new float[n], chromaRelTop = new float[n];
        float[] lumRelTopWide = new float[n], chromaRelTopWide = new float[n];
        for (int i = 0; i < n; i++) {
            int x = i % width;
            lumRelTop[i] = lum[i] - lumTop[x];
            satRelTop[i] = sat[i] - satTop[x];
            chromaRelTop[i] = chroma[i] - chromaTop[x];
            lumRelTopWide[i] = lum[i] - lumTopWide[x];
            chromaRelTopWide[i] = chroma[i] - chromaTopWide[x];
        }
        f[16] = lumRelTop;
        f[17] = satRelTop;
        f[18] = chromaRelTop;
        f[40] = lumRelTopWide;
        f[41] = chromaRelTopWide;

        float[] sorted = lum.clone();
        Arrays.sort(sorted);
        float median = sorted[n / 2];
        float[] lumRelImage = new float[n];
        for (int i = 0; i < n; i++) {
            lumRelImage[i] = lum[i] - median;
        }
        f[19] = lumRelImage;

        // CIE Lab, scaled to [0, 1] as OpenCV's 8-bit conversion does
        float[] labL = new float[n], labA = new float[n], labB = new float[n];
        lab(r, g, b, labL, labA, labB);
        f[20] = labL;
        f[LAB_A] = labA;
        f[LAB_B] = labB;

        // residuals from a per-column linear model of the sky fitted on the top rows
        float[] slope = new float[width];
        f[23] = skyFitResidual(lum, width, height, slope);
        f[24] = skyFitResidual(chroma, width, height, null);
        f[25] = skyFitResidual(sat, width, height, null);
        float[] slopePlane = new float[n];
        for (int i = 0; i < n; i++) {
            slopePlane[i] = slope[i % width];
        }
        f[26] = slopePlane;

        // edges relative to the variation in a large neighbourhood
        float[] lumN = SkylineExtractor.gaussianBlur(lum, width, height, 1.5);
        float[] gradN = SkylineExtractor.gradientMagnitude(lumN, width, height);
        float[] gyN = sobelVertical(lumN, width, height);
        float[] norm = localStd(lum, width, height, NORM_RADIUS);
        float[] gradNorm = new float[n], dyNorm = new float[n];
        for (int i = 0; i < n; i++) {
            float d = norm[i] + 0.02f;
            gradNorm[i] = gradN[i] / d;
            dyNorm[i] = gyN[i] / d;
        }
        f[GRAD_NORM] = gradNorm;
        f[DY_NORM] = dyNorm;
        f[29] = SkylineExtractor.gradientMagnitude(
                SkylineExtractor.gaussianBlur(lum, width, height, VERY_COARSE_SIGMA), width, height);
        f[V_CONTRAST_FAR] = verticalContrast(lumC, width, height, CONTRAST_OFFSET_FAR);

        // column context: what lies above this pixel
        f[MAX_GRAD_ABOVE] = runningMax(gradLumFine, width, height);
        float[] gradSmooth = SkylineExtractor.gaussianBlur(gradLumFine, width, height, 2.0);
        float[] sumGradAbove = new float[n];
        float[] lumSmooth = SkylineExtractor.gaussianBlur(lum, width, height, 2.0);
        float[] colFracDark = new float[n];
        for (int x = 0; x < width; x++) {
            double sum = 0;
            int dark = 0;
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                sum += gradSmooth[i];
                sumGradAbove[i] = (float) (sum / height);
                if (lum[i] < median) {
                    dark++;
                }
                colFracDark[i] = dark / (float) (y + 1);
            }
        }
        f[32] = sumGradAbove;
        f[33] = runningMin(lumSmooth, width, height);
        f[34] = runningMax(texCoarse, width, height);
        f[35] = colFracDark;
        f[36] = localStd(lum, width, height, TEX_VERY_COARSE_RADIUS);
        f[37] = SkylineExtractor.gaussianBlur(sat, width, height, COARSE_SIGMA);

        float[] hueCos = new float[n], hueSin = new float[n];
        for (int i = 0; i < n; i++) {
            double h = hue(r[i], g[i], b[i]);
            hueCos[i] = (float) (Math.cos(h) * sat[i]);
            hueSin[i] = (float) (Math.sin(h) * sat[i]);
        }
        f[38] = hueCos;
        f[39] = hueSin;
        return f;
    }

    /** One pixel's feature vector, gathered from the planes. */
    public static void gather(float[][] planes, int index, float[] out) {
        for (int k = 0; k < planes.length; k++) {
            out[k] = planes[k][index];
        }
    }

    /**
     * Mean of the top {@code fraction} of rows in a neighbourhood of {@code columns}
     * columns either side: what the sky looks like at the top of the picture above a
     * pixel; one value per column.
     */
    static float[] topRows(float[] src, int width, int height, float fraction, int columns) {
        int rows = Math.max(1, Math.round(fraction * height));
        float[] column = new float[width];
        for (int x = 0; x < width; x++) {
            float s = 0;
            for (int y = 0; y < rows; y++) {
                s += src[y * width + x];
            }
            column[x] = s / rows;
        }
        return boxSmooth(column, columns);
    }

    /** Box filter of a per-column vector with clamped ends, radius {@code k}. */
    static float[] boxSmooth(float[] column, int k) {
        int width = column.length;
        float[] out = new float[width];
        for (int x = 0; x < width; x++) {
            int a = Math.max(0, x - k), b = Math.min(width - 1, x + k);
            float s = 0;
            for (int i = a; i <= b; i++) {
                s += column[i];
            }
            out[x] = s / (b - a + 1);
        }
        return out;
    }

    /**
     * Residual of {@code src} from a per-column straight line fitted (least squares) to
     * its top {@code SKY_FIT_FRACTION} of rows, the line's coefficients smoothed over
     * neighbouring columns; the slope per column is returned in {@code slopeOut} when
     * given.
     */
    static float[] skyFitResidual(float[] src, int width, int height, float[] slopeOut) {
        int rows = Math.max(3, Math.round(SKY_FIT_FRACTION * height));
        double ym = (rows - 1) / 2.0;
        double syy = 0;
        for (int y = 0; y < rows; y++) {
            syy += (y - ym) * (y - ym);
        }
        float[] slope = new float[width], intercept = new float[width];
        for (int x = 0; x < width; x++) {
            double mean = 0;
            for (int y = 0; y < rows; y++) {
                mean += src[y * width + x];
            }
            mean /= rows;
            double sxy = 0;
            for (int y = 0; y < rows; y++) {
                sxy += (y - ym) * (src[y * width + x] - mean);
            }
            slope[x] = (float) (sxy / syy);
            intercept[x] = (float) (mean - slope[x] * ym);
        }
        slope = boxSmooth(slope, SKY_FIT_SMOOTH);
        intercept = boxSmooth(intercept, SKY_FIT_SMOOTH);
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = src[y * width + x] - (intercept[x] + slope[x] * y);
            }
        }
        if (slopeOut != null) {
            System.arraycopy(slope, 0, slopeOut, 0, width);
        }
        return out;
    }

    /** Central vertical difference, half-step at the borders. */
    static float[] centralVertical(float[] src, int width, int height) {
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1), yp = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                out[y * width + x] = (src[yp * width + x] - src[ym * width + x]) * 0.5f;
            }
        }
        return out;
    }

    /** Sobel vertical derivative (positive downwards), clamped borders. */
    static float[] sobelVertical(float[] src, int width, int height) {
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1), yp = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int xm = Math.max(0, x - 1), xp = Math.min(width - 1, x + 1);
                out[y * width + x] = (src[yp * width + xm] + 2 * src[yp * width + x] + src[yp * width + xp])
                        - (src[ym * width + xm] + 2 * src[ym * width + x] + src[ym * width + xp]);
            }
        }
        return out;
    }

    /** {@code src(y - offset) - src(y + offset)} with rows clamped. */
    static float[] verticalContrast(float[] src, int width, int height, int offset) {
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            int ya = Math.max(0, y - offset), yb = Math.min(height - 1, y + offset);
            for (int x = 0; x < width; x++) {
                out[y * width + x] = src[ya * width + x] - src[yb * width + x];
            }
        }
        return out;
    }

    static float[] runningMax(float[] src, int width, int height) {
        float[] out = new float[width * height];
        for (int x = 0; x < width; x++) {
            float m = Float.NEGATIVE_INFINITY;
            for (int y = 0; y < height; y++) {
                m = Math.max(m, src[y * width + x]);
                out[y * width + x] = m;
            }
        }
        return out;
    }

    static float[] runningMin(float[] src, int width, int height) {
        float[] out = new float[width * height];
        for (int x = 0; x < width; x++) {
            float m = Float.POSITIVE_INFINITY;
            for (int y = 0; y < height; y++) {
                m = Math.min(m, src[y * width + x]);
                out[y * width + x] = m;
            }
        }
        return out;
    }

    /** Standard deviation in a (2r+1)^2 window with clamped borders, via integral images. */
    static float[] localStd(float[] src, int width, int height, int radius) {
        int iw = width + 1;
        double[] sum = new double[(width + 1) * (height + 1)];
        double[] sq = new double[(width + 1) * (height + 1)];
        for (int y = 0; y < height; y++) {
            double rowSum = 0, rowSq = 0;
            for (int x = 0; x < width; x++) {
                float v = src[y * width + x];
                rowSum += v;
                rowSq += v * (double) v;
                sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rowSum;
                sq[(y + 1) * iw + x + 1] = sq[y * iw + x + 1] + rowSq;
            }
        }
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius), y1 = Math.min(height, y + radius + 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius), x1 = Math.min(width, x + radius + 1);
                double count = (y1 - y0) * (double) (x1 - x0);
                double s = sum[y1 * iw + x1] - sum[y0 * iw + x1] - sum[y1 * iw + x0] + sum[y0 * iw + x0];
                double q = sq[y1 * iw + x1] - sq[y0 * iw + x1] - sq[y1 * iw + x0] + sq[y0 * iw + x0];
                double mean = s / count;
                double var = q / count - mean * mean;
                out[y * width + x] = (float) Math.sqrt(Math.max(0, var));
            }
        }
        return out;
    }

    /** sRGB to CIE Lab (D65), scaled as OpenCV's 8-bit output divided by 255. */
    static void lab(float[] r, float[] g, float[] b, float[] outL, float[] outA, float[] outB) {
        for (int i = 0; i < r.length; i++) {
            double rl = linear(r[i]), gl = linear(g[i]), bl = linear(b[i]);
            double x = (0.412453 * rl + 0.357580 * gl + 0.180423 * bl) / 0.950456;
            double y = 0.212671 * rl + 0.715160 * gl + 0.072169 * bl;
            double z = (0.019334 * rl + 0.119193 * gl + 0.950227 * bl) / 1.088754;
            double fx = labF(x), fy = labF(y), fz = labF(z);
            double lStar = 116 * fy - 16;
            double aStar = 500 * (fx - fy);
            double bStar = 200 * (fy - fz);
            outL[i] = (float) (lStar / 100.0);
            outA[i] = (float) ((aStar + 128) / 255.0);
            outB[i] = (float) ((bStar + 128) / 255.0);
        }
    }

    private static double linear(float c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    /** Hue in radians, 0 at red. */
    static double hue(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        if (d < 1e-6f) {
            return 0;
        }
        double h;
        if (max == r) {
            h = (g - b) / d;
            if (h < 0) {
                h += 6;
            }
        } else if (max == g) {
            h = 2 + (b - r) / d;
        } else {
            h = 4 + (r - g) / d;
        }
        return h * Math.PI / 3;
    }
}
