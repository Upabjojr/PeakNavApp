import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.CoordinateLinks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a coordinate out of a {@code geo:} URI, and the web link built from one.
 *
 * <p>The parser takes input from other apps, so it has to cope with every shape they send and
 * refuse the ones that carry no coordinate at all. Getting that wrong is visible: a point in
 * the wrong place, or a jump to the Gulf of Guinea when an app sends {@code geo:0,0}.
 */
class TestCoordinateLinks {

    @Test
    @DisplayName("the plain forms: a point, with or without a zoom")
    void parsesBarePoints() {
        assertArrayEquals(new double[]{46.0207, 7.7491},
                CoordinateLinks.parseGeoUri("geo:46.0207,7.7491"), 1e-9);
        assertArrayEquals(new double[]{46.0207, 7.7491},
                CoordinateLinks.parseGeoUri("geo:46.0207,7.7491?z=15"), 1e-9,
                "a zoom is not ours to use, but must not stop the point being read");
        assertArrayEquals(new double[]{-43.5950, 170.1418},
                CoordinateLinks.parseGeoUri("geo:-43.5950,170.1418"), 1e-9,
                "southern and eastern coordinates read the same way");
        assertArrayEquals(new double[]{46.0207, 7.7491},
                CoordinateLinks.parseGeoUri("46.0207,7.7491"), 1e-9,
                "the scheme is optional: callers may pass just the part after geo:");
    }

    @Test
    @DisplayName("the marker form: q= carries the point, the path is a placeholder")
    void prefersTheQueryPoint() {
        // This is what PeakNav itself sends, and what most map apps send.
        assertArrayEquals(new double[]{46.0207, 7.7491},
                CoordinateLinks.parseGeoUri("geo:0,0?q=46.0207,7.7491(Matterhorn)"), 1e-9,
                "reading the path here would land on 0,0");
        assertArrayEquals(new double[]{45.9763, 7.6586},
                CoordinateLinks.parseGeoUri("geo:0,0?q=45.9763%2C7.6586(Matterhorn)"), 1e-9,
                "the query is percent-encoded when it carries a label");
        assertArrayEquals(new double[]{46.8523, -121.7603},
                CoordinateLinks.parseGeoUri("geo:46.0,7.0?q=46.8523,-121.7603"), 1e-9,
                "q= wins even when the path also holds a usable point");
    }

    @Test
    @DisplayName("what carries no coordinate is refused, rather than guessed at")
    void refusesWhatItCannotRead() {
        assertNull(CoordinateLinks.parseGeoUri("geo:0,0?q=Zermatt"),
                "a place name needs a geocoder; this is not one");
        assertNull(CoordinateLinks.parseGeoUri("geo:0,0"),
                "an app saying 'no location' must not send the map to the Gulf of Guinea");
        assertNull(CoordinateLinks.parseGeoUri("geo:bananas"));
        assertNull(CoordinateLinks.parseGeoUri("geo:200,400"), "out of range");
        assertNull(CoordinateLinks.parseGeoUri("geo:46.0207"), "half a coordinate is none");
        assertNull(CoordinateLinks.parseGeoUri(null));
        assertNull(CoordinateLinks.parseGeoUri(""));
    }

    @Test
    @DisplayName("the extremes of the range are accepted, just past them is not")
    void checksTheRange() {
        assertArrayEquals(new double[]{90, 180}, CoordinateLinks.parseGeoUri("geo:90,180"), 1e-9);
        assertArrayEquals(new double[]{-90, -180}, CoordinateLinks.parseGeoUri("geo:-90,-180"), 1e-9);
        assertNull(CoordinateLinks.parseGeoUri("geo:90.1,0"));
        assertNull(CoordinateLinks.parseGeoUri("geo:0,180.1"));
    }

    @Test
    @DisplayName("the web link carries the point and the language")
    void buildsTheWebLink() {
        String url = CoordinateLinks.geoHackUrl(45.9763, 7.6586, "it");
        assertTrue(url.startsWith("https://geohack.toolforge.org/geohack.php?"), url);
        assertTrue(url.contains("params=45.976300;7.658600"),
                "GeoHack wants decimal degrees separated by a semicolon, was " + url);
        assertTrue(url.contains("language=it"), url);

        // Signs must survive: the southern hemisphere is not the northern one.
        assertTrue(CoordinateLinks.geoHackUrl(-43.5950, 170.1418, "en")
                .contains("params=-43.595000;170.141800"));
        // A missing language must not produce "language=null".
        assertEquals(CoordinateLinks.geoHackUrl(0, 0, "en"), CoordinateLinks.geoHackUrl(0, 0, null));
    }

    @Test
    @DisplayName("a link built from a point parses back to that point")
    void roundTrips() {
        double[][] points = {{46.0207, 7.7491}, {-43.5950, 170.1418}, {28.2724, -16.6425}};
        for (double[] p : points) {
            String geo = String.format(java.util.Locale.ENGLISH, "geo:%f,%f?q=%f,%f(PeakNav)",
                    p[0], p[1], p[0], p[1]);
            assertArrayEquals(p, CoordinateLinks.parseGeoUri(geo), 1e-5,
                    "what PeakNav sends, PeakNav must be able to read back");
        }
    }
}
