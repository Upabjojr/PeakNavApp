package com.peaknav.skyline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds where a photo's skyline sits on the terrain's horizon: the compass bearing, pitch
 * and vertical field of view (and a small roll) of a pinhole camera that projects the
 * {@link TerrainHorizon} onto the skyline {@link SkylineExtractor} traced in the photo.
 *
 * <p>No learning: this is a plain optimisation. A coarse exhaustive search over bearing
 * (one-degree steps), a handful of fields of view and a few rolls, with the pitch solved in
 * closed form for each triple as the median vertical offset, then a coordinate-descent
 * refinement of the best distinct candidates using the exact projection. The cost is a
 * Huber loss on the vertical pixel residual, measured as a fraction of image height - NOT
 * in degrees, which would let a narrow field of view shrink every error and win by cheating.
 *
 * <p>The roll matters more than its size suggests. Hand-held pictures are routinely a few
 * degrees off level, and a tilt of 5 degrees slants the ridge by 8% of the height across a
 * 480-pixel-wide frame - far past the loss cap, so a level camera at the right bearing
 * scores like a wrong one and the coarse search never hands the true pose to the
 * refinement. Trying a few rolls in the coarse search costs a few times the work and
 * recovers those pictures; the refinement then settles the roll to a fraction of a degree.
 *
 * <p>Confidence comes from two independent signs: the residual itself, and how much better
 * the winner is than the best pose pointing somewhere else (the second-ranked distinct
 * candidate). A flat horizon fits anywhere equally well, so the winner is also refused when
 * the terrain in view has hardly any relief.
 */
public final class SkylineMatcher {

    /** Vertical fields of view tried when the photo carries no focal length. */
    private static final float[] DEFAULT_VFOVS = {20f, 27f, 35f, 45f, 55f, 65f, 80f};
    /** Search bounds for the field of view. */
    public static final float VFOV_MIN = 15f;
    public static final float VFOV_MAX = 100f;
    private static final float PITCH_MAX = 60f;
    private static final float ROLL_MAX = 15f;
    /**
     * Rolls tried in the coarse search, degrees. Spaced so that at the worst point between
     * two of them the ridge is off by under 2% of the height at the frame's edge - inside
     * the loss cap, so the right bearing still wins its column of the search - and reaching
     * to the tilt a photograph is plausibly taken with; the refinement covers the rest up
     * to {@link #ROLL_MAX}.
     */
    private static final float[] COARSE_ROLLS_DEG = {-9f, -6f, -3f, 0f, 3f, 6f, 9f};
    /** Huber transition: residuals up to 1% of the image height count quadratically. */
    private static final double HUBER_DELTA = 0.01;
    /**
     * Residuals beyond 3% of the height all cost the same: a photo whose skyline is right
     * for most columns but wrong in a large minority (a fence, trees, a snow-line the
     * extractor slid onto) must still be won by the pose that fits the majority, not by
     * one that is mediocre everywhere. Raised the hand-annotated GeoPose3K score from
     * 46% to 55% within 10 degrees.
     */
    private static final double LOSS_CAP = 0.03;
    /** Bearing step of the coarse search, degrees. */
    private static final double COARSE_STEP_DEG = 1.0;
    /** Distinct candidates are at least this far apart in bearing. */
    private static final double CANDIDATE_SEPARATION_DEG = 8.0;
    private static final int CANDIDATES_TO_REFINE = 6;

    // ---- acceptance thresholds ----
    //
    // Fitted on GeoPose3K's 123 hand-annotated photos (see SkylineBenchmark). The ratio is
    // what carries the decision: at 0.6, 95% of the matches called confident are within
    // 10 degrees of the truth whether or not the field of view was known (recall 40-50%);
    // at 0.85 precision falls to 65-80%. The relief floor removes the flat horizons a
    // photo cannot be oriented on, the cost bound is a sanity check (the capped loss keeps
    // costs small even for nonsense, so it rarely decides).
    /** Largest mean residual, as a fraction of image height, still called a match. */
    public static final double CONFIDENT_MAX_COST = 3.0e-4;
    /** Winner's cost divided by the runner-up's must be below this. */
    public static final double CONFIDENT_MAX_RATIO = 0.6;
    /** Standard deviation of the horizon's elevation angle across the view, degrees. */
    public static final double CONFIDENT_MIN_RELIEF_DEG = 1.0;

