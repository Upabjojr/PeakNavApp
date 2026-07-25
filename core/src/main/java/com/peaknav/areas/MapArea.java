package com.peaknav.areas;

/**
 * A named area on the map approximated as an ellipse: a {@code type} (e.g. "island",
 * "mountain_group", "region") that selects its label colour, a centre in degrees, semi-axes in
 * kilometres, the major axis rotated {@code rotationDeg} counter-clockwise from due East, and
 * {@code peakMeters} — the elevation of the area's highest point, used to drop the label once the
 * whole area falls below the horizon. Loaded from {@code areas.json} by {@link AreaRegistry}; the
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

    public MapArea(String name, String type, float lat, float lon,
                   float semiMajorKm, float semiMinorKm, float rotationDeg, float peakMeters) {
        this.name = (name == null) ? "" : name;
        this.type = (type == null || type.isEmpty()) ? "island" : type;
        this.lat = lat;
        this.lon = lon;
        this.semiMajorKm = semiMajorKm;
        this.semiMinorKm = semiMinorKm;
        this.rotationDeg = rotationDeg;
        this.peakMeters = peakMeters;
    }
}
