import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.peaknav.utils.CoordinateSearch;

import org.junit.jupiter.api.Test;

/** Coordinates typed or pasted into the search box, in the ways they are commonly printed. */
class TestCoordinateSearch {

    /** The Matterhorn: 45°58'35"N 7°39'31"E. */
    private static final double LAT = 45 + 58 / 60.0 + 35 / 3600.0;
    private static final double LON = 7 + 39 / 60.0 + 31 / 3600.0;

    private static void assertAt(double lat, double lon, String text) {
        assertArrayEquals(new double[] {lat, lon}, CoordinateSearch.parseCoordinates(text), 1e-4, text);
    }

    @Test
    void decimalDegrees() {
        assertAt(46.0207, 7.7491, "46.0207, 7.7491");
        assertAt(46.0207, 7.7491, "46.0207,7.7491");
        assertAt(46.0207, 7.7491, "46.0207 7.7491");
        assertAt(46.0207, 7.7491, "46.0207; 7.7491");
        assertAt(46.0207, 7.7491, "46.0207/7.7491");
        assertAt(46.0207, 7.7491, "(46.0207, 7.7491)");
        assertAt(46.0207, 7.7491, "46.0207°, 7.7491°");
        assertAt(-43.595, 170.1418, "-43.595, 170.1418");
        assertAt(36.0544, -112.1401, "36.0544, -112.1401");
        assertAt(46, 7, "46, 7");
    }

    @Test
    void decimalCommas() {
        assertAt(46.0207, 7.7491, "46,0207 7,7491");
        assertAt(46.0207, 7.7491, "46,0207; 7,7491");
        assertAt(46.0207, 7.7491, "46,0207, 7,7491");
        assertAt(46.02, 7.74, "46,02 7,74");
    }

    @Test
    void hemispheres() {
        assertAt(46.0207, 7.7491, "46.0207° N, 7.7491° E");
        assertAt(46.0207, 7.7491, "46.0207N 7.7491E");
        assertAt(46.0207, 7.7491, "N 46.0207 E 7.7491");
        assertAt(46.0207, 7.7491, "N46.0207, E7.7491");
        assertAt(46.0207, 7.7491, "7.7491 E, 46.0207 N");
        assertAt(46.0207, 7.7491, "e 7.7491 n 46.0207");
        assertAt(-43.595, -70.1418, "43.595 S 70.1418 W");
        assertAt(-43.595, -70.1418, "S 43.595, W 70.1418");
    }

    @Test
    void degreesMinutesSeconds() {
        assertAt(LAT, LON, "45°58'35\"N 7°39'31\"E");
        assertAt(LAT, LON, "45°58′35″N 7°39′31″E");
        assertAt(LAT, LON, "45° 58′ 35″ N, 7° 39′ 31″ E");
        assertAt(LAT, LON, "45º58'35''N 7º39'31''E");
        assertAt(LAT, LON, "N45°58'35\" E7°39'31\"");
        assertAt(LAT, LON, "45 58 35 N 7 39 31 E");
        assertAt(LAT, LON, "45°58'35\", 7°39'31\"");
        assertAt(-LAT, -LON, "45°58'35\"S 7°39'31\"W");
        assertAt(-LAT, LON, "-45°58'35\", 7°39'31\"");
        assertAt(45.97639, 7.65861, "45°58'35.0\"N 7°39'31.0\"E");
    }

    @Test
    void degreesAndDecimalMinutes() {
        assertAt(45 + 58.583 / 60, 7 + 39.517 / 60, "N 45° 58.583' E 007° 39.517'");
        assertAt(45 + 58.583 / 60, 7 + 39.517 / 60, "N 45 58.583 E 007 39.517");
        assertAt(45 + 58.583 / 60, 7 + 39.517 / 60, "45°58.583'N, 7°39.517'E");
    }