    /** A camera pose on the terrain plus how well it fits. */
    public static final class Match {
        /** Compass bearing of the camera's optical axis, degrees clockwise from north. */
        public final float bearingDeg;
        /** Pitch of the axis, degrees, positive looking up. */
        public final float pitchDeg;
        /** Vertical field of view of the photo, degrees. */
        public final float verticalFovDeg;
        /** Roll about the axis, degrees, positive = horizon tilts clockwise. */
        public final float rollDeg;
        /** Mean Huber residual (fraction of image height); 0 is a perfect fit. */
        public final double cost;
        /** Cost of the best pose pointing at least {@link #CANDIDATE_SEPARATION_DEG} away. */
        public final double runnerUpCost;
        /** Relief of the terrain horizon inside the matched view, degrees. */
        public final double reliefDeg;

        Match(float bearingDeg, float pitchDeg, float verticalFovDeg, float rollDeg,
              double cost, double runnerUpCost, double reliefDeg) {
            this.bearingDeg = bearingDeg;
            this.pitchDeg = pitchDeg;
            this.verticalFovDeg = verticalFovDeg;
            this.rollDeg = rollDeg;
            this.cost = cost;
            this.runnerUpCost = runnerUpCost;
            this.reliefDeg = reliefDeg;
        }

        /** How much better the winner is than the runner-up: below 1, smaller is clearer. */
        public double ratio() {
            return runnerUpCost <= 0 ? 1.0 : cost / runnerUpCost;
        }

