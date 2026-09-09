package com.peaknav.stars;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds where a photograph of the night sky points: the compass bearing, pitch, roll and
 * vertical field of view of a pinhole camera that projects the stars of the sky - given as
 * directions in the local horizon frame, from {@code com.peaknav.sky} - onto the point
 * sources {@link StarExtractor} found in the picture. A small plate solver.
 *
 * <p>No learning, and no prior needed beyond the sky itself; a prior pose (where the app's
 * camera already points, when the phone is held up at the sky) only narrows the part of
 * the sky tried first. The method:
 * <ol>
 * <li>For each candidate field of view (the photo's own from its EXIF focal length, or a
 *     grid), every source becomes a direction in the camera frame, so angles between
 *     sources are real angles and can be compared with angles between stars.</li>
 * <li>Every triangle among the brightest sources is compared with every triangle among
 *     the brightest catalogue stars of the region by its three side lengths - the
 *     signature does not depend on where the camera points or how it is rolled. Each
 *     agreement proposes three correspondences, hence a rotation (two of the pairs fix
 *     it, the third checks it and the handedness rules out mirror images).</li>
 * <li>Every proposed rotation is verified by projecting the region's stars into the
 *     picture and counting the ones that land on a source. The pose with the most is the
 *     answer, refined by coordinate descent over bearing, pitch, roll and field of view
 *     on the pixel residuals of its matched pairs.</li>
 * </ol>
 *
 * <p>Confidence is a count and two ratios: enough stars matched, a fair share of the
 * bright stars the pose says should be in the field actually seen (the rest may be behind
 * clouds or trees), and a clear margin over the best pose pointing somewhere else. See the
 * constants below, and {@code TestStarMatcher} for what they were set on.
 */
public final class StarMatcher {

    /** Vertical fields of view tried when the photo carries no focal length (degrees). */
    private static final float[] DEFAULT_VFOVS = {22, 25, 28, 32, 36, 40, 45, 50, 55, 60, 66, 72, 78, 85, 92, 100};
    public static final float VFOV_MIN = 10f;
    public static final float VFOV_MAX = 110f;
    /** Brightest sources whose triangles are tried. */
    private static final int IMAGE_STARS = 14;
    /** Brightest catalogue stars of the region whose triangles are tried. */
    private static final int SKY_STARS_TRIANGLES = 40;
    /** Brightest catalogue stars of the region projected when a pose is verified. */
    private static final int SKY_STARS_VERIFY = 300;
    /** Proposed rotations verified per field of view, at most. */
    private static final int MAX_HYPOTHESES = 6000;
    /** Stars below the horizon by more than this are not in any picture (refraction lifts them a little). */
    private static final float MIN_ALTITUDE_DEG = -1f;
    /** How far from the prior direction the first pass looks, degrees, beyond the field of view itself. */
    public static final double DEFAULT_PRIOR_RADIUS_DEG = 30;
    /** Poses whose directions differ by more than this are distinct candidates. */
    private static final double CANDIDATE_SEPARATION_DEG = 5.0;
    /** A star counts as seen when a source lies within this fraction of the image height of it. */
    private static final double MATCH_TOLERANCE = 0.008;
    /** ...more generously while the field of view is still a grid guess. */
    private static final double COARSE_TOLERANCE = 0.016;

    // ---- acceptance thresholds ----
    /** Stars matched, at least. */
    public static final int CONFIDENT_MIN_MATCHES = 5;
    /** Matched stars over the bright stars the pose puts in the field, at least. */
    public static final double CONFIDENT_MIN_COMPLETENESS = 0.35;
    /** The runner-up's matches over the winner's, at most. */
    public static final double CONFIDENT_MAX_RUNNER_UP_RATIO = 0.6;
    /** Root-mean-square residual of the matched pairs, as a fraction of image height, at most. */
    public static final double CONFIDENT_MAX_RESIDUAL = 0.006;

