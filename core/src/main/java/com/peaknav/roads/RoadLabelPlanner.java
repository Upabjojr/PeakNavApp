package com.peaknav.roads;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses where along each way its name and number may go.
 *
 * <p>Spots are spread evenly along the way, centred on its length. A road gets its name every
 * {@link #ROAD_SPACING_METERS}. A trail or track gets a spot every
 * {@link #TRAIL_SPACING_METERS}, and the spots alternate: the name - with the number in front of
 * it when the trail has one - then the number alone, then the name again. So a numbered trail
 * shows its number every few hundred metres and its name every second spot, and a short trail
 * with a single spot carries both at once.
 *
 * <p>Each spot is kept by exactly one tile: the one whose box contains it, taking the boxes as
 * half-open so a point on a shared edge belongs to one side only. A way crossing four tiles is
 * therefore labelled where it is, not four times over. Around each spot the way is resampled
 * over {@link #HALF_WINDOW_METERS} on either side: the stretch the renderer projects to find
 * which way the text should lean.
 */
public final class RoadLabelPlanner {

    /** Distance between two names of the same road. */
    public static final double ROAD_SPACING_METERS = 1200.0;
    /** Distance between two labels of the same trail or track: names and numbers alternate. */
    public static final double TRAIL_SPACING_METERS = 300.0;
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
            for (int k = 0; k < count; k++) {
                String text;
                String shortText = null;
                boolean numberOnly;
                if (!trail) {
                    text = name;
                    numberOnly = false;
                } else if (k % 2 == 0 && name != null) {
                    text = number != null ? number + NUMBER_NAME_SEPARATOR + name : name;
                    shortText = number;
                    numberOnly = false;
                } else if (number != null) {
                    text = number;
                    numberOnly = true;
                } else {
                    continue; // a name-only trail is named every second spot
                }
                double s = first + k * spacing;
                double[] p = pointAt(f, along, s);
                if (!(p[0] >= south && p[0] < north && p[1] >= west && p[1] < east)) {
                    continue;
                }
                out.add(window(f, text, shortText, numberOnly, along, s, length));
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

    private static RoadLabelCandidate window(RoadFeature f, String text, String shortText,
                                             boolean numberOnly, double[] along, double s,
                                             double length) {
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
        return new RoadLabelCandidate(text, f.roadClass, f.attribute, lat, lon, anchor, length,
                numberOnly, shortText);
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
