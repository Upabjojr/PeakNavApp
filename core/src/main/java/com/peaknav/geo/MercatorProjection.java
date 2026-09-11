package com.peaknav.geo;

/**
 * The spherical ("web") Mercator projection the map tiles are cut in. At zoom level z the world
 * is 2^z by 2^z tiles: x counts east from the antimeridian, y counts south from the northern
 * edge of the map, at {@link #LATITUDE_MAX} - the latitude at which the projected world becomes
 * a square. Beyond it, north and south, there are no tiles.
 *
 * <p>In fractions of the map's width, a longitude lies at (lon + 180) / 360 across, and a
 * latitude at (1 - ln(tan φ + sec φ) / π) / 2 down; a tile's edges are those fractions of
 * the whole tile numbers, turned back into degrees by the inverse, φ = atan(sinh(π (1 - 2y))).
 */
public final class MercatorProjection {

    /** The northern edge of the map, in degrees, atan(sinh π); the southern is its negative. */
    public static final double LATITUDE_MAX = Math.toDegrees(Math.atan(Math.sinh(Math.PI)));

    private MercatorProjection() {
    }

    /** How many tiles span the world, either way, at this zoom level. */
    static long tilesAcross(byte zoomLevel) {
        return 1L << zoomLevel;
    }

    /**
     * The column of the tile holding this longitude. Longitudes past either edge give the
     * nearest column; 180 degrees, the east edge itself, belongs to the last one.
     */
    public static int longitudeToTileX(double longitude, byte zoomLevel) {
        long n = tilesAcross(zoomLevel);
        return tileIndex((longitude + 180.0) / 360.0 * n, n);
    }

    /**
     * The row of the tile holding this latitude. Latitudes beyond {@link #LATITUDE_MAX},
     * the poles included, give the first or last row.
     */
    public static int latitudeToTileY(double latitude, byte zoomLevel) {
        long n = tilesAcross(zoomLevel);
        // ln(tan φ + sec φ), written as half the log of (1 + sin φ) / (1 - sin φ): the same
        // quantity, and at the poles it runs to infinity rather than through tan(90°).
        double s = Math.sin(Math.toRadians(latitude));
        double down = 0.5 - Math.log((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI);
        return tileIndex(down * n, n);
    }

    /** The whole tile number a position in tiles falls in, kept within 0 .. n - 1. */
    private static int tileIndex(double position, long n) {
        if (!(position > 0.0)) {
            return 0; // west or north of the map, or NaN
        }
        if (position >= n) {
            return (int) (n - 1);
        }
        return (int) position; // positive, so the cast is the floor
    }

    /** The longitude of the western edge of tile column {@code tileX}. */
    public static double tileXToLongitude(long tileX, byte zoomLevel) {
        return tileX * 360.0 / tilesAcross(zoomLevel) - 180.0;
    }

    /** The latitude of the northern edge of tile row {@code tileY}. */
    public static double tileYToLatitude(long tileY, byte zoomLevel) {
        double y = Math.PI * (1.0 - 2.0 * tileY / tilesAcross(zoomLevel));
        return Math.toDegrees(Math.atan(Math.sinh(y)));
    }
}
