package com.peaknav.gpx;

import com.peaknav.utils.PreferencesManager.UnitSystem;

import java.util.List;
import java.util.Locale;

/**
 * What the GPX info pane says about a track: where it starts and ends, how long it is, how much it
 * climbs and drops, how long it takes on foot, and its altimetric profile.
 *
 * <p>Walking time follows DIN 33466, the rule of the Alpine clubs' and Swiss trail signs: 4 km an
 * hour on the flat, 300 m an hour up and 500 m an hour down; the larger of the horizontal and
 * vertical times plus half the smaller. No breaks.
 *
 * <p>Ascent and descent ignore changes smaller than {@link #CLIMB_THRESHOLD_METRES} before they turn,
 * so a recorded track's noise - GPS altitude wanders by metres - is not counted as climbing.
 *
 * <p>Pure: no rendering, so it is covered by unit tests.
 */
public final class GpxTrackStats {

    /** A height that is not known, where a point has none and the terrain was not asked. */
    public interface Elevation {
        Float metres(double latitude, double longitude);
    }

    static final double CLIMB_THRESHOLD_METRES = 5.0;
    /** Points in {@link #profileMetres}. */
    public static final int PROFILE_SAMPLES = 160;

    private static final double FLAT_KMH = 4.0;
    private static final double ASCENT_METRES_PER_HOUR = 300.0;
    private static final double DESCENT_METRES_PER_HOUR = 500.0;

    public final String name;
    public final double startLat, startLon, endLat, endLon;
    public final double distanceMetres;
    public final double ascentMetres;
    public final double descentMetres;
    /** NaN when no point has a height. */
    public final double highestMetres;
    public final double lowestMetres;
    public final double walkingMinutes;
    /** Heights at {@link #PROFILE_SAMPLES} points evenly spaced along the track; null without heights. */
    public final float[] profileMetres;

    private GpxTrackStats(String name, double startLat, double startLon, double endLat, double endLon,
                          double distanceMetres, double ascentMetres, double descentMetres,
                          double highestMetres, double lowestMetres, float[] profileMetres) {
        this.name = name;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.distanceMetres = distanceMetres;
        this.ascentMetres = ascentMetres;
        this.descentMetres = descentMetres;
        this.highestMetres = highestMetres;
        this.lowestMetres = lowestMetres;
        this.profileMetres = profileMetres;
        this.walkingMinutes = walkingMinutes(distanceMetres, ascentMetres, descentMetres);
    }

    /** DIN 33466: the longer of the horizontal and vertical times, plus half the shorter. */
    static double walkingMinutes(double distanceMetres, double ascentMetres, double descentMetres) {
        double horizontal = distanceMetres / 1000.0 / FLAT_KMH * 60.0;
        double vertical = (ascentMetres / ASCENT_METRES_PER_HOUR + descentMetres / DESCENT_METRES_PER_HOUR) * 60.0;
        return Math.max(horizontal, vertical) + Math.min(horizontal, vertical) / 2.0;
    }

    /**
     * @param elevation asked for the height of points the GPX gives none; may be null
     * @return null for a track with fewer than two points
     */
    public static GpxTrackStats of(GpxTrack track, Elevation elevation) {
        List<GpxTrack.Point> points = track.getPoints();
        int n = points.size();
        if (n < 2) {
            return null;
        }
        double[] along = new double[n];
        double[] heights = new double[n];
        boolean anyHeight = false;
        for (int i = 0; i < n; i++) {
            GpxTrack.Point p = points.get(i);
            if (i > 0) {
                GpxTrack.Point q = points.get(i - 1);
                along[i] = along[i - 1] + metres(q.lat, q.lon, p.lat, p.lon);
            }
            Float h = p.hasElevation ? Float.valueOf(p.eleMeters)
                    : elevation == null ? null : elevation.metres(p.lat, p.lon);
            heights[i] = h == null || h.isNaN() ? Double.NaN : h;
            anyHeight |= !Double.isNaN(heights[i]);
        }

        double ascent = 0, descent = 0, highest = Double.NaN, lowest = Double.NaN;
        float[] profile = null;
        if (anyHeight) {
            fillGaps(heights);
            double anchor = heights[0];
            highest = lowest = heights[0];
            for (int i = 1; i < n; i++) {
                double h = heights[i];
                highest = Math.max(highest, h);
                lowest = Math.min(lowest, h);
                // Counted once the height has moved beyond the threshold from the last turn.
                if (h - anchor >= CLIMB_THRESHOLD_METRES) {
                    ascent += h - anchor;
                    anchor = h;
                } else if (anchor - h >= CLIMB_THRESHOLD_METRES) {
                    descent += anchor - h;
                    anchor = h;
                }
            }
            profile = resample(along, heights, PROFILE_SAMPLES);
        }
        GpxTrack.Point first = points.get(0);
        GpxTrack.Point last = points.get(n - 1);
        return new GpxTrackStats(track.getName(), first.lat, first.lon, last.lat, last.lon,
                along[n - 1], ascent, descent, highest, lowest, profile);
    }

    /** Points without a height take their neighbours' along the track. */
    private static void fillGaps(double[] heights) {
        int n = heights.length;
        int first = 0;
        while (first < n && Double.isNaN(heights[first])) {
            first++;
        }
        for (int i = 0; i < first; i++) {
            heights[i] = heights[first];
        }
        for (int i = first + 1; i < n; i++) {
            if (Double.isNaN(heights[i])) {
                heights[i] = heights[i - 1];
            }
        }
    }

    private static float[] resample(double[] along, double[] heights, int samples) {
        float[] out = new float[samples];
        double total = along[along.length - 1];
        int segment = 0;
        for (int s = 0; s < samples; s++) {
            double want = total * s / (samples - 1);
            while (segment < along.length - 2 && along[segment + 1] < want) {
                segment++;
            }
            double length = along[segment + 1] - along[segment];
            double t = length <= 0 ? 0 : Math.max(0, Math.min(1, (want - along[segment]) / length));
            out[s] = (float) (heights[segment] + t * (heights[segment + 1] - heights[segment]));
        }
        return out;
    }

    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    // --- formatting

    /** "12.4 km" or "7.7 mi"; under a kilometre (or a mile), in metres or feet. */
    public static String formatDistance(double metres, UnitSystem units) {
        if (units == UnitSystem.IMPERIAL) {
            double miles = metres / 1609.344;
            return miles < 1 ? Math.round(metres * 3.28084) + " ft" : String.format(Locale.ROOT, "%.1f mi", miles);
        }
        return metres < 1000 ? Math.round(metres) + " m" : String.format(Locale.ROOT, "%.1f km", metres / 1000);
    }

    /** "1 234 m" as a whole number of metres, or of feet. */
    public static String formatHeight(double metres, UnitSystem units) {
        if (Double.isNaN(metres)) {
            return "-";
        }
        return units == UnitSystem.IMPERIAL ? Math.round(metres * 3.28084) + " ft" : Math.round(metres) + " m";
    }

    /** "3 h 25 min", or "40 min" under an hour. */
    public static String formatDuration(double minutes) {
        long total = Math.round(minutes);
        long hours = total / 60, rest = total % 60;
        return hours == 0 ? rest + " min" : hours + " h " + (rest < 10 ? "0" : "") + rest + " min";
    }

    /** "46.02070 N, 7.74910 E". */
    public static String formatPosition(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.5f %s, %.5f %s", Math.abs(latitude), latitude >= 0 ? "N" : "S",
                Math.abs(longitude), longitude >= 0 ? "E" : "W");
    }
}