    @Test
    void labelled() {
        assertAt(46.0207, 7.7491, "lat: 46.0207, lon: 7.7491");
        assertAt(46.0207, 7.7491, "Latitude 46.0207 Longitude 7.7491");
        assertAt(46.0207, 7.7491, "lat=46.0207 lng=7.7491");
    }

    @Test
    void links() {
        assertAt(46.0207, 7.7491, "geo:46.0207,7.7491");
        assertAt(45.9763, 7.6586,
                "https://www.google.com/maps/place/Matterhorn/@45.97,7.65,14z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d45.9763!4d7.6586");
        assertAt(45.97, 7.65, "https://www.google.com/maps/@45.97,7.65,14z");
        assertAt(45.97, 7.65, "https://maps.google.com/?q=45.97,7.65");
        assertAt(45.97, 7.65, "https://www.google.com/maps/search/?api=1&query=45.97%2C7.65");
        assertAt(46.0207, 7.7491, "https://www.openstreetmap.org/#map=15/46.0207/7.7491");
        assertAt(46.0207, 7.7491, "https://www.openstreetmap.org/?mlat=46.0207&mlon=7.7491#map=12/46/7");
        assertNull(CoordinateSearch.parseCoordinates("https://peaknav.com/"));
    }

    @Test
    void formattingIsStripped() {
        assertAt(46.0207, 7.7491, "   **46.0207,   7.7491**  ");
        assertAt(46.0207, 7.7491, "<b>46.0207</b>, <b>7.7491</b>");
        assertAt(46.0207, 7.7491, "​46.0207, 7.7491\n");
        // Mathematical bold digits and letters, as pasted from styled text.
        assertAt(46.0207, 7.7491, "𝟒𝟔.𝟎𝟐𝟎𝟕° 𝐍, 𝟕.𝟕𝟒𝟗𝟏° 𝐄");
        assertAt(46.0207, 7.7491, "４６.０２０７, ７.７４９１");
        assertAt(46.0207, 7.7491, "46.0207, 7.7491");
    }

    @Test
    void notCoordinates() {
        assertNull(CoordinateSearch.parseCoordinates("Matterhorn"));
        assertNull(CoordinateSearch.parseCoordinates("Cima 12"));
        assertNull(CoordinateSearch.parseCoordinates("Route 66, 1"));
        assertNull(CoordinateSearch.parseCoordinates("38086 38087"), "two bare whole numbers");
        assertNull(CoordinateSearch.parseCoordinates("46.02"), "one number");
        assertNull(CoordinateSearch.parseCoordinates("95.0, 7.0"), "latitude out of range");
        assertNull(CoordinateSearch.parseCoordinates("46.0, 190.0"), "longitude out of range");
        assertNull(CoordinateSearch.parseCoordinates("46.0 N, 7.0 S"), "two latitudes");
        assertNull(CoordinateSearch.parseCoordinates("46.0 N, 7.0"), "a letter on only one");
        assertNull(CoordinateSearch.parseCoordinates("45°70'00\"N 7°39'31\"E"), "70 minutes");
        assertNull(CoordinateSearch.parseCoordinates("45.5° 30' N 7° E"), "decimal degrees with minutes");
        assertNull(CoordinateSearch.parseCoordinates(""));
        assertNull(CoordinateSearch.parseCoordinates(null));
    }

    @Test
    void cleanQueryKeepsTheWords() {
        assertEquals("Piz Palü", CoordinateSearch.cleanQuery("  **Piz   Palü**​ "));
        assertEquals("Monte Rosa", CoordinateSearch.cleanQuery("<b>Monte</b> Rosa"));
        assertEquals("Grandes Jorasses", CoordinateSearch.cleanQuery("𝐆𝐫𝐚𝐧𝐝𝐞𝐬 𝐉𝐨𝐫𝐚𝐬𝐬𝐞𝐬"));
        assertEquals("Pic d'Anie", CoordinateSearch.cleanQuery("Pic d’Anie"));
    }
}
