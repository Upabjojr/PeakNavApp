package com.peaknav.sky;

/**
 * Time and spherical-astronomy helpers, following Paul Schlyter's public-domain
 * "How to compute planetary positions" (http://stjarnhimlen.se/comp/ppcomp.html).
 *
 * <p>Angles are in degrees unless noted. The horizontal frame produced here matches the app's world
 * axes (azimuth 0 = North, 90 = East; altitude 0 = horizon, 90 = zenith), so an (azimuth, altitude)
 * pair can be fed straight into {@link com.peaknav.viewer.SunLight#setFromAzimuthAltitude}.
 */
public final class SkyMath {

    private SkyMath() {}

    static final double DEG2RAD = Math.PI / 180.0;
    static final double RAD2DEG = 180.0 / Math.PI;

    /** Normalise an angle to [0, 360). */
    static double rev(double x) {
        double r = x - Math.floor(x / 360.0) * 360.0;
        return r < 0 ? r + 360.0 : r;
    }

    static double sind(double d) { return Math.sin(d * DEG2RAD); }
    static double cosd(double d) { return Math.cos(d * DEG2RAD); }
    static double tand(double d) { return Math.tan(d * DEG2RAD); }
    static double asind(double x) { return Math.asin(x) * RAD2DEG; }
    static double atan2d(double y, double x) { return Math.atan2(y, x) * RAD2DEG; }

    /**
     * Schlyter's day number: days (and fraction) since the epoch 2000 Jan 0.0 UT
     * (= 1999 Dec 31, 00:00 UT), computed from a UTC instant.
     */
    public static double dayNumber(int year, int month, int day, double utHours) {
        // Integer arithmetic exactly as specified by Schlyter.
        long d = 367L * year
                - (7L * (year + (month + 9) / 12)) / 4
                + (275L * month) / 9
                + day - 730530L;
        return d + utHours / 24.0;
    }

    /** Obliquity of the ecliptic for day number d, in degrees. */
    public static double obliquity(double d) {
        return 23.4393 - 3.563e-7 * d;
    }

    /**
     * Local sidereal time in degrees, from the Sun's mean longitude (Schlyter's GMST0 trick).
     * @param sunMeanLongitude the Sun's mean longitude Ls in degrees (w + M of the Sun)
     * @param utHours          UT hours
     * @param longitudeDeg     observer longitude, east positive, degrees
     */
    public static double localSiderealTimeDeg(double sunMeanLongitude, double utHours, double longitudeDeg) {
        double gmst0Hours = (rev(sunMeanLongitude) + 180.0) / 15.0; // hours
        double lstHours = gmst0Hours + utHours + longitudeDeg / 15.0;
        return rev(lstHours * 15.0);
    }

    /** Result of an equatorial→horizontal conversion. */
    public static final class AzAlt {
        public final double azimuth;   // degrees, 0 = North, 90 = East
        public final double altitude;  // degrees above the horizon
        public AzAlt(double azimuth, double altitude) { this.azimuth = azimuth; this.altitude = altitude; }
    }

    /**
     * Converts equatorial (RA, Dec in degrees) to horizontal (azimuth, altitude) for an observer.
     * @param raDeg          right ascension, degrees
     * @param decDeg         declination, degrees
     * @param lstDeg         local sidereal time, degrees
     * @param latitudeDeg    observer latitude, degrees
     */
    public static AzAlt equatorialToHorizontal(double raDeg, double decDeg, double lstDeg, double latitudeDeg) {
        double ha = rev(lstDeg - raDeg); // hour angle, degrees
        double x = cosd(ha) * cosd(decDeg);
        double y = sind(ha) * cosd(decDeg);
        double z = sind(decDeg);
        double xhor = x * sind(latitudeDeg) - z * cosd(latitudeDeg);
        double yhor = y;
        double zhor = x * cosd(latitudeDeg) + z * sind(latitudeDeg);
        double azimuth = rev(atan2d(yhor, xhor) + 180.0);
        double altitude = atan2d(zhor, Math.sqrt(xhor * xhor + yhor * yhor));
        return new AzAlt(azimuth, altitude);
    }
}
