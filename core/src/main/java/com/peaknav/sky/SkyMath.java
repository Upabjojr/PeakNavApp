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
     * How far below level the horizon lies, seen from a given height, in degrees.
     *
     * <p>At sea level it is zero and the horizon is exactly level. Climb, and it drops away:
     * 1.0 degrees from 1 km, 1.8 from 3 km, 3.2 from 10 km. Two things depend on it - how much
     * sky there is below level to draw, and the fact that the Sun sets later the higher you
     * stand, which is why a mountain sunset does not match a sea-level almanac.
     *
     * @param altitudeMeters height above sea level; zero or below gives zero
     */
    public static double horizonDipDegrees(double altitudeMeters) {
        if (altitudeMeters <= 0) {
            return 0;
        }
        double r = com.peaknav.utils.Units.radiusOfEarth;
        return Math.toDegrees(Math.acos(r / (r + altitudeMeters)));
    }

    /**
     * Moves a geocentric position to the observer's own place on the Earth's surface.
     *
     * <p>Everything else in the sky is far enough away that it makes no difference - the Sun
     * shifts by 9 arcseconds, the planets by less. The Moon is the exception: it is only about
     * 60 Earth radii away, so standing on the surface rather than at the centre moves it by up
     * to <b>57 arcminutes</b>, close to twice the Sun's apparent width, and most of all when it
     * is near the horizon.
     *
     * <p>Without this, a solar eclipse comes out wrong in the most visible way possible: the
     * Sun and Moon fail to overlap. It is the difference between drawing where the Moon is and
     * drawing where it is seen.
     *
     * <p>Done as vectors rather than through Schlyter's trigonometric form, which divides by
     * sin(g) and so has to be special-cased near the equator.
     *
     * @param raDeg               geocentric right ascension, degrees
     * @param decDeg              geocentric declination, degrees
     * @param distanceEarthRadii  distance in Earth radii (about 60 for the Moon)
     * @param lstDeg              local sidereal time, degrees
     * @param latitudeDeg         observer latitude, degrees
     * @return {right ascension, declination} as seen from the observer, in degrees
     */
    public static double[] topocentric(double raDeg, double decDeg, double distanceEarthRadii,
                                       double lstDeg, double latitudeDeg) {
        if (distanceEarthRadii <= 0) {
            return new double[]{raDeg, decDeg};
        }
        // The Earth is flattened: the geocentric latitude differs from the geographic one by up
        // to 11 arcminutes, and the radius at the observer from the equatorial radius by 0.3%.
        double gclat = latitudeDeg - 0.1924 * sind(2 * latitudeDeg);
        double rho = 0.99833 + 0.00167 * cosd(2 * latitudeDeg);

        // Both in the same rectangular frame, in Earth radii; subtract, and read the direction.
        double mx = distanceEarthRadii * cosd(decDeg) * cosd(raDeg);
        double my = distanceEarthRadii * cosd(decDeg) * sind(raDeg);
        double mz = distanceEarthRadii * sind(decDeg);

        double ox = rho * cosd(gclat) * cosd(lstDeg);
        double oy = rho * cosd(gclat) * sind(lstDeg);
        double oz = rho * sind(gclat);

        double x = mx - ox, y = my - oy, z = mz - oz;
        return new double[]{rev(atan2d(y, x)), atan2d(z, Math.sqrt(x * x + y * y))};
    }

    /**
     * Turns a point on the ecliptic into equatorial coordinates.
     *
     * <p>The ecliptic is where the Sun, Moon and planets are found, so drawing it shows the
     * lane they travel along. A point on it has ecliptic latitude 0 and is tilted onto the
     * equator by the obliquity.
     *
     * @param lambdaDeg    ecliptic longitude, degrees
     * @param obliquityDeg tilt of the ecliptic to the equator, from {@link #obliquity}
     * @return {right ascension, declination} in degrees
     */
    public static double[] eclipticToEquatorial(double lambdaDeg, double obliquityDeg) {
        double x = cosd(lambdaDeg);
        double y = sind(lambdaDeg) * cosd(obliquityDeg);
        double z = sind(lambdaDeg) * sind(obliquityDeg);
        return new double[]{rev(atan2d(y, x)), asind(z)};
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
