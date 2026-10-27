package com.peaknav.contours;

/**
 * The rules the contour labels share with the terrain shader, and the amounts the menu offers.
 *
 * <p>The shader picks its contour interval per pixel from how many metres of height one pixel
 * spans ({@code assets/fragment_shader.glsl}, the contours block): level k has lines every
 * {@link #BASE_METERS} times 2^k, k0 is the finest whose lines are at least
 * {@link #MIN_PIXELS} apart, and the lines at four and eight times its interval are bold, the
 * first of them fading in with the weight f. A label is written only on a line the viewer sees
 * in bold: one at eight times the interval, or at four times it once it is more than half
 * faded in. Keep the constants equal to the shader's.
 */
public final class ContourLabels {

    public static final float BASE_METERS = 100f;
    public static final float MIN_LEVEL = -2f;
    public static final float MIN_PIXELS = 7f;

    /** The amounts the menu steps through. */
    public static final int AMOUNT_OFF = 0, AMOUNT_FEW = 1, AMOUNT_SOME = 2, AMOUNT_MANY = 3;
    public static final int AMOUNT_MAX = AMOUNT_MANY;

    private ContourLabels() {
    }

    /**
     * Whether the shader draws the line at {@code level} metres in bold where one pixel spans
     * {@code metersPerPixel} of height.
     */
    public static boolean labelled(int level, float metersPerPixel) {
        float de = Math.max(metersPerPixel, 1e-4f);
        double lod = Math.log(de * MIN_PIXELS / BASE_METERS) / Math.log(2.0);
        double k0 = Math.max(Math.ceil(lod), MIN_LEVEL);
        double f = Math.min(1.0, Math.max(0.0, k0 - lod));
        long bold = Math.round(4.0 * BASE_METERS * Math.pow(2.0, k0));
        if (bold <= 0) {
            return false;
        }
        if (level % (2 * bold) == 0) {
            return true;
        }
        return level % bold == 0 && f >= 0.5;
    }

    /** Which blocks an amount keeps: every fourth, second or one (see {@link ContourLabelCandidate#kept}). */
    public static int stride(int amount) {
        switch (amount) {
            case AMOUNT_FEW:
                return 4;
            case AMOUNT_SOME:
                return 2;
            default:
                return 1;
        }
    }

    /** The most labels an amount writes at once. */
    public static int maxLabels(int amount) {
        switch (amount) {
            case AMOUNT_FEW:
                return 10;
            case AMOUNT_SOME:
                return 24;
            default:
                return 48;
        }
    }

    /**
     * Room kept clear around a label, in heights of its text: the fewer labels asked for, the
     * farther apart they stand.
     */
    public static float spacing(int amount) {
        switch (amount) {
            case AMOUNT_FEW:
                return 3f;
            case AMOUNT_SOME:
                return 1.5f;
            default:
                return 0.5f;
        }
    }
}
