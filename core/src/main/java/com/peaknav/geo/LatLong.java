package com.peaknav.geo;

/**
 * A place on the Earth, in degrees: latitude north of the equator, longitude east of Greenwich.
 *
 * <p>Immutable. A latitude outside [-90, 90] or a longitude outside [-180, 180], NaN included,
 * is refused when the point is made, so every LatLong the app holds is a real place. Not final:
 * {@code LatLongEle} adds an elevation to it.
 */
public class LatLong {

    /**
     * The radius of the sphere {@link #sphericalDistance} measures along: the WGS 84 equatorial
     * radius, in metres - a degree of longitude on the equator is then 111,319.49 m.
     */
    public static final double EARTH_RADIUS_METERS = 6378137.0;

    public final double latitude;
    public final double longitude;

    public LatLong(double latitude, double longitude) {
        this.latitude = checkLatitude(latitude);
        this.longitude = checkLongitude(longitude);
    }

    static double checkLatitude(double latitude) {
        if (!(latitude >= -90.0 && latitude <= 90.0)) {
            throw new IllegalArgumentException("invalid latitude: " + latitude);
        }
        return latitude;
    }

    static double checkLongitude(double longitude) {
        if (!(longitude >= -180.0 && longitude <= 180.0)) {
            throw new IllegalArgumentException("invalid longitude: " + longitude);
        }
        return longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    /**
     * How far {@code other} is in the plane of degrees, latitude and longitude taken as flat
     * coordinates. Not a length on the ground - a degree of longitude shrinks towards the poles -
     * but what the tile code ranks tiles and cut-offs by.
     */
    public double distance(LatLong other) {
        return Math.hypot(other.longitude - longitude, other.latitude - latitude);
    }

    /**
     * The great-circle distance to {@code other}, in metres, on a sphere of
     * {@link #EARTH_RADIUS_METERS}: the haversine formula, which stays accurate for points a few
     * metres apart where the spherical law of cosines loses its digits.
     */
    public double sphericalDistance(LatLong other) {
        double phi1 = Math.toRadians(latitude);
        double phi2 = Math.toRadians(other.latitude);
        double sinHalfDPhi = Math.sin((phi2 - phi1) / 2.0);
        double sinHalfDLambda = Math.sin(Math.toRadians(other.longitude - longitude) / 2.0);
        double h = sinHalfDPhi * sinHalfDPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfDLambda * sinHalfDLambda;
        return 2.0 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LatLong)) {
            return false;
        }
        LatLong p = (LatLong) o;
        return Double.doubleToLongBits(latitude) == Double.doubleToLongBits(p.latitude)
                && Double.doubleToLongBits(longitude) == Double.doubleToLongBits(p.longitude);
    }

    @Override
    public int hashCode() {
        long bits = Double.doubleToLongBits(latitude) * 31 + Double.doubleToLongBits(longitude);
        return (int) (bits ^ (bits >>> 32));
    }

    @Override
    public String toString() {
        return "latitude=" + latitude + ", longitude=" + longitude;
    }
}
