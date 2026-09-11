package com.peaknav.roads;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses where along each named way its name may go.
 *
 * <p>Anchors are spread evenly along the way - one in the middle of a short street, one every
 * {@link #SPACING_METERS} along a long trail - and each is kept by exactly one tile: the one
 * whose box contains it, taking the boxes as half-open so a point on a shared edge belongs to
 * one side only. A way crossing four tiles is therefore labelled where it is, not four times at
 * the same spot. Around each anchor the way is resampled over {@link #HALF_WINDOW_METERS} on
 * either side: the stretch the renderer projects to find which way the text should lean.
 */
public final class RoadLabelPlanner {

    /** Distance between two labels of the same way. */
    public static final double SPACING_METERS = 1200.0;
    /** Reach of the stretch around an anchor, each way. */
    public static final double HALF_WINDOW_METERS = 120.0;
    /** Spacing of the samples within it. */
    public static final double SAMPLE_METERS = 15.0;
    /** Ways shorter than this are not labelled: the name would be longer than the way. */
    public static final double MIN_WAY_METERS = 40.0;

    private RoadLabelPlanner() {
    }

    public static List<RoadLabelCandidate> plan(List<RoadFeature> features,
                                                double north, double south,
                                                double east, double west) {
        List<RoadLabelCandidate> out = new ArrayList<>();
        for (RoadFeature f : features) {
            if (f.name == null || f.name.trim().isEmpty() || f.area
                    || f.roadClass == RoadClass.PISTE || f.size() < 2) {
                continue;
            }
            double[] along = cumulative(f);
            double length = along[along.length - 1];
            if (length < MIN_WAY_METERS) {
                continue;
            }
            int count = Math.max(1, (int) Math.floor(length / SPACING_METERS));
            double first = (length - (count - 1) * SPACING_METERS) * 0.5;
            for (int k = 0; k < count; k++) {
                double s = first + k * SPACING_METERS;
                double[] p = pointAt(f, along, s);
                if (!(p[0] >= south && p[0] < north && p[1] >= west && p[1] < east)) {
                    continue;
                }
                out.add(window(f, along, s, length));
            }
        }
        return out;
    }

    private static RoadLabelCandidate window(RoadFeature f, double[] along, double s, double length) {
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
        return new RoadLabelCandidate(f.name.trim(), f.roadClass, f.attribute, lat, lon, anchor,
                length);
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
