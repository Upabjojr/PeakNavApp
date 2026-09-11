package com.peaknav.geo;

/**
 * A rectangle of latitude and longitude, edges included. Immutable; the corners must be real
 * places (see {@link LatLong}) and the minimum may not exceed the maximum, so a box never
 * crosses the antimeridian.
 */
public final class BoundingBox {

    public final double minLatitude;
    public final double minLongitude;
    public final double maxLatitude;
    public final double maxLongitude;

    public BoundingBox(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {
        LatLong.checkLatitude(minLatitude);
        LatLong.checkLongitude(minLongitude);
        LatLong.checkLatitude(maxLatitude);
        LatLong.checkLongitude(maxLongitude);
        if (minLatitude > maxLatitude) {
            throw new IllegalArgumentException("invalid latitude range: " + minLatitude + " " + maxLatitude);
        }
        if (minLongitude > maxLongitude) {
            throw new IllegalArgumentException("invalid longitude range: " + minLongitude + " " + maxLongitude);
        }
        this.minLatitude = minLatitude;
        this.minLongitude = minLongitude;
        this.maxLatitude = maxLatitude;
        this.maxLongitude = maxLongitude;
    }

    /** Whether the point lies inside the box or on its edge. */
    public boolean contains(double latitude, double longitude) {
        return latitude >= minLatitude && latitude <= maxLatitude
                && longitude >= minLongitude && longitude <= maxLongitude;
    }

    public boolean contains(LatLong point) {
        return contains(point.latitude, point.longitude);
    }

    /** The middle of the box, halfway across in latitude and in longitude. */
    public LatLong getCenterPoint() {
        return new LatLong(minLatitude + (maxLatitude - minLatitude) / 2.0,
                minLongitude + (maxLongitude - minLongitude) / 2.0);
    }

    /** Whether the two boxes share any point; boxes that only touch along an edge do. */
    public boolean intersects(BoundingBox other) {
        return this == other
                || (other.minLatitude <= maxLatitude && minLatitude <= other.maxLatitude
                && other.minLongitude <= maxLongitude && minLongitude <= other.maxLongitude);
    }

    /** The smallest box holding both this one and {@code other}. */
    public BoundingBox extendBoundingBox(BoundingBox other) {
        return new BoundingBox(Math.min(minLatitude, other.minLatitude),
                Math.min(minLongitude, other.minLongitude),
                Math.max(maxLatitude, other.maxLatitude),
                Math.max(maxLongitude, other.maxLongitude));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BoundingBox)) {
            return false;
        }
        BoundingBox b = (BoundingBox) o;
        return Double.compare(minLatitude, b.minLatitude) == 0
                && Double.compare(minLongitude, b.minLongitude) == 0
                && Double.compare(maxLatitude, b.maxLatitude) == 0
                && Double.compare(maxLongitude, b.maxLongitude) == 0;
    }

    @Override
    public int hashCode() {
        long h = Double.doubleToLongBits(minLatitude);
        h = h * 31 + Double.doubleToLongBits(minLongitude);
        h = h * 31 + Double.doubleToLongBits(maxLatitude);
        h = h * 31 + Double.doubleToLongBits(maxLongitude);
        return (int) (h ^ (h >>> 32));
    }

    @Override
    public String toString() {
        return "minLatitude=" + minLatitude + ", minLongitude=" + minLongitude
                + ", maxLatitude=" + maxLatitude + ", maxLongitude=" + maxLongitude;
    }
}