    /** A camera pose on the sky plus how well it fits. */
    public static final class Match {
        /** Compass bearing of the camera's optical axis, degrees clockwise from north. */
        public final float bearingDeg;
        /** Pitch of the axis, degrees, positive looking up. */
        public final float pitchDeg;
        /** Roll about the axis, degrees, positive = the horizon tilts clockwise in the picture. */
        public final float rollDeg;
        /** Vertical field of view of the photo, degrees. */
        public final float verticalFovDeg;
        /** Catalogue stars found on a source. */
        public final int matched;
        /** Stars at least as bright as the faintest one matched that the pose puts inside the picture. */
        public final int expected;
        /** Root-mean-square pixel residual of the matched pairs, as a fraction of image height. */
        public final double residual;
        /** Stars matched by the best pose pointing at least {@link #CANDIDATE_SEPARATION_DEG} away. */
        public final int runnerUpMatched;
        /** For each source, the index of the catalogue star matched to it, or -1. */
        public final int[] pairs;

        Match(float bearingDeg, float pitchDeg, float rollDeg, float verticalFovDeg, int matched, int expected,
              double residual, int runnerUpMatched, int[] pairs) {
            this.bearingDeg = bearingDeg;
            this.pitchDeg = pitchDeg;
            this.rollDeg = rollDeg;
            this.verticalFovDeg = verticalFovDeg;
            this.matched = matched;
            this.expected = expected;
            this.residual = residual;
            this.runnerUpMatched = runnerUpMatched;
            this.pairs = pairs;
        }

        public double completeness() {
            return expected == 0 ? 0 : matched / (double) expected;
        }

        public double runnerUpRatio() {
            return matched == 0 ? 1 : runnerUpMatched / (double) matched;
        }

