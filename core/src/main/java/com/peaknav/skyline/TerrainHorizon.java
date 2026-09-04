package com.peaknav.skyline;

/**
 * The terrain's skyline all around an observer: for every compass direction, the elevation
 * angle of the highest piece of ground along that bearing. This is what a photo's skyline is
 * matched against - a full-circle panorama of the horizon computed from the elevation model,
 * at the resolution of the model.
 *
 * <p>Computed by marching a ray outward in each direction and keeping the largest angle
 * {@code atan2(height - eye - curvatureDrop, distance)} met on the way. Distances grow
 * geometrically, so the sampling density roughly follows the elevation model's own angular
 * resolution instead of wasting samples far away. Earth curvature and standard atmospheric
 * refraction (coefficient 0.13) are applied as the usual drop of {@code d²/2R}.
 *
 * <p>The march starts a little way out ({@link #MIN_DISTANCE_M}): at a 30 m grid the first
 * pixels around the observer are unreliable - one rough pixel 25 m away subtends fifteen
 * degrees and would wall off the real skyline, which is exactly what happened on the first
 * photo this was tried on.
 */
public final class TerrainHorizon {

    /** Mean Earth radius in metres. */
    public static final double EARTH_RADIUS_M = 6371000.0;
    /** Terrestrial refraction lifts distant terrain a little; the standard coefficient. */
    public static final double REFRACTION_COEFFICIENT = 0.13;
    /** Nearest ground the march considers. */
    public static final double MIN_DISTANCE_M = 120.0;
    /** Farthest ground the march considers - ~120 km sees the far Alps from a summit. */
    public static final double MAX_DISTANCE_M = 120000.0;
    /** Step growth: the angular spacing of samples, about a third of a horizon bin. */
    private static final double STEP_FRACTION = 0.006;
    private static final double MIN_STEP_M = 15.0;

    /** Number of azimuth bins around the circle. Bin {@code i} is bearing {@code i * 360 / bins}. */
    public final int bins;
    /** Elevation angle of the horizon in each bin, degrees, positive above the horizontal. */
    public final float[] angleDeg;
    /** Distance (metres) of the terrain that forms the horizon in each bin. */
    public final float[] distanceM;
    /** Height of the observer's eye, metres above sea level. */
    public final double eyeMeters;
    /** Fraction of ray samples that found elevation data; below ~0.9 the horizon has holes. */
    public final double coverage;

    private TerrainHorizon(int bins, float[] angleDeg, float[] distanceM, double eyeMeters,
                           double coverage) {
        this.bins = bins;
        this.angleDeg = angleDeg;
        this.distanceM = distanceM;
        this.eyeMeters = eyeMeters;
        this.coverage = coverage;
    }

    /**
     * Computes the horizon around a point.
     *
     * @param dem            the elevation model
     * @param latitude       observer latitude, degrees
     * @param longitude      observer longitude, degrees
     * @param eyeAboveGround height of the eye above the ground at the observer, metres - the
     *                       app's camera sits 20 m up, a person 1.7 m
     * @param bins           azimuth bins; 720 gives half-degree steps
     */
    public static TerrainHorizon compute(ElevationSampler dem, double latitude, double longitude,
                                         double eyeAboveGround, int bins) {
        float ground = dem.elevationMeters(latitude, longitude);
        if (Float.isNaN(ground)) {
            ground = 0f;
        }
        double eye = ground + eyeAboveGround;
        double effectiveRadius = EARTH_RADIUS_M / (1.0 - REFRACTION_COEFFICIENT);

        double metersPerDegLat = 2 * Math.PI * EARTH_RADIUS_M / 360.0;
        double metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(latitude));

        float[] angle = new float[bins];
        float[] distance = new float[bins];
        long samples = 0;
        long covered = 0;
        for (int i = 0; i < bins; i++) {
            double bearing = Math.toRadians(i * 360.0 / bins);
            double east = Math.sin(bearing);
            double north = Math.cos(bearing);
            double best = -90.0;
            double bestDistance = MAX_DISTANCE_M;
            for (double d = MIN_DISTANCE_M; d < MAX_DISTANCE_M; d += Math.max(MIN_STEP_M, d * STEP_FRACTION)) {
                double lat = latitude + north * d / metersPerDegLat;
                double lon = longitude + east * d / metersPerDegLon;
                float h = dem.elevationMeters(lat, lon);
                samples++;
                if (Float.isNaN(h)) {
                    continue;
                }
                covered++;
                double drop = d * d / (2 * effectiveRadius);
                double a = Math.toDegrees(Math.atan2(h - eye - drop, d));
                if (a > best) {
                    best = a;
                    bestDistance = d;
                }
            }
            angle[i] = (float) best;
            distance[i] = (float) bestDistance;
        }
        double coverage = samples == 0 ? 0 : covered / (double) samples;
        return new TerrainHorizon(bins, angle, distance, eye, coverage);
    }

    /** The horizon's elevation angle at any bearing, linearly interpolated between bins. */
    public float angleAt(double bearingDeg) {
        double b = bearingDeg / 360.0 * bins;
        b -= Math.floor(b / bins) * bins;
        int i0 = (int) Math.floor(b);
        double t = b - i0;
        i0 %= bins;
        int i1 = (i0 + 1) % bins;
        return (float) (angleDeg[i0] * (1 - t) + angleDeg[i1] * t);
    }

    /**
     * How much the horizon varies over a range of bearings, degrees (standard deviation of
     * the elevation angle). A flat horizon - the sea, a plain - carries no bearing information.
     */
    public double reliefDeg(double fromBearingDeg, double toBearingDeg) {
        int n = 0;
        double sum = 0, sum2 = 0;
        double span = toBearingDeg - fromBearingDeg;
        if (span < 0) {
            span += 360;
        }
        double step = 360.0 / bins;
        for (double b = 0; b <= span; b += step) {
            float a = angleAt(fromBearingDeg + b);
            sum += a;
            sum2 += a * a;
            n++;
        }
        if (n < 2) {
            return 0;
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sum2 / n - mean * mean));
    }
}
