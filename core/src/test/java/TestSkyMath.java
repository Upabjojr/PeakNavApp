import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.sky.SkyBody;
import com.peaknav.sky.SkyMath;
import com.peaknav.sky.SolarSystem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The astronomy behind the sky overlays.
 *
 * <p>These are checked against facts rather than against the code's own output: the equinoxes
 * sit where the ecliptic crosses the equator, the solstices at the obliquity, and a star due
 * north of an observer stays north. An error here is not subtle on screen - the ecliptic misses
 * the Moon, or the grid converges somewhere that is not the pole - but it is easy to write and
 * hard to spot by reading.
 */
class TestSkyMath {

    /** Obliquity at J2000, near enough for these checks. */
    private static final double OBLIQUITY = 23.4393;

    @Test
    @DisplayName("the equinoxes lie on the equator, the solstices at the obliquity")
    void eclipticCrossesTheEquatorAtTheEquinoxes() {
        // Ecliptic longitude 0 is the March equinox: by definition the origin of both systems.
        double[] march = SkyMath.eclipticToEquatorial(0, OBLIQUITY);
        assertEquals(0, march[0], 1e-6, "right ascension");
        assertEquals(0, march[1], 1e-6, "declination");

        // 90 degrees along is the June solstice: due east on the equator, as far north as the
        // ecliptic reaches - which is exactly the obliquity.
        double[] june = SkyMath.eclipticToEquatorial(90, OBLIQUITY);
        assertEquals(90, june[0], 1e-6, "right ascension");
        assertEquals(OBLIQUITY, june[1], 1e-6, "the Sun's greatest northern declination");

        double[] september = SkyMath.eclipticToEquatorial(180, OBLIQUITY);
        assertEquals(180, september[0], 1e-6);
        assertEquals(0, september[1], 1e-6, "back on the equator");

        double[] december = SkyMath.eclipticToEquatorial(270, OBLIQUITY);
        assertEquals(270, december[0], 1e-6);
        assertEquals(-OBLIQUITY, december[1], 1e-6, "as far south as it reaches");
    }

    @Test
    @DisplayName("the ecliptic never strays further from the equator than the obliquity")
    void eclipticStaysWithinTheObliquity() {
        double furthestNorth = -90, furthestSouth = 90;
        for (int lambda = 0; lambda < 360; lambda++) {
            double dec = SkyMath.eclipticToEquatorial(lambda, OBLIQUITY)[1];
            furthestNorth = Math.max(furthestNorth, dec);
            furthestSouth = Math.min(furthestSouth, dec);
            assertTrue(Math.abs(dec) <= OBLIQUITY + 1e-6,
                    "declination " + dec + " at ecliptic longitude " + lambda);
        }
        assertEquals(OBLIQUITY, furthestNorth, 1e-3);
        assertEquals(-OBLIQUITY, furthestSouth, 1e-3);
    }

    @Test
    @DisplayName("right ascension comes back in 0..360, never negative")
    void rightAscensionIsNormalised() {
        for (int lambda = 0; lambda < 360; lambda += 7) {
            double ra = SkyMath.eclipticToEquatorial(lambda, OBLIQUITY)[0];
            assertTrue(ra >= 0 && ra < 360, "right ascension " + ra + " at longitude " + lambda);
        }
    }

    @Test
    @DisplayName("the celestial pole sits at the observer's latitude, due north")
    void poleIsAtTheObserversLatitude() {
        // The north celestial pole is declination +90; from 46 degrees north it stands 46
        // degrees above the northern horizon. This is what makes the grid's meridians
        // converge where they do.
        double lat = 46.0207;
        SkyMath.AzAlt pole = SkyMath.equatorialToHorizontal(0, 90, 123.4, lat);
        assertEquals(lat, pole.altitude, 1e-6, "altitude of the pole equals the latitude");
        assertEquals(0, ((pole.azimuth % 360) + 360) % 360, 1e-6, "and it is due north");

        // From the southern hemisphere the north pole is below the horizon by as much.
        SkyMath.AzAlt fromSouth = SkyMath.equatorialToHorizontal(0, 90, 55.0, -43.595);
        assertEquals(-43.595, fromSouth.altitude, 1e-6);
    }

    @Test
    @DisplayName("a point on the meridian is due south from the northern hemisphere")
    void meridianPointsSouth() {
        // An object whose right ascension equals the local sidereal time is on the meridian.
        // Seen from a latitude north of it, it is due south.
        double lst = 100.0;
        SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(lst, 10.0, lst, 46.0);
        assertEquals(180, ((aa.azimuth % 360) + 360) % 360, 1e-6, "due south");
        assertEquals(90 - 46.0 + 10.0, aa.altitude, 1e-6, "altitude on the meridian");
    }

