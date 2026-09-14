package com.peaknav.routing;

/**
 * How fast people walk on a slope: Tobler's hiking function, 6 e^(-3.5 |s + 0.05|) km/h for a
 * slope s (rise over run). Fastest, about 6 km/h, on a gentle descent of 5 %; 5 km/h on the flat;
 * slower the steeper it climbs, and slower again once a descent gets steep.
 *
 * <p>Used both to estimate how long a GPX track takes on foot and to find the quickest walk to a
 * point, so the two agree. No breaks.
 */
public final class WalkingSpeed {

    /** Slopes beyond this (45 degrees) are walked as if they were this steep. */
    static final double MAX_SLOPE = 1.0;
    /** Ground covered in one go when timing a track: short enough to follow the terrain, long enough to average GPS noise. */
    public static final double STRETCH_METRES = 50.0;

    private WalkingSpeed() {
    }

    /** Walking speed in km/h on a slope of {@code slope} (rise over run; negative going down). */
    public static double kmh(double slope) {
        double s = Math.max(-MAX_SLOPE, Math.min(MAX_SLOPE, slope));
        return 6.0 * Math.exp(-3.5 * Math.abs(s + 0.05));
    }

    /** The fastest {@link #kmh} gets, for an optimistic estimate of what is left of a walk. */
    public static double maxKmh() {
        return 6.0;
    }

    /** Seconds to walk {@code horizontalMetres} while rising {@code riseMetres} (negative: dropping). */
    public static double seconds(double horizontalMetres, double riseMetres) {
        if (horizontalMetres <= 0) {
            return Math.abs(riseMetres) <= 0 ? 0 : Math.abs(riseMetres) / (kmh(Math.signum(riseMetres) * MAX_SLOPE) / 3.6);
        }
        return horizontalMetres / (kmh(riseMetres / horizontalMetres) / 3.6);
    }
}
