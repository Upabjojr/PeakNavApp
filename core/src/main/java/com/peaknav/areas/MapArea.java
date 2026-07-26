package com.peaknav.areas;

/**
 * A named area on the map approximated as an ellipse: a {@code type} (e.g. "island", "city",
 * "mountain_range", "region") that selects its label colour, a centre in degrees, semi-axes in
 * kilometres, the major axis rotated {@code rotationDeg} counter-clockwise from due East,
 * {@code peakMeters} — the elevation of the area's highest point, used to drop the label once the
 * whole area falls below the horizon — and {@code visibleRangeKm}, the relevance radius: the label
 * only appears while the viewer is within this distance, so tiny areas show only when you are near
 * and large ones from much farther. Loaded tile by tile from the slippy-map tree
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

    public MapArea(String name, String type, float lat, float lon,
                   float semiMajorKm, float semiMinorKm, float rotationDeg, float peakMeters,
                   float visibleRangeKm) {
        this.name = (name == null) ? "" : name;
        this.type = (type == null || type.isEmpty()) ? "island" : type;
        this.lat = lat;
        this.lon = lon;
        this.semiMajorKm = semiMajorKm;
        this.semiMinorKm = semiMinorKm;
        this.rotationDeg = rotationDeg;
        this.peakMeters = peakMeters;
        this.visibleRangeKm = (visibleRangeKm > 0f)
                ? visibleRangeKm : defaultRangeKm(semiMajorKm, peakMeters);
    }

    /**
     * Default relevance radius when {@code visibleRangeKm} is not given: grows with the area's size
     * and its height, so a big, tall range stays relevant much farther out than a small low islet.
     */
    private static float defaultRangeKm(float semiMajorKm, float peakMeters) {
        return 15f + semiMajorKm * 7f + peakMeters * 0.04f;
    }
}
