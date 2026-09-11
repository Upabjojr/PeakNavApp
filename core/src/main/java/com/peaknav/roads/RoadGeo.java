package com.peaknav.roads;

/** The little geodesy the road renderer needs: short distances, where a flat earth is exact enough. */
public final class RoadGeo {

    /** Metres per degree of latitude, and of longitude at the equator. */
    public static final double METERS_PER_DEGREE = 111320.0;

    private RoadGeo() {
    }

    /**
     * Ground distance between two nearby points, by the equirectangular approximation. Over the
     * few hundred metres between two nodes of a way it is off by far less than a texel.
     */
    public static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double meanLat = Math.toRadians((lat1 + lat2) * 0.5);
        double dy = (lat2 - lat1) * METERS_PER_DEGREE;
        double dx = (lon2 - lon1) * METERS_PER_DEGREE * Math.cos(meanLat);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Metres covered by one texel of a {@code res}-texel texture laid over this box. Slippy-map
     * tiles are close to square on the ground, so the east-west and north-south sizes are
     * averaged into one figure.
     */
    public static double metersPerTexel(double north, double south, double east, double west, int res) {
        double midLat = Math.toRadians((north + south) * 0.5);
        double x = (east - west) * METERS_PER_DEGREE * Math.cos(midLat) / res;
        double y = (north - south) * METERS_PER_DEGREE / res;
        return 0.5 * (x + y);
    }
}
