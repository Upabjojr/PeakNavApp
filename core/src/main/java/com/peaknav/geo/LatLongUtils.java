package com.peaknav.geo;

/** The distances of {@link LatLong}, as functions of two points. */
public final class LatLongUtils {

    private LatLongUtils() {
    }

    /** See {@link LatLong#distance}: in degrees, as if latitude and longitude were flat. */
    public static double distance(LatLong a, LatLong b) {
        return a.distance(b);
    }

    /** See {@link LatLong#sphericalDistance}: in metres along the Earth's surface. */
    public static double sphericalDistance(LatLong a, LatLong b) {
        return a.sphericalDistance(b);
    }
}
