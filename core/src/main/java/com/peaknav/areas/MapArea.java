package com.peaknav.areas;

/**
 * A named area on the map approximated as an ellipse: a {@code type} (e.g. "island", "city",
 * "mountain_range", "region") that selects its label colour, a centre in degrees, semi-axes in
 * kilometres, the major axis rotated {@code rotationDeg} counter-clockwise from due East,
 * {@code peakMeters} — the elevation of the area's highest point, used to drop the label once the
 * whole area falls below the horizon — and {@code visibleRangeKm}, the relevance radius: the label
 * only appears while the viewer is within this distance, so tiny areas show only when you are near
 * and large ones from much farther. For populated places the radius is also raised by
 * {@code population}, so a major city stays labelled from far off even when it sits on flat ground
 * (which would otherwise give it only a small prominence-based range). Loaded tile by tile from the
 * slippy-map tree
 * {@code areas/AREAS/<zoom>/<x/100>/<x%100>/<y/100>/<y%100>.json} by {@link AreaRegistry}; the
 * ellipse only locates/sizes the area on screen — it is not drawn.
 */
public class MapArea {

    public final String name;
    public final String type;
    public final float lat;
    public final float lon;
    public final float semiMajorKm;
    public final float semiMinorKm;
    public final float rotationDeg;
    public final float peakMeters;
    public final float visibleRangeKm;
    public final int population;

    public MapArea(String name, String type, float lat, float lon,
                   float semiMajorKm, float semiMinorKm, float rotationDeg, float peakMeters,
                   float visibleRangeKm, int population) {
        this.name = (name == null) ? "" : name;
        this.type = (type == null || type.isEmpty()) ? "island" : type;
        this.lat = lat;
        this.lon = lon;
        this.semiMajorKm = semiMajorKm;
        this.semiMinorKm = semiMinorKm;
        this.rotationDeg = rotationDeg;
        this.peakMeters = peakMeters;
        this.population = Math.max(0, population);
        float base = (visibleRangeKm > 0f) ? visibleRangeKm : defaultRangeKm(semiMajorKm, peakMeters);
        // A populous place must stay readable from far off regardless of terrain prominence: a flat
        // coastal city (small prominence range) would otherwise be culled long before a tiny hill
        // village. Take whichever radius is larger so nothing that used to show is lost.
        float range = Math.max(base, populationRangeKm(this.population));
        // Islands are wanted from far away — e.g. to name what you can see while sailing — so they
        // get a much bigger relevance radius (and a generous floor for the tiny ones).
        if ("island".equals(this.type)) {
            range = Math.max(range * 2.5f, 60f);
        }
        this.visibleRangeKm = range;
    }

    /**
     * Default relevance radius when {@code visibleRangeKm} is not given: grows with the area's size
     * and its height, so a big, tall range stays relevant much farther out than a small low islet.
     */
    private static float defaultRangeKm(float semiMajorKm, float peakMeters) {
        return 15f + semiMajorKm * 7f + peakMeters * 0.04f;
    }

    /**
     * Relevance radius earned by population alone (0 for unpopulated areas). Scales with the square
     * root of population so it grows fast for towns and levels off for big cities, e.g. ~27 km at
     * 5k people, ~48 km at 30k, ~73 km at 90k, ~92 km at 150k.
     */
    private static float populationRangeKm(int population) {
        if (population <= 0) return 0f;
        return 12f + 6.5f * (float) Math.sqrt(population / 1000.0);
    }
}