    @Test
    @DisplayName("the total eclipse of 12 August 2026 is a total eclipse from Palma")
    void eclipseLinesUpFromTheObserversPlace() {
        // Palma de Mallorca lies in the path of totality; greatest eclipse there is around
        // 18:35 UT. This is the sharpest test of the Moon's position there is - the Sun and
        // the Moon are each about half a degree wide, so being right to a tenth of a degree
        // is the difference between an eclipse and a near miss.
        double lat = 39.5696, lon = 2.6502;
        SolarSystem solarSystem = new SolarSystem();
        double ut = 18.0 + 35.0 / 60.0;
        solarSystem.compute(SkyMath.dayNumber(2026, 8, 12, ut));

        SkyBody sun = null, moon = null;
        for (SkyBody b : solarSystem.bodies) {
            if (b.kind == SkyBody.Kind.SUN) sun = b;
            if (b.kind == SkyBody.Kind.MOON) moon = b;
        }
        assertTrue(sun != null && moon != null);
        double lst = SkyMath.localSiderealTimeDeg(solarSystem.getSunMeanLongitude(), ut, lon);

        // As seen from the centre of the Earth they are nowhere near each other: the Moon is
        // about a degree off, roughly two solar diameters. Drawing this is what made the app
        // show an eclipse that did not overlap.
        double geocentric = separation(sun.raDeg, sun.decDeg, moon.raDeg, moon.decDeg);
        assertTrue(geocentric > 0.9,
                "geocentrically the Moon should be about a degree from the Sun, was " + geocentric);

        // From where the observer actually stands, they coincide - which is the eclipse.
        double[] topo = SkyMath.topocentric(
                moon.raDeg, moon.decDeg, moon.distanceEarthRadii, lst, lat);
        double topocentric = separation(sun.raDeg, sun.decDeg, topo[0], topo[1]);
        assertTrue(topocentric < 0.15,
                "the discs should overlap; centres were " + topocentric + " degrees apart");
    }

    @Test
    @DisplayName("parallax moves the Moon by nearly a degree, and nothing else appreciably")
    void parallaxIsAMoonSizedProblem() {
        double lst = 120.0, lat = 46.0;
        // A body straight overhead is unmoved by parallax: the observer is directly beneath it.
        double[] zenith = SkyMath.topocentric(lst, lat, 60.3, lst, lat);
        assertEquals(lst, zenith[0], 0.02, "right ascension at the zenith");
        assertEquals(lat, zenith[1], 0.02, "declination at the zenith");

        // Near the horizon it is displaced by close to the full horizontal parallax, which for
        // the Moon is about 0.95 degrees.
        double horizonRa = lst - 90;
        double[] horizon = SkyMath.topocentric(horizonRa, 0, 60.3, lst, 0);
        double shift = separation(horizonRa, 0, horizon[0], horizon[1]);
        assertTrue(shift > 0.85 && shift < 1.0,
                "the Moon should shift by about a degree near the horizon, was " + shift);

        // The Sun, 23500 Earth radii away, does not care where you stand: under 10 arcseconds.
        double[] sun = SkyMath.topocentric(horizonRa, 0, 23481, lst, 0);
        assertTrue(separation(horizonRa, 0, sun[0], sun[1]) < 0.005,
                "the Sun's parallax should be negligible");
    }

    /** Angle between two directions on the sky, in degrees. */
    private static double separation(double ra1, double dec1, double ra2, double dec2) {
        double r1 = Math.toRadians(ra1), d1 = Math.toRadians(dec1);
        double r2 = Math.toRadians(ra2), d2 = Math.toRadians(dec2);
        double cos = Math.sin(d1) * Math.sin(d2) + Math.cos(d1) * Math.cos(d2) * Math.cos(r1 - r2);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    @Test
    @DisplayName("the horizon drops away as you climb, by a known amount")
    void horizonDipMatchesTheGeometry() {
        assertEquals(0, SkyMath.horizonDipDegrees(0), 1e-9, "level at sea level");
        assertEquals(0, SkyMath.horizonDipDegrees(-100), 1e-9, "and below it");

        // Against the surveyor's rule of thumb, 1.93 arcminutes times the square root of the
        // height in metres - an independent check, not a restatement of the same formula.
        for (double h : new double[]{100, 1000, 3000, 10000, 25000}) {
            double ruleOfThumb = 1.93 * Math.sqrt(h) / 60.0;
            assertEquals(ruleOfThumb, SkyMath.horizonDipDegrees(h), ruleOfThumb * 0.02,
                    "dip at " + h + " m");
        }

        // The values the sky renderer depends on: from 10 km there is over 3 degrees of sky
        // below level, which a fixed 1.15 degree limit used to cut off.
        assertEquals(1.02, SkyMath.horizonDipDegrees(1000), 0.02);
        assertEquals(3.21, SkyMath.horizonDipDegrees(10000), 0.02);
        assertTrue(SkyMath.horizonDipDegrees(10000) > 1.15 * 2,
                "the old fixed limit hid more than half the sky below level at 10 km");

        // Monotonic: higher always sees further round.
        double previous = -1;
        for (int h = 0; h <= 25000; h += 500) {
            double dip = SkyMath.horizonDipDegrees(h);
            assertTrue(dip >= previous, "dip must not decrease with height, at " + h + " m");
            previous = dip;
        }
    }
}