        /** Whether the pose is trustworthy enough to offer to the user. */
        public boolean isConfident() {
            return cost <= CONFIDENT_MAX_COST
                    && ratio() <= CONFIDENT_MAX_RATIO
                    && reliefDeg >= CONFIDENT_MIN_RELIEF_DEG;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ENGLISH,
                    "bearing %.1f pitch %.1f vfov %.1f roll %.1f cost %.5f ratio %.2f relief %.2f%s",
                    bearingDeg, pitchDeg, verticalFovDeg, rollDeg, cost, ratio(), reliefDeg,
                    isConfident() ? " (confident)" : "");
        }
    }

    private final TerrainHorizon horizon;
    private final float[] skylineRows;
    private final float[] weights;
    private final int width;
    private final int height;

    /**
     * @param horizon      the terrain horizon around the camera position
     * @param skylineRows  the photo's skyline: the row (y, downward) of the sky/ground
     *                     boundary in every column, as {@link SkylineExtractor} returns it
     * @param confidence   per-column weight in [0, 1], same length
     * @param width        image width in pixels (columns)
     * @param height       image height in pixels
     */
    public SkylineMatcher(TerrainHorizon horizon, float[] skylineRows, float[] confidence,
                          int width, int height) {
        this.horizon = horizon;
        this.skylineRows = skylineRows;
        this.width = width;
        this.height = height;
        this.weights = new float[width];
        double sum = 0;
        for (int x = 0; x < width; x++) {
            sum += confidence[x];
        }
        float norm = sum > 0 ? (float) (width / sum) : 1f;
        for (int x = 0; x < width; x++) {
            weights[x] = confidence[x] * norm;
        }
    }

    /** Matches with no prior knowledge of the field of view. */
    public Match match() {
        return match(DEFAULT_VFOVS, VFOV_MIN, VFOV_MAX);
    }

    /**
     * Matches with a known vertical field of view (from the photo's focal length), letting
     * the refinement drift 15% either way to absorb cropping and sensor-size guesses.
     */
    public Match match(float verticalFovDeg) {
        return match(new float[]{verticalFovDeg}, verticalFovDeg * 0.85f, verticalFovDeg * 1.15f);
    }

    private Match match(float[] vfovs, float vfovMin, float vfovMax) {
        List<double[]> coarse = coarseSearch(vfovs);
        // Keep the best of each bearing cluster, so the refinement explores distinct places.
        List<double[]> candidates = new ArrayList<>();
        for (double[] c : coarse) {
            boolean distinct = true;
            for (double[] k : candidates) {
                if (bearingDistance(c[1], k[1]) < CANDIDATE_SEPARATION_DEG) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                candidates.add(c);
            }
            if (candidates.size() >= CANDIDATES_TO_REFINE) {
                break;
            }
        }
        List<double[]> refined = new ArrayList<>();
        for (double[] c : candidates) {
            refined.add(refine(c[1], c[2], c[3], c[4], vfovMin, vfovMax));
        }
        Collections.sort(refined, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        double[] best = refined.get(0);
        double runnerUp = Double.POSITIVE_INFINITY;
        for (int i = 1; i < refined.size(); i++) {
            if (bearingDistance(refined.get(i)[1], best[1]) >= CANDIDATE_SEPARATION_DEG) {
                runnerUp = refined.get(i)[0];
                break;
            }
        }
        double hfov = horizontalFov(best[3]);
        double relief = horizon.reliefDeg(best[1] - hfov / 2, best[1] + hfov / 2);
        return new Match((float) best[1], (float) best[2], (float) best[3], (float) best[4],
                best[0], runnerUp, relief);
    }

    private double horizontalFov(double vfovDeg) {
        double f = focalPx(vfovDeg);
        return Math.toDegrees(2 * Math.atan(width / 2.0 / f));
    }

    private double focalPx(double vfovDeg) {
        return (height / 2.0) / Math.tan(Math.toRadians(vfovDeg) / 2.0);
    }

    static double bearingDistance(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180 ? 360 - d : d;
    }

    private static double huber(double r) {
        double a = Math.abs(r);
        if (a > LOSS_CAP) {
            a = LOSS_CAP;
        }
        return a <= HUBER_DELTA ? 0.5 * a * a : HUBER_DELTA * (a - 0.5 * HUBER_DELTA);
    }

    /**
     * Every bearing at every field of view and every coarse roll, pitch solved as the
     * median offset. Two passes per triple, and BOTH are kept as candidates: first the
     * small-pitch approximation - a column is a bearing offset, a row an elevation offset -
     * then, with the pitch that gave, the exact direction of every skyline pixel through a
     * camera pitched that much. The approximation alone loses the true pose once the
     * camera looks well above the horizon (a village view up at 20-30 degree ridges): the
     * columns of a pitched camera sweep bearings faster than {@code atan(x/f)} says, and
     * the residual of the right bearing looked worse than that of a wrong one. The exact
     * pass alone, though, scored slightly worse on GeoPose3K than the approximation (its
     * pitch estimate can drift when the skyline is partly wrong), so the refinement gets
     * the union and the exact cost decides.
     *
     * <p>The roll is folded into the pixel coordinates once per (field of view, roll):
     * rotating each skyline pixel about the optical axis by minus the roll gives where it
     * would sit in a level camera, and both passes then run unchanged on those
     * coordinates - the roll costs nothing per bearing. Returns
     * {cost, bearing, pitch, vfov, roll} sorted by cost.
     */
    private List<double[]> coarseSearch(float[] vfovs) {
        List<double[]> results = new ArrayList<>();
        double[] xc = new double[width];
        double[] yc = new double[width];
        double[] colAz = new double[width];
        double[] rowEl = new double[width];
        double[] diff = new double[width];
        double[] sorted = new double[width];
        for (float vfov : vfovs) {
            double f = focalPx(vfov);
            for (float roll : COARSE_ROLLS_DEG) {
                double cosR = Math.cos(Math.toRadians(roll)), sinR = Math.sin(Math.toRadians(roll));
                for (int x = 0; x < width; x++) {
                    // The pixel in the rolled camera's frame, then in the level camera's: the
                    // inverse of the roll {@link #projectHorizon} applies to its right and up.
                    double xr = (x + 0.5 - width / 2.0) / f;
                    double yr = (height / 2.0 - skylineRows[x]) / f;
                    xc[x] = cosR * xr - sinR * yr;
                    yc[x] = sinR * xr + cosR * yr;
                    colAz[x] = Math.toDegrees(Math.atan(xc[x]));
                    rowEl[x] = Math.toDegrees(Math.atan(yc[x]));
                }
                for (double bearing = 0; bearing < 360; bearing += COARSE_STEP_DEG) {
                    // pass 1: small-pitch approximation
                    for (int x = 0; x < width; x++) {
                        diff[x] = horizon.angleAt(bearing + colAz[x]) - rowEl[x];
                    }
                    double pitch = median(diff, sorted);
                    double cost1 = 0;
                    for (int x = 0; x < width; x++) {
                        double r = f * Math.toRadians(diff[x] - pitch) / height;
                        cost1 += weights[x] * huber(r);
                    }
                    results.add(new double[]{cost1 / width, bearing, pitch, vfov, roll});
                    // pass 2: exact pixel directions for a camera pitched by that much
                    double ph = Math.toRadians(pitch);
                    double cosP = Math.cos(ph), sinP = Math.sin(ph);
                    for (int x = 0; x < width; x++) {
                        double forward = cosP - yc[x] * sinP;
                        double up = sinP + yc[x] * cosP;
                        double azOff = Math.toDegrees(Math.atan2(xc[x], forward));
                        double elObs = Math.toDegrees(Math.atan2(up, Math.hypot(xc[x], forward)));
                        diff[x] = horizon.angleAt(bearing + azOff) - elObs;
                    }
                    double shift = median(diff, sorted);
                    pitch += shift;
                    double cost = 0;
                    for (int x = 0; x < width; x++) {
                        double r = f * Math.toRadians(diff[x] - shift) / height;
                        cost += weights[x] * huber(r);
                    }
                    results.add(new double[]{cost / width, bearing, pitch, vfov, roll});
                }
            }
        }
        Collections.sort(results, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        return results;
    }

    /**
     * The upper median, by quickselect on a scratch copy. The coarse search takes tens of
     * thousands of medians of a few hundred values, and selecting is several times cheaper
     * than sorting; on a real device that is the difference the roll loop costs.
     */
    private static double median(double[] values, double[] scratch) {
        int n = values.length;
        System.arraycopy(values, 0, scratch, 0, n);
        int k = n / 2;
        int lo = 0, hi = n - 1;
        while (hi > lo) {
            // median-of-three pivot moved to the end
            int mid = (lo + hi) >>> 1;
            if (scratch[mid] < scratch[lo]) swap(scratch, mid, lo);
            if (scratch[hi] < scratch[lo]) swap(scratch, hi, lo);
            if (scratch[mid] < scratch[hi]) swap(scratch, mid, hi);
            double pivot = scratch[hi];
            int store = lo;
            for (int i = lo; i < hi; i++) {
                if (scratch[i] < pivot) {
                    swap(scratch, i, store);
                    store++;
                }
            }
            swap(scratch, store, hi);
            if (store == k) {
                break;
            } else if (store < k) {
                lo = store + 1;
            } else {
                hi = store - 1;
            }
        }
        return scratch[k];
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    /**
     * Projects the horizon through a pinhole camera and returns the predicted skyline row
     * per column ({@code NaN} where the horizon does not cover the column) - the ridge line
     * a photo taken with that pose would show, in this matcher's image size.
     */
    public float[] projectHorizon(double bearingDeg, double pitchDeg, double vfovDeg, double rollDeg) {
        double f = focalPx(vfovDeg);
        double th = Math.toRadians(bearingDeg);
        double ph = Math.toRadians(pitchDeg);
        double ro = Math.toRadians(rollDeg);
        // World axes east, north, up.
        double fx = Math.sin(th) * Math.cos(ph), fy = Math.cos(th) * Math.cos(ph), fz = Math.sin(ph);
        double rx = Math.cos(th), ry = -Math.sin(th), rz = 0;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        if (ro != 0) {
            double c = Math.cos(ro), s = Math.sin(ro);
            double nrx = c * rx + s * ux, nry = c * ry + s * uy, nrz = c * rz + s * uz;
            ux = -s * rx + c * ux;
            uy = -s * ry + c * uy;
            uz = -s * rz + c * uz;
            rx = nrx;
            ry = nry;
            rz = nrz;
        }
        int n = horizon.bins;
        // Projected samples, ordered by bearing; consecutive samples are consecutive in u as
        // long as the bearing is within the front hemisphere, which is all we keep.
        double[] us = new double[n + 1];
        double[] vs = new double[n + 1];
        boolean[] ok = new boolean[n + 1];
        for (int i = 0; i <= n; i++) {
            double az = Math.toRadians((i % n) * 360.0 / n);
            double el = Math.toRadians(horizon.angleDeg[i % n]);
            double dx = Math.cos(el) * Math.sin(az), dy = Math.cos(el) * Math.cos(az), dz = Math.sin(el);
            double depth = dx * fx + dy * fy + dz * fz;
            ok[i] = depth > 0.3;
            if (ok[i]) {
                us[i] = width / 2.0 + f * (dx * rx + dy * ry + dz * rz) / depth;
                vs[i] = height / 2.0 - f * (dx * ux + dy * uy + dz * uz) / depth;
            }
        }
        float[] rows = new float[width];
        Arrays.fill(rows, Float.NaN);
        for (int i = 0; i < n; i++) {
            if (!ok[i] || !ok[i + 1]) {
                continue;
            }
            double u0 = us[i], u1 = us[i + 1], v0 = vs[i], v1 = vs[i + 1];
            if (u1 < u0) {
                double t = u0; u0 = u1; u1 = t;
                t = v0; v0 = v1; v1 = t;
            }
            int x0 = Math.max(0, (int) Math.ceil(u0 - 0.5));
            int x1 = Math.min(width - 1, (int) Math.floor(u1 - 0.5));
            for (int x = x0; x <= x1; x++) {
                double t = u1 > u0 ? ((x + 0.5) - u0) / (u1 - u0) : 0;
                rows[x] = (float) (v0 + (v1 - v0) * t);
            }
        }
        return rows;
    }

    private double exactCost(double bearing, double pitch, double vfov, double roll) {
        float[] pred = projectHorizon(bearing, pitch, vfov, roll);
        double cost = 0, wsum = 0;
        int covered = 0;
        for (int x = 0; x < width; x++) {
            if (Float.isNaN(pred[x])) {
                continue;
            }
            covered++;
            double r = (skylineRows[x] - pred[x]) / height;
            cost += weights[x] * huber(r);
            wsum += weights[x];
        }
        if (covered < width / 2) {
            return Double.MAX_VALUE;
        }
        return cost / (wsum + 1e-9);
    }

    /** Coordinate descent with halving steps; returns {cost, bearing, pitch, vfov, roll}. */
    private double[] refine(double bearing, double pitch, double vfov, double roll,
                            double vfovMin, double vfovMax) {
        double[] p = {bearing, pitch, vfov, roll};
        double[] step = {1.0, 1.0, 2.0, 1.0};
        double[] lo = {Double.NEGATIVE_INFINITY, -PITCH_MAX, vfovMin, -ROLL_MAX};
        double[] hi = {Double.POSITIVE_INFINITY, PITCH_MAX, vfovMax, ROLL_MAX};
        double best = exactCost(p[0], p[1], p[2], p[3]);
        for (int iteration = 0; iteration < 60; iteration++) {
            boolean improved = false;
            for (int i = 0; i < 4; i++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double v = p[i] + sign * step[i];
                    if (v < lo[i] || v > hi[i]) {
                        continue;
                    }
                    double saved = p[i];
                    p[i] = v;
                    double c = exactCost(p[0], p[1], p[2], p[3]);
                    if (c < best) {
                        best = c;
                        improved = true;
                    } else {
                        p[i] = saved;
                    }
                }
            }
            if (!improved) {
                boolean done = true;
                for (int i = 0; i < 4; i++) {
                    step[i] *= 0.5;
                    if (step[i] >= 0.02) {
                        done = false;
                    }
                }
                if (done) {
                    break;
                }
            }
        }
        double b = p[0] % 360.0;
        if (b < 0) {
            b += 360.0;
        }
        return new double[]{best, b, p[1], p[2], p[3]};
    }
}
