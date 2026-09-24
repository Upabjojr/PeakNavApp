package com.peaknav.markers;

/**
 * A point of the user's own, kept from one run to the next and drawn on the map as a flag: where
 * the car is, a spring, a bivouac. Immutable; a marker is replaced, not changed.
 */
public final class Marker {

    public final String name;
    public final double latitude;
    public final double longitude;
    /** Metres above the sea, NaN when not known. */
    public final double elevation;
    /** When it was saved, milliseconds since the epoch; 0 when not known. */
    public final long created;

    public Marker(String name, double latitude, double longitude, double elevation, long created) {
        this.name = name == null ? "" : name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.elevation = elevation;
        this.created = created;
    }

    /** Whether two markers stand on the same spot, to a few centimetres. */
    public boolean samePlace(double lat, double lon) {
        return Math.abs(latitude - lat) < 1e-6 && Math.abs(longitude - lon) < 1e-6;
    }

    @Override
    public String toString() {
        return "Marker{" + name + " " + latitude + "," + longitude + " " + elevation + "}";
    }
}
