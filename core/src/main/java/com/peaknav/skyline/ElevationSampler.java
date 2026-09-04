package com.peaknav.skyline;

/**
 * Where the skyline code reads terrain heights from. The app implements it over its loaded
 * elevation tiles; tests implement it over the dataset files or an analytic landscape.
 */
public interface ElevationSampler {

    /**
     * Height of the ground at a coordinate, in metres above sea level, or {@code Float.NaN}
     * when nothing is known about that point (tile not loaded, no data).
     */
    float elevationMeters(double latitude, double longitude);
}
