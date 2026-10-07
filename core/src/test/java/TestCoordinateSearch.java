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

    /** Point Reyes offshore, as written in the ways below: 32.30642, -122.61458. */
    private static final double P_LAT = 32.30642;
    private static final double P_LON = -122.61458;

    @Test
    void signs() {
        assertAt(P_LAT, P_LON, "+32.30642, -122.61458");
        assertAt(P_LAT, P_LON, "+32.30642 -122.61458");
        assertAt(P_LAT, P_LON, "32.30642,-122.61458");
        assertAt(P_LAT, P_LON, "32.30642, \u2212122.61458");
        assertAt(P_LAT, P_LON, "32.30642 / -122.61458");
        assertAt(P_LAT, P_LON, "32.30642, -122.61458.");
    }

    @Test
    void longitudeFirstWhenTheFirstCannotBeALatitude() {
        assertAt(P_LAT, P_LON, "-122.61458, 32.30642");
        assertAt(P_LAT, P_LON, "[-122.61458, 32.30642]");
        assertAt(P_LAT, P_LON, "POINT(-122.61458 32.30642)");
        assertAt(P_LAT, P_LON, "point (-122.61458 32.30642)");
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
        assertAt(45 + 46 / 60.0 + 52 / 3600.0, -(108 + 30 / 60.0 + 14 / 3600.0),
                "45° 46' 52\" N 108° 30' 14\" W");
    }

    @Test
    void summitsInEveryHemisphere() {
        assertAt(27 + 59 / 60.0 + 17 / 3600.0, 86 + 55 / 60.0 + 31 / 3600.0, "27°59′17″N 86°55′31″E");
        assertAt(-(32 + 39 / 60.0 + 12 / 3600.0), -(70 + 39 / 3600.0), "32°39′12″S 70°00′39″W");
        assertAt(-(3 + 4 / 60.0 + 33 / 3600.0), 37 + 21 / 60.0 + 12 / 3600.0, "3°04′33″S 37°21′12″E");
        assertAt(63 + 4 / 60.0 + 10 / 3600.0, -(151 + 0 / 60.0 + 27 / 3600.0), "63°04′10″N 151°00′27″W");
        assertAt(-(43 + 35 / 60.0 + 42 / 3600.0), 170 + 8 / 60.0 + 30 / 3600.0, "43°35'42\"S 170°08'30\"E");
    }

    @Test
    void googleMapsDecimalSeconds() {
        assertAt(32 + 18 / 60.0 + 23.1 / 3600.0, -(122 + 36 / 60.0 + 52.5 / 3600.0), "32°18'23.1\"N 122°36'52.5\"W");
        assertAt(32 + 18 / 60.0 + 23.1 / 3600.0, -(122 + 36 / 60.0 + 52.5 / 3600.0), "32°18'23,1\"N 122°36'52,5\"W");
    }

    @Test
    void colons() {
        assertAt(32 + 18 / 60.0 + 23 / 3600.0, -(122 + 36 / 60.0 + 52 / 3600.0), "32:18:23N 122:36:52W");
        assertAt(32 + 18 / 60.0 + 23.1 / 3600.0, -(122 + 36 / 60.0 + 52.5 / 3600.0), "32:18:23.1 N, 122:36:52.5 W");
        assertAt(32 + 18.385 / 60, -(122 + 36.875 / 60), "32:18.385N 122:36.875W");
    }

    @Test
    void compactAviationAndNmea() {
        assertAt(32 + 18 / 60.0 + 23 / 3600.0, -(122 + 36 / 60.0 + 52 / 3600.0), "321823N 1223652W");
        assertAt(32 + 18 / 60.0 + 23 / 3600.0, -(122 + 36 / 60.0 + 52 / 3600.0), "321823N1223652W");
        assertAt(32 + 18 / 60.0 + 23.1 / 3600.0, -(122 + 36 / 60.0 + 52.5 / 3600.0), "321823.1N 1223652.5W");
        assertAt(32 + 18.385 / 60, -(122 + 36.875 / 60), "3218.385,N,12236.875,W");
        assertAt(32 + 18.385 / 60, -(122 + 36.875 / 60), "3218.385 N 12236.875 W");
    }

    @Test
    void degreesAndDecimalMinutes() {
        assertAt(32 + 18.385 / 60, -(122 + 36.875 / 60), "N32 18.385 W122 36.875");
        assertAt(32 + 18.385 / 60, -(122 + 36.875 / 60), "N 32° 18.385', W 122° 36.875'");
        assertAt(32 + 18.385 / 60, -(122 + 36.875 / 60), "N32°18.385' W122°36.875'");
        assertAt(-(32 + 18.385 / 60), 122 + 36.875 / 60, "S 32° 18.385 E 122° 36.875");
        assertAt(45 + 58.583 / 60, 7 + 39.517 / 60, "N 45° 58.583' E 007° 39.517'");
        assertAt(45 + 58.583 / 60, 7 + 39.517 / 60, "N 45 58.583 E 007 39.517");
        assertAt(45 + 58.583 / 60, 7 + 39.517 / 60, "45°58.583'N, 7°39.517'E");
        assertAt(45 + 46.8666 / 60, -(108 + 30.2333 / 60), "45° 46.8666' N 108° 30.2333' W");
    }

    @Test
    void wikipediaLine() {
        assertAt(LAT, LON, "45°58′35″N 7°39′31″E / 45.97639°N 7.65861°E / 45.97639; 7.65861");
        assertAt(LAT, LON, "45°58′35″N 7°39′31″E\uFEFF / \uFEFF45.97639°N 7.65861°E");
        assertAt(45.97639, 7.65861, "Coordinates: garbage / 45.97639°N 7.65861°E");
    }

    @Test
    void labelled() {
        assertAt(P_LAT, P_LON, "{\"lat\": 32.30642, \"lng\": -122.61458}");
        assertAt(P_LAT, P_LON, "{\"longitude\": -122.61458, \"latitude\": 32.30642}");
        assertAt(P_LAT, P_LON, "lng=-122.61458&lat=32.30642");
        assertAt(P_LAT, P_LON, "Lat: 32,30642 Lon: -122,61458");
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
        assertAt(P_LAT, P_LON, "https://www.bing.com/maps?cp=32.30642~-122.61458&lvl=11");
        assertAt(P_LAT, P_LON, "https://maps.apple.com/?ll=32.30642,-122.61458&q=Pin");
        assertAt(P_LAT, P_LON, "https://www.waze.com/ul?ll=32.30642%2C-122.61458&navigate=yes");
        assertAt(P_LAT, P_LON, "https://maps.google.com/maps?q=loc:32.30642,-122.61458");
        assertAt(P_LAT, P_LON, "geo:32.30642,-122.61458;u=35");
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
        assertAt(46.0207, 7.7491, "`46.0207, 7.7491`");
        assertAt(46.0207, 7.7491, "__46.0207__, __7.7491__");
        assertAt(46.0207, 7.7491, "\t46.0207,\r\n7.7491\t");
    }

    @Test
    void notCoordinates() {
        assertNull(CoordinateSearch.parseCoordinates("Matterhorn"));
        assertNull(CoordinateSearch.parseCoordinates("Cima 12"));
        assertNull(CoordinateSearch.parseCoordinates("Route 66, 1"));
        assertNull(CoordinateSearch.parseCoordinates("38086 38087"), "two bare whole numbers");
        assertNull(CoordinateSearch.parseCoordinates("46.02"), "one number");
        assertNull(CoordinateSearch.parseCoordinates("95.0, 97.0"), "no latitude either way round");
        assertNull(CoordinateSearch.parseCoordinates("200.0, 7.0"), "not a longitude either");
        assertNull(CoordinateSearch.parseCoordinates("46.0, 190.0"), "longitude out of range");
        assertNull(CoordinateSearch.parseCoordinates("46.0 N, 7.0 S"), "two latitudes");
        assertNull(CoordinateSearch.parseCoordinates("46.0 N, 7.0"), "a letter on only one");
        assertNull(CoordinateSearch.parseCoordinates("45°70'00\"N 7°39'31\"E"), "70 minutes");
        assertNull(CoordinateSearch.parseCoordinates("45.5° 30' N 7° E"), "decimal degrees with minutes");
        assertNull(CoordinateSearch.parseCoordinates("Everest 8848"));
        assertNull(CoordinateSearch.parseCoordinates("Mont Blanc 4808 m"));
        assertNull(CoordinateSearch.parseCoordinates("3 Cime di Lavaredo"));
        assertNull(CoordinateSearch.parseCoordinates("K2"));
        assertNull(CoordinateSearch.parseCoordinates("12:30"), "a time");
        assertNull(CoordinateSearch.parseCoordinates("321875N 1223652W"), "75 seconds");
        assertNull(CoordinateSearch.parseCoordinates("3275.385,N,12236.875,W"), "75 minutes");
        assertNull(CoordinateSearch.parseCoordinates("POINT(200 32)"), "WKT out of range");
        assertNull(CoordinateSearch.parseCoordinates("lat: 95, lon: 7"), "labelled out of range");
        assertNull(CoordinateSearch.parseCoordinates("46.0, 7.0, 8.0"), "three numbers");
        assertNull(CoordinateSearch.parseCoordinates("  **  **  "));
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
