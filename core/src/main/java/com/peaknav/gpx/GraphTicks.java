package com.peaknav.gpx;

import com.peaknav.utils.PreferencesManager.UnitSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the GPX info pane's graphs put their axis labels: round heights in the units the user has
 * chosen up the side, and round times since the start along the bottom.
 *
 * <p>Pure: no rendering, so it is covered by unit tests.
 */
public final class GraphTicks {

    private static final double FEET_PER_METRE = 3.28084;
    /** Steps of time, in minutes, a time axis may count in; beyond them, whole days. */
    private static final int[] TIME_STEPS = {1, 2, 5, 10, 15, 20, 30, 60, 90, 120, 180, 240, 360, 480, 720, 1440};

    private GraphTicks() {
    }

    /**
     * Round heights between {@code lowMetres} and {@code highMetres} - 1, 2 or 5 times a power of
     * ten in metres or feet - at most {@code maxTicks} of them, returned in metres.
     */
    public static double[] heightTicksMetres(double lowMetres, double highMetres, UnitSystem units, int maxTicks) {
        double factor = units == UnitSystem.IMPERIAL ? FEET_PER_METRE : 1.0;
        double low = lowMetres * factor, high = highMetres * factor;
        double step = niceStep(Math.max(1.0, high - low) / Math.max(1, maxTicks));
        List<Double> ticks = new ArrayList<>();
        for (double v = Math.ceil(low / step) * step; v <= high + 1e-9 && ticks.size() < maxTicks; v += step) {
            ticks.add(v / factor);
        }
        return toArray(ticks);
    }

    /** Round times from 0 up to {@code totalMinutes}, at most {@code maxTicks} of them. */
    public static double[] timeTicksMinutes(double totalMinutes, int maxTicks) {
        double wanted = Math.max(1.0, totalMinutes) / Math.max(1, maxTicks);
        double step = Math.ceil(wanted / 1440) * 1440;
        for (int candidate : TIME_STEPS) {
            if (candidate >= wanted) {
                step = candidate;
                break;
            }
        }
        List<Double> ticks = new ArrayList<>();
        for (double v = 0; v <= totalMinutes + 1e-9 && ticks.size() < maxTicks; v += step) {
            ticks.add(v);
        }
        return toArray(ticks);
    }

    /** "0:00", "2:56", "25:10": hours and minutes. */
    public static String formatClock(double minutes) {
        long total = Math.round(minutes);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    /**
     * How far along the track, 0..1, it has been going for {@code minutes}, given the minutes at
     * evenly spaced points along it (never decreasing).
     */
    public static float fractionAt(float[] elapsedMinutes, double minutes) {
        int n = elapsedMinutes.length;
        if (n < 2 || minutes <= elapsedMinutes[0]) {
            return 0f;
        }
        for (int i = 1; i < n; i++) {
            if (elapsedMinutes[i] >= minutes) {
                float span = elapsedMinutes[i] - elapsedMinutes[i - 1];
                float t = span <= 0 ? 0f : (float) ((minutes - elapsedMinutes[i - 1]) / span);
                return (i - 1 + t) / (n - 1);
            }
        }
        return 1f;
    }

    /** The smallest of 1, 2 or 5 times a power of ten that is at least {@code atLeast}. */
    static double niceStep(double atLeast) {
        double power = Math.pow(10, Math.floor(Math.log10(atLeast)));
        for (double multiple : new double[]{1, 2, 5, 10}) {
            if (multiple * power >= atLeast - 1e-9) {
                return multiple * power;
            }
        }
        return 10 * power;
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
