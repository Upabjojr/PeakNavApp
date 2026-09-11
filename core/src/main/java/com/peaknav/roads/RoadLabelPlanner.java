package com.peaknav.roads;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses where along each way its name and number may go.
 *
 * <p>Spots are laid out evenly along the way, centred on its length: every
 * {@link #ROAD_SPACING_METERS} along a road, every {@link #TRAIL_SPACING_METERS} along a trail
 * or track. That is the densest the user can ask for; the label frequency in the menu keeps
 * every first, second, fourth or eighth spot, counted from the way's middle so the labels stay
 * centred whatever the setting, and the spots kept alternate between a trail's number with its
 * name and its number alone (see {@link RoadLabelCandidate#label(int)}). Deciding that when
 * drawing rather than here is what lets the frequency slider act at once.
 *
 * <p>Each spot is kept by exactly one tile: the one whose box contains it, taking the boxes as
 * half-open so a point on a shared edge belongs to one side only. A way crossing four tiles is
 * therefore labelled where it is, not four times over. Around each spot the way is resampled
 * over {@link #HALF_WINDOW_METERS} on either side: the stretch the renderer projects to find
 * which way the text should lean.
 */
public final class RoadLabelPlanner {

    /** Distance between two spots along a road, at the highest label frequency. */
    public static final double ROAD_SPACING_METERS = 600.0;
    /** Distance between two spots along a trail or track, at the highest label frequency. */
    public static final double TRAIL_SPACING_METERS = 150.0;
    /**
     * Reach of the stretch around an anchor, each way: long enough to carry a trail's number and
     * name together without the renderer judging it seen end-on.
     */
    public static final double HALF_WINDOW_METERS = 180.0;
    /** Spacing of the samples within it. */
    public static final double SAMPLE_METERS = 15.0;
    /** Ways shorter than this are not labelled: the name would be longer than the way. */
    public static final double MIN_WAY_METERS = 40.0;
    /** Between a trail's number and its name, when a spot carries both. */
    public static final String NUMBER_NAME_SEPARATOR = " · ";

    private RoadLabelPlanner() {
    }

    public static List<RoadLabelCandidate> plan(List<RoadFeature> features,
                                                double north, double south,
                                                double east, double west) {
        List<RoadLabelCandidate> out = new ArrayList<>();
        for (RoadFeature f : features) {
            String name = blankToNull(f.name);
            boolean trail = f.roadClass == RoadClass.PATH || f.roadClass == RoadClass.TRACK;
            String number = trail ? blankToNull(f.number) : null;
            if ((name == null && number == null) || f.area
                    || f.roadClass == RoadClass.PISTE || f.size() < 2) {
                continue;
            }
            double[] along = cumulative(f);
            double length = along[along.length - 1];
            if (length < MIN_WAY_METERS) {
                continue;
            }
            double spacing = trail ? TRAIL_SPACING_METERS : ROAD_SPACING_METERS;
            // A hair of slack, so a way measured a rounding error short of a whole number of
            // spacings still gets the spot its length is worth.
            int count = Math.max(1, (int) Math.floor(length / spacing + 1e-6));
            double first = (length - (count - 1) * spacing) * 0.5;
            int middle = (count - 1) / 2;
            for (int k = 0; k < count; k++) {
                double s = first + k * spacing;
                double[] p = pointAt(f, along, s);
                if (!(p[0] >= south && p[0] < north && p[1] >= west && p[1] < east)) {
                    continue;
                }
                out.add(window(f, name, number, k - middle, along, s, length));
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static RoadLabelCandidate window(RoadFeature f, String name, String number, int spot,
                                             double[] along, double s, double length) {
        int half = (int) Math.round(HALF_WINDOW_METERS / SAMPLE_METERS);
        List<double[]> samples = new ArrayList<>(2 * half + 1);
        int anchor = 0;
        for (int j = -half; j <= half; j++) {
            double sj = s + j * SAMPLE_METERS;
            if (sj < 0 || sj > length) {
                continue;
            }
            if (j == 0) {
                anchor = samples.size();
            }
            samples.add(pointAt(f, along, sj));
        }
        double[] lat = new double[samples.size()];
        double[] lon = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            lat[i] = samples.get(i)[0];
            lon[i] = samples.get(i)[1];
        }
        return new RoadLabelCandidate(name, number, f.roadClass, f.attribute, lat, lon, anchor,
                length, spot);
    }

    static double[] cumulative(RoadFeature f) {
        double[] s = new double[f.size()];
        for (int i = 1; i < s.length; i++) {
            s[i] = s[i - 1] + RoadGeo.metersBetween(f.lat[i - 1], f.lon[i - 1], f.lat[i], f.lon[i]);
        }
        return s;
    }

    /** The point {@code s} metres along the way, as {lat, lon}. */
    static double[] pointAt(RoadFeature f, double[] along, double s) {
        int n = along.length;
        if (s <= 0) {
            return new double[]{f.lat[0], f.lon[0]};
        }
        if (s >= along[n - 1]) {
            return new double[]{f.lat[n - 1], f.lon[n - 1]};
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (along[mid] <= s) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double seg = along[hi] - along[lo];
        double t = seg <= 0 ? 0 : (s - along[lo]) / seg;
        return new double[]{
                f.lat[lo] + (f.lat[hi] - f.lat[lo]) * t,
                f.lon[lo] + (f.lon[hi] - f.lon[lo]) * t};
    }
}