        public boolean isConfident() {
            return matched >= CONFIDENT_MIN_MATCHES
                    && completeness() >= CONFIDENT_MIN_COMPLETENESS
                    && runnerUpRatio() <= CONFIDENT_MAX_RUNNER_UP_RATIO
                    && residual <= CONFIDENT_MAX_RESIDUAL;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ENGLISH,
                    "bearing %.2f pitch %.2f roll %.2f vfov %.2f: %d of %d stars, residual %.4f, runner-up %d%s",
                    bearingDeg, pitchDeg, rollDeg, verticalFovDeg, matched, expected, residual, runnerUpMatched,
                    isConfident() ? " (confident)" : "");
        }
    }

    private final float[] srcX, srcY;
    private final int n;
    private final int width, height;
    private final float[] skyEnu, skyMag;
    private final int skyCount;

    /**
     * @param x, y   the sources' centres in pixels (y downwards), brightest first
     * @param width  the picture's width in pixels
     * @param height its height
     * @param skyEnu the sky's directions as east-north-up unit vectors, three per star
     * @param skyMag their magnitudes (smaller is brighter)
     * @param skyCount how many stars the two arrays hold
     */
    public StarMatcher(float[] x, float[] y, int width, int height, float[] skyEnu, float[] skyMag, int skyCount) {
        this.srcX = x;
        this.srcY = y;
        this.n = Math.min(x.length, y.length);
        this.width = width;
        this.height = height;
        this.skyEnu = skyEnu;
        this.skyMag = skyMag;
        this.skyCount = skyCount;
    }

    /** The sources of a field, in its order. */
    public static StarMatcher of(StarExtractor.Field field, float[] skyEnu, float[] skyMag, int skyCount) {
        float[] x = new float[field.sources.length], y = new float[field.sources.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = field.sources[i].x;
            y[i] = field.sources[i].y;
        }
        return new StarMatcher(x, y, field.width, field.height, skyEnu, skyMag, skyCount);
    }

    /**
     * Matches the whole visible sky, with the field of view searched.
     */
    public Match match() {
        return match(Double.NaN, Double.NaN, DEFAULT_PRIOR_RADIUS_DEG, Float.NaN);
    }

    /**
     * Matches the picture against the sky.
     *
     * @param priorBearingDeg  where the camera is thought to point (NaN for no idea): that part
     *                         of the sky is tried first, the whole visible sky next if it
     *                         gives nothing confident
     * @param priorPitchDeg    its pitch
     * @param priorRadiusDeg   how far from the prior the first pass looks, beyond the field of view
     * @param verticalFovDeg   the photo's vertical field of view when known, else NaN
     * @return the best pose, confident or not; null only when there are too few sources
     */
    public Match match(double priorBearingDeg, double priorPitchDeg, double priorRadiusDeg, float verticalFovDeg) {
        if (n < 3 || skyCount < 3) {
            return null;
        }
        boolean hasPrior = !Double.isNaN(priorBearingDeg) && !Double.isNaN(priorPitchDeg);
        Match near = hasPrior ? search(priorBearingDeg, priorPitchDeg, priorRadiusDeg, verticalFovDeg) : null;
        if (near != null && near.isConfident()) {
            return near;
        }
        Match wide = search(Double.NaN, Double.NaN, 0, verticalFovDeg);
        if (near == null) {
            return wide;
        }
        if (wide == null) {
            return near;
        }
        return wide.matched > near.matched || wide.isConfident() ? wide : near;
    }

    // ------------------------------------------------------------------------------------------
    // The search

    /** A verified pose. */
    private static final class Candidate {
        double bearing, pitch, roll, vfov;
        int matched;
        double residual;

        Candidate(double bearing, double pitch, double roll, double vfov, int matched, double residual) {
            this.bearing = bearing;
            this.pitch = pitch;
            this.roll = roll;
            this.vfov = vfov;
            this.matched = matched;
            this.residual = residual;
        }

        boolean betterThan(Candidate o) {
            return o == null || matched > o.matched || (matched == o.matched && residual < o.residual);
        }
    }

    private Match search(double priorBearing, double priorPitch, double priorRadius, float knownVfov) {
        float[] vfovs = Float.isNaN(knownVfov) ? DEFAULT_VFOVS : new float[]{knownVfov};
        boolean known = !Float.isNaN(knownVfov);
        double[] prior = Double.isNaN(priorBearing) ? null : forward(priorBearing, priorPitch);

        Candidate best = null, runnerUp = null;
        int k = Math.min(IMAGE_STARS, n);
        for (float vfov : vfovs) {
            double f = focalPx(vfov);
            double diag = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vfov) / 2)
                    * Math.hypot(width, height) / height));
            int[] region = region(prior, prior == null ? 180 : priorRadius + diag / 2);
            if (region.length < 3) {
                continue;
            }
            // The brightest sources as camera directions.
            double[][] img = new double[k][];
            for (int i = 0; i < k; i++) {
                img[i] = cameraDir(srcX[i], srcY[i], f);
            }
            Triangle[] imgTriangles = triangles(img, k, Double.MAX_VALUE);
            double[][] sky = new double[Math.min(SKY_STARS_TRIANGLES, region.length)][];
            for (int j = 0; j < sky.length; j++) {
                int s = region[j];
                sky[j] = new double[]{skyEnu[s * 3], skyEnu[s * 3 + 1], skyEnu[s * 3 + 2]};
            }
            Triangle[] skyTriangles = triangles(sky, sky.length, diag);
            int[] verify = Arrays.copyOf(region, Math.min(SKY_STARS_VERIFY, region.length));

            double tolSide = known ? 0.03 : 0.08;
            double tolRatio = known ? 0.02 : 0.035;
            double tolPx = (known ? MATCH_TOLERANCE : COARSE_TOLERANCE) * height;
            Set<Long> seen = new HashSet<Long>();
            int hypotheses = 0;
            for (Triangle t : imgTriangles) {
                for (Triangle u : skyTriangles) {
                    if (Math.abs(t.a - u.a) > tolSide * u.a + 0.1) {
                        continue;
                    }
                    if (Math.abs(t.b / t.a - u.b / u.a) > tolRatio || Math.abs(t.c / t.a - u.c / u.a) > tolRatio) {
                        continue;
                    }
                    // Vertex correspondence by the side each faces; a near-isosceles triangle
                    // also gets the swapped assignment of its two similar vertices.
                    int[][] orders = correspondences(t, u, tolRatio);
                    for (int[] order : orders) {
                        double[] c0 = img[t.v[0]], c1 = img[t.v[1]], c2 = img[t.v[2]];
                        double[] w0 = sky[u.v[order[0]]], w1 = sky[u.v[order[1]]], w2 = sky[u.v[order[2]]];
                        // A rotation keeps the handedness of any three vectors.
                        if (Math.signum(triple(c0, c1, c2)) != Math.signum(triple(w0, w1, w2))) {
                            continue;
                        }
                        double[] r = rotationFrom(c0, c1, w0, w1);
                        // The third vertex is the check.
                        double[] p2 = apply(r, c2);
                        if (angleDeg(p2, w2) > 0.08 * u.a + 0.3) {
                            continue;
                        }
                        double[] pose = poseOf(r);
                        long key = (Math.round(pose[0]) & 0xFFFF) << 32 | (Math.round(pose[1] + 90) & 0xFFFF) << 16
                                | (Math.round(pose[2] + 180) & 0xFFFF);
                        if (!seen.add(key)) {
                            continue;
                        }
                        if (++hypotheses > MAX_HYPOTHESES) {
                            break;
                        }
                        Candidate c = verify(pose[0], pose[1], pose[2], vfov, verify, tolPx, null);
                        if (c.matched < 3) {
                            continue;
                        }
                        // Best and runner-up, kept distinct in direction.
                        if (best == null || c.betterThan(best)) {
                            if (best != null && distinct(best, c)) {
                                runnerUp = best;
                            }
                            best = c;
                        } else if (distinct(best, c) && c.betterThan(runnerUp)) {
                            runnerUp = c;
                        }
                    }
                }
                if (hypotheses > MAX_HYPOTHESES) {
                    break;
                }
            }
        }
        if (best == null) {
            return null;
        }
        // Refine the winner on the exact projection, field of view included, against the
        // whole region at its field of view; then the numbers that decide.
        double refineDiag = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(VFOV_MAX) / 2)
                * Math.hypot(width, height) / height));
        int[] region = region(prior, prior == null ? 180 : priorRadius + refineDiag / 2);
        int[] verify = Arrays.copyOf(region, Math.min(SKY_STARS_VERIFY, region.length));
        Candidate refined = refine(best, verify, known);
        int[] pairs = new int[n];
        Candidate fin = verify(refined.bearing, refined.pitch, refined.roll, refined.vfov, verify,
                MATCH_TOLERANCE * height, pairs);
        int expected = expected(fin, verify, pairs);
        int runnerUpMatched = runnerUp == null || !distinct(fin, runnerUp) ? 0 : runnerUp.matched;
        return new Match((float) fin.bearing, (float) fin.pitch, (float) fin.roll, (float) fin.vfov, fin.matched,
                expected, fin.residual, runnerUpMatched, pairs);
    }

    /** The catalogue stars above the horizon within {@code radiusDeg} of {@code centre} (all of them for null), brightest first. */
    private int[] region(double[] centre, double radiusDeg) {
        double cosR = Math.cos(Math.toRadians(Math.min(180, radiusDeg)));
        double minUp = Math.sin(Math.toRadians(MIN_ALTITUDE_DEG));
        List<Integer> in = new ArrayList<Integer>();
        for (int s = 0; s < skyCount; s++) {
            double e = skyEnu[s * 3], nn = skyEnu[s * 3 + 1], u = skyEnu[s * 3 + 2];
            if (u < minUp) {
                continue;
            }
            if (centre != null && e * centre[0] + nn * centre[1] + u * centre[2] < cosR) {
                continue;
            }
            in.add(s);
        }
        Collections.sort(in, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(skyMag[a], skyMag[b]);
            }
        });
        int[] out = new int[in.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = in.get(i);
        }
        return out;
    }

    /** A triangle of three directions, sides sorted: a >= b >= c, and v[i] the vertex facing side i. */
    private static final class Triangle {
        final int[] v = new int[3];
        double a, b, c;
    }

    private static Triangle[] triangles(double[][] dirs, int count, double maxSideDeg) {
        List<Triangle> out = new ArrayList<Triangle>();
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                double dij = angleDeg(dirs[i], dirs[j]);
                if (dij > maxSideDeg) {
                    continue;
                }
                for (int k = j + 1; k < count; k++) {
                    double dik = angleDeg(dirs[i], dirs[k]);
                    double djk = angleDeg(dirs[j], dirs[k]);
                    if (dik > maxSideDeg || djk > maxSideDeg) {
                        continue;
                    }
                    // sides faced by i, j, k respectively
                    double[] side = {djk, dik, dij};
                    int[] idx = {i, j, k};
                    // sort descending by side
                    for (int p = 0; p < 2; p++) {
                        for (int q = p + 1; q < 3; q++) {
                            if (side[q] > side[p]) {
                                double ts = side[p]; side[p] = side[q]; side[q] = ts;
                                int ti = idx[p]; idx[p] = idx[q]; idx[q] = ti;
                            }
                        }
                    }
                    if (side[2] < 0.05) {
                        continue;   // two sources on top of each other
                    }
                    Triangle t = new Triangle();
                    t.a = side[0];
                    t.b = side[1];
                    t.c = side[2];
                    t.v[0] = idx[0];
                    t.v[1] = idx[1];
                    t.v[2] = idx[2];
                    out.add(t);
                }
            }
        }
        return out.toArray(new Triangle[out.size()]);
    }

    /**
     * How the sky triangle's vertices may map onto the image triangle's: by facing side, and
     * with the two vertices facing near-equal sides swapped as well.
     */
    private static int[][] correspondences(Triangle t, Triangle u, double tolRatio) {
        boolean ab = Math.abs(u.a - u.b) / u.a < 2 * tolRatio;
        boolean bc = Math.abs(u.b - u.c) / u.a < 2 * tolRatio;
        if (ab && bc) {
            return new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        }
        if (ab) {
            return new int[][]{{0, 1, 2}, {1, 0, 2}};
        }
        if (bc) {
            return new int[][]{{0, 1, 2}, {0, 2, 1}};
        }
        return new int[][]{{0, 1, 2}};
    }

    /**
     * Projects the region into the picture at a pose and counts the stars that land on a
     * source, each source taken by the brightest star claiming it.
     *
     * @param pairsOut when not null, receives the star index matched to each source, or -1
     */
    private Candidate verify(double bearing, double pitch, double roll, double vfov, int[] region, double tolPx,
                             int[] pairsOut) {
        double[] r = rotation(bearing, pitch, roll);
        double f = focalPx(vfov);
        boolean[] taken = new boolean[n];
        if (pairsOut != null) {
            Arrays.fill(pairsOut, -1);
        }
        int matched = 0;
        double sum2 = 0;
        double tol2 = tolPx * tolPx;
        double[] px = new double[2];
        for (int s : region) {
            if (!project(r, f, s, px)) {
                continue;
            }
            int bestI = -1;
            double bestD2 = tol2;
            for (int i = 0; i < n; i++) {
                if (taken[i]) {
                    continue;
                }
                double dx = srcX[i] - px[0], dy = srcY[i] - px[1];
                double d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestI = i;
                }
            }
            if (bestI >= 0) {
                taken[bestI] = true;
                matched++;
                sum2 += bestD2;
                if (pairsOut != null) {
                    pairsOut[bestI] = s;
                }
            }
        }
        double residual = matched == 0 ? 1 : Math.sqrt(sum2 / matched) / height;
        return new Candidate(bearing, pitch, roll, vfov, matched, residual);
    }

    /** Stars at least as bright as the faintest matched one that the pose puts inside the picture. */
    private int expected(Candidate c, int[] region, int[] pairs) {
        float faintest = -100;
        for (int s : pairs) {
            if (s >= 0) {
                faintest = Math.max(faintest, skyMag[s]);
            }
        }
        double[] r = rotation(c.bearing, c.pitch, c.roll);
        double f = focalPx(c.vfov);
        double[] px = new double[2];
        int count = 0;
        for (int s : region) {
            if (skyMag[s] <= faintest && project(r, f, s, px)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Coordinate descent over bearing, pitch, roll and field of view on the pixel residuals
     * of the matched pairs, re-matched between rounds so the pairs the coarse pose missed
     * (at the edges, where a wrong field of view moves stars most) join in.
     */
    private Candidate refine(Candidate start, int[] region, boolean knownVfov) {
        double[] p = {start.bearing, start.pitch, start.roll, start.vfov};
        double tolPx = COARSE_TOLERANCE * height;
        for (int round = 0; round < 4; round++) {
            int[] pairs = new int[n];
            verify(p[0], p[1], p[2], p[3], region, tolPx, pairs);
            List<int[]> matched = new ArrayList<int[]>();
            for (int i = 0; i < n; i++) {
                if (pairs[i] >= 0) {
                    matched.add(new int[]{i, pairs[i]});
                }
            }
            if (matched.size() < 2) {
                break;
            }
            double[] step = {0.5, 0.5, 0.5, knownVfov ? 0.5 : 2.0};
            double[] min = {0.002, 0.002, 0.002, 0.01};
            double cost = cost(p, matched);
            boolean any = true;
            for (int iter = 0; iter < 200 && any; iter++) {
                any = false;
                for (int d = 0; d < 4; d++) {
                    if (step[d] < min[d]) {
                        continue;
                    }
                    boolean improved = false;
                    for (int sign = -1; sign <= 1; sign += 2) {
                        double old = p[d];
                        p[d] = old + sign * step[d];
                        if (d == 3) {
                            p[d] = Math.max(VFOV_MIN, Math.min(VFOV_MAX, p[d]));
                        }
                        double c = cost(p, matched);
                        if (c < cost) {
                            cost = c;
                            improved = true;
                            break;
                        }
                        p[d] = old;
                    }
                    if (improved) {
                        any = true;
                    } else {
                        step[d] *= 0.5;
                        any = any || step[d] >= min[d];
                    }
                }
            }
            tolPx = MATCH_TOLERANCE * height * 1.5;
        }
        p[0] = ((p[0] % 360) + 360) % 360;
        return new Candidate(p[0], p[1], p[2], p[3], start.matched, start.residual);
    }

    /** Mean squared pixel distance of the pairs at a pose, as a fraction of the height squared. */
    private double cost(double[] p, List<int[]> pairs) {
        double[] r = rotation(p[0], p[1], p[2]);
        double f = focalPx(p[3]);
        double[] px = new double[2];
        double sum = 0;
        for (int[] pair : pairs) {
            if (!project(r, f, pair[1], px)) {
                sum += 1;   // behind the camera: as bad as it gets
                continue;
            }
            double dx = (srcX[pair[0]] - px[0]) / height, dy = (srcY[pair[0]] - px[1]) / height;
            sum += dx * dx + dy * dy;
        }
        return sum / pairs.size();
    }

    private static boolean distinct(Candidate a, Candidate b) {
        double[] fa = forward(a.bearing, a.pitch), fb = forward(b.bearing, b.pitch);
        return angleDeg(fa, fb) > CANDIDATE_SEPARATION_DEG
                || Math.abs(normDeg(a.roll - b.roll)) > CANDIDATE_SEPARATION_DEG;
    }

    // ------------------------------------------------------------------------------------------
    // Camera geometry. The camera frame is right-handed with x to the right of the picture, y
    // up it and z out of the back of the camera (a point in front has negative z); the world
    // frame is east, north, up. A rotation R (row-major 3x3) takes camera vectors to the world.

    private double focalPx(double vfovDeg) {
        return (height / 2.0) / Math.tan(Math.toRadians(vfovDeg) / 2);
    }

    /** The direction of a pixel in the camera frame. */
    private double[] cameraDir(double x, double y, double f) {
        double cx = (x - width / 2.0) / f, cy = -(y - height / 2.0) / f;
        double len = Math.sqrt(cx * cx + cy * cy + 1);
        return new double[]{cx / len, cy / len, -1 / len};
    }

    /** Projects catalogue star {@code s}; false when it is behind the camera or off the picture. */
    private boolean project(double[] r, double f, int s, double[] out) {
        double e = skyEnu[s * 3], nn = skyEnu[s * 3 + 1], u = skyEnu[s * 3 + 2];
        // camera = R^T world
        double cx = r[0] * e + r[3] * nn + r[6] * u;
        double cy = r[1] * e + r[4] * nn + r[7] * u;
        double cz = r[2] * e + r[5] * nn + r[8] * u;
        if (cz > -0.05) {
            return false;
        }
        double px = width / 2.0 + cx / -cz * f;
        double py = height / 2.0 - cy / -cz * f;
        if (px < 0 || px >= width || py < 0 || py >= height) {
            return false;
        }
        out[0] = px;
        out[1] = py;
        return true;
    }

    /** The world direction of a bearing and pitch. */
    static double[] forward(double bearingDeg, double pitchDeg) {
        double b = Math.toRadians(bearingDeg), p = Math.toRadians(pitchDeg);
        return new double[]{Math.sin(b) * Math.cos(p), Math.cos(b) * Math.cos(p), Math.sin(p)};
    }

    /** The rotation of a pose: columns right, up, back. */
    static double[] rotation(double bearingDeg, double pitchDeg, double rollDeg) {
        double[] fwd = forward(bearingDeg, pitchDeg);
        double[] right0 = normalize(cross(fwd, new double[]{0, 0, 1}));
        if (right0 == null) {
            right0 = new double[]{1, 0, 0};   // straight up or down: any right will do
        }
        double[] level = cross(right0, fwd);
        double roll = Math.toRadians(rollDeg);
        double[] up = normalize(new double[]{
                level[0] * Math.cos(roll) - right0[0] * Math.sin(roll),
                level[1] * Math.cos(roll) - right0[1] * Math.sin(roll),
                level[2] * Math.cos(roll) - right0[2] * Math.sin(roll)});
        double[] right = cross(fwd, up);
        return new double[]{
                right[0], up[0], -fwd[0],
                right[1], up[1], -fwd[1],
                right[2], up[2], -fwd[2]};
    }

    /** {bearing, pitch, roll} of a rotation, degrees. */
    static double[] poseOf(double[] r) {
        double[] fwd = {-r[2], -r[5], -r[8]};
        double[] up = {r[1], r[4], r[7]};
        double bearing = Math.toDegrees(Math.atan2(fwd[0], fwd[1]));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd[2]))));
        double[] right0 = normalize(cross(fwd, new double[]{0, 0, 1}));
        double roll = 0;
        if (right0 != null) {
            double[] level = cross(right0, fwd);
            roll = Math.toDegrees(Math.atan2(-dot(up, right0), dot(up, level)));
        }
        return new double[]{((bearing % 360) + 360) % 360, pitch, roll};
    }

    /**
     * The rotation taking camera directions c0, c1 to world directions w0, w1: the frame
     * built on each pair, one transposed onto the other. Exact when the two pairs are the
     * same angle apart; otherwise it splits the difference around the first vector.
     */
    static double[] rotationFrom(double[] c0, double[] c1, double[] w0, double[] w1) {
        double[] fc = frame(c0, c1), fw = frame(w0, w1);
        // R = Fw * Fc^T, both with basis vectors as columns
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += fw[i * 3 + k] * fc[j * 3 + k];
                }
                r[i * 3 + j] = s;
            }
        }
        return r;
    }

    /** An orthonormal frame with columns e1 = a, e2 in the plane of a and b, e3 = e1 x e2, row-major. */
    private static double[] frame(double[] a, double[] b) {
        double[] e1 = normalize(a);
        double d = dot(b, e1);
        double[] e2 = normalize(new double[]{b[0] - d * e1[0], b[1] - d * e1[1], b[2] - d * e1[2]});
        if (e2 == null) {
            e2 = normalize(cross(e1, Math.abs(e1[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0}));
        }
        double[] e3 = cross(e1, e2);
        return new double[]{
                e1[0], e2[0], e3[0],
                e1[1], e2[1], e3[1],
                e1[2], e2[2], e3[2]};
    }

    static double[] apply(double[] r, double[] v) {
        return new double[]{
                r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
                r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
                r[6] * v[0] + r[7] * v[1] + r[8] * v[2]};
    }

    static double angleDeg(double[] a, double[] b) {
        double d = dot(a, b) / Math.sqrt(dot(a, a) * dot(b, b));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d))));
    }

    private static double triple(double[] a, double[] b, double[] c) {
        return dot(a, cross(b, c));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(dot(v, v));
        if (len < 1e-9) {
            return null;
        }
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static double normDeg(double d) {
        d = d % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
