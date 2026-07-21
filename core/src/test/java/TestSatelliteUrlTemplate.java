import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.viewer.imgmapprovider.SatelliteUrlTemplate;

import org.junit.jupiter.api.Test;

import java.util.Locale;

public class TestSatelliteUrlTemplate {

    private static String expand(String template, int z, int x, int y) {
        return new SatelliteUrlTemplate(template).expand(z, x, y);
    }

    // ---- the placeholders the code already supported, so behaviour cannot regress ----

    @Test
    public void expandsXYZ() {
        assertEquals("https://example.com/7/12/34.png",
                expand("https://example.com/{z}/{x}/{y}.png", 7, 12, 34));
    }

    @Test
    public void expandsInArcGisRowColumnOrder() {
        // USGS built-in: ArcGIS REST serves /tile/{level}/{row}/{col}
        assertEquals("https://basemap.nationalmap.gov/tile/8/100/200",
                expand("https://basemap.nationalmap.gov/tile/{z}/{y}/{x}", 8, 200, 100));
    }

    /** Reference implementation of the quadkey the provider used before this change. */
    private static String legacyQuadKey(int z, int x, int y) {
        char[] num2char = {'0', '1', '2', '3'};
        char[] quadkey = new char[z];
        int ix = x, iy = y;
        for (int i = z - 1; i >= 0; i--) {
            int n = ((iy % 2) << 1) | (ix % 2);
            quadkey[i] = num2char[n];
            ix >>= 1;
            iy >>= 1;
        }
        return new String(quadkey);
    }

    @Test
    public void quadKeyMatchesThePreviousImplementation() {
        for (int z = 1; z <= 18; z++) {
            int max = 1 << z;
            for (int i = 0; i < 40; i++) {
                int x = (i * 2654435761L % max) < 0 ? 0 : (int) ((i * 2654435761L) % max);
                int y = (int) ((i * 40503L) % max);
                assertEquals("https://t/" + legacyQuadKey(z, x, y) + ".jpg",
                        expand("https://t/{u}.jpg", z, x, y),
                        "quadkey differs at z=" + z + " x=" + x + " y=" + y);
            }
        }
    }

    @Test
    public void knownBingQuadKeyValue() {
        // Documented example: zoom 3, x=3, y=5 -> "213"
        assertEquals("https://t/213.jpg", expand("https://t/{u}.jpg", 3, 3, 5));
    }

    // ---- placeholders added so URLs copied from the OSM editor work ----

    @Test
    public void supportsZoomSpelledOut() {
        assertEquals("https://example.com/9/1/2.png",
                expand("https://example.com/{zoom}/{x}/{y}.png", 9, 1, 2));
    }

    @Test
    public void supportsTmsFlippedY() {
        // At zoom 4 there are 16 rows, so row 3 from the top is row 12 from the bottom.
        assertEquals("https://example.com/4/1/12.png",
                expand("https://example.com/{z}/{x}/{-y}.png", 4, 1, 3));
    }

    @Test
    public void supportsSwitchAndSpreadsAcrossAlternatives() {
        String template = "https://{switch:a,b,c}.tile.example.com/{z}/{x}/{y}.png";
        boolean seenA = false, seenB = false, seenC = false;
        for (int x = 0; x < 6; x++) {
            String url = expand(template, 5, x, 0);
            assertTrue(url.endsWith("/5/" + x + "/0.png"));
            if (url.startsWith("https://a.")) seenA = true;
            if (url.startsWith("https://b.")) seenB = true;
            if (url.startsWith("https://c.")) seenC = true;
        }
        assertTrue(seenA && seenB && seenC, "switch should spread requests over all alternatives");
    }

    @Test
    public void switchIsStableForTheSameTile() {
        String template = "https://{switch:a,b}.example.com/{z}/{x}/{y}.png";
        assertEquals(expand(template, 5, 7, 9), expand(template, 5, 7, 9));
    }

    // ---- Bing / Virtual Earth style templates ----

    /** The exact URL form these services are usually published as. */
    private static final String VIRTUAL_EARTH =
            "http://ecn.t{subdomain}.tiles.virtualearth.net/tiles/r{quadkey}.jpeg?g=129";

    @Test
    public void acceptsVirtualEarthTemplate() {
        assertNull(SatelliteUrlTemplate.validate(VIRTUAL_EARTH));
    }

    @Test
    public void quadkeyIsASynonymForU() {
        assertEquals(expand("https://t/{u}.jpg", 5, 9, 13),
                expand("https://t/{quadkey}.jpg", 5, 9, 13));
    }

    @Test
    public void expandsVirtualEarthTemplateToARealTileUrl() {
        String url = expand(VIRTUAL_EARTH, 3, 3, 5);
        assertTrue(url.startsWith("http://ecn.t"), url);
        assertTrue(url.contains("/tiles/r213.jpeg?g=129"), url);
        // the subdomain must have been substituted, not left as a placeholder
        assertTrue(!url.contains("{"), "no placeholder should survive: " + url);
    }

    @Test
    public void subdomainSpreadsOverTheNumberedHosts() {
        boolean[] seen = new boolean[4];
        for (int x = 0; x < 8; x++) {
            String url = expand(VIRTUAL_EARTH, 4, x, 0);
            for (int i = 0; i < 4; i++) {
                if (url.startsWith("http://ecn.t" + i + ".")) seen[i] = true;
            }
        }
        for (int i = 0; i < 4; i++) {
            assertTrue(seen[i], "subdomain " + i + " should be used for some tiles");
        }
    }

    // ---- the rest of the iD token set ----

    @Test
    public void supportsTyAsFlippedY() {
        assertEquals(expand("https://e.com/{z}/{x}/{-y}.png", 4, 1, 3),
                expand("https://e.com/{z}/{x}/{ty}.png", 4, 1, 3));
    }

    @Test
    public void supportsShortSwitch() {
        String url = expand("https://{sw:a,b}.e.com/{z}/{x}/{y}.png", 5, 1, 1);
        assertTrue(url.startsWith("https://a.") || url.startsWith("https://b."), url);
    }

    @Test
    public void resolutionTokensDropOutAtStandardResolution() {
        assertEquals("https://e.com/5/1/2.png",
                expand("https://e.com/{z}/{x}/{y}{@2x}.png", 5, 1, 2));
        assertEquals("https://e.com/5/1/2.png",
                expand("https://e.com/{z}/{x}/{y}{r}.png", 5, 1, 2));
    }

    @Test
    public void supportsWmsProjectionAndSizeTokens() {
        // A real WMS request always carries the bbox as well; it is what identifies the tile.
        String url = expand("https://e.com/wms?SRS={proj}&W={width}&H={height}&ID={wkid}&B={bbox}",
                0, 0, 0);
        assertTrue(url.startsWith("https://e.com/wms?SRS=EPSG:3857&W=256&H=256&ID=3857&B="), url);
    }

    @Test
    public void wmsBboxCoversTheWholeWorldAtZoomZero() {
        String url = expand("https://e.com/wms?BBOX={bbox}", 0, 0, 0);
        String bbox = url.substring(url.indexOf("BBOX=") + 5);
        String[] parts = bbox.split(",");
        assertEquals(4, parts.length, bbox);
        assertEquals(-20037508.342789, Double.parseDouble(parts[0]), 1e-3);
        assertEquals(-20037508.342789, Double.parseDouble(parts[1]), 1e-3);
        assertEquals(20037508.342789, Double.parseDouble(parts[2]), 1e-3);
        assertEquals(20037508.342789, Double.parseDouble(parts[3]), 1e-3);
    }

    @Test
    public void wmsBboxIsTheCorrectQuadrantAtZoomOne() {
        // Tile (0,0) at zoom 1 is the north west quadrant: x negative, y positive.
        String[] nw = bboxOf(1, 0, 0);
        assertEquals(-20037508.342789, Double.parseDouble(nw[0]), 1e-3);
        assertEquals(0.0, Double.parseDouble(nw[1]), 1e-3);
        assertEquals(0.0, Double.parseDouble(nw[2]), 1e-3);
        assertEquals(20037508.342789, Double.parseDouble(nw[3]), 1e-3);

        // Tile (1,1) is the south east quadrant.
        String[] se = bboxOf(1, 1, 1);
        assertEquals(0.0, Double.parseDouble(se[0]), 1e-3);
        assertEquals(-20037508.342789, Double.parseDouble(se[1]), 1e-3);
        assertEquals(20037508.342789, Double.parseDouble(se[2]), 1e-3);
        assertEquals(0.0, Double.parseDouble(se[3]), 1e-3);
    }

    @Test
    public void wmsBboxIsAlwaysMinThenMax() {
        for (int z = 0; z <= 10; z += 2) {
            int n = 1 << z;
            for (int i = 0; i < 5; i++) {
                int x = (i * 7) % n, y = (i * 3) % n;
                String[] b = bboxOf(z, x, y);
                assertTrue(Double.parseDouble(b[0]) < Double.parseDouble(b[2]), "minX < maxX");
                assertTrue(Double.parseDouble(b[1]) < Double.parseDouble(b[3]), "minY < maxY");
            }
        }
    }

    @Test
    public void wmsBboxUsesDotsNotCommasAsDecimalSeparator() {
        // Under an Italian locale a careless format() would emit "11,25" and split the bbox into
        // the wrong number of fields.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ITALY);
            assertEquals(4, bboxOf(6, 34, 22).length,
                    "the bbox must not gain extra fields under a comma-decimal locale");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static String[] bboxOf(int z, int x, int y) {
        String url = expand("https://e.com/wms?BBOX={bbox}", z, x, y);
        return url.substring(url.indexOf("BBOX=") + 5).split(",");
    }

    @Test
    public void acceptsAWmsTemplateWithoutTileCoordinates() {
        assertNull(SatelliteUrlTemplate.validate(
                "https://e.com/wms?SERVICE=WMS&BBOX={bbox}&WIDTH={width}&HEIGHT={height}"
                        + "&SRS={proj}&FORMAT=image/png"));
    }

    @Test
    public void wmsFormatDrivesTheCacheExtension() {
        assertEquals("png", SatelliteUrlTemplate.guessImageExtension(
                "https://e.com/wms?BBOX={bbox}&FORMAT=image/png"));
        assertEquals("jpg", SatelliteUrlTemplate.guessImageExtension(
                "https://e.com/wms?BBOX={bbox}&FORMAT=image/jpeg"));
    }

    // ---- validation ----

    @Test
    public void acceptsValidTemplates() {
        assertNull(SatelliteUrlTemplate.validate("https://example.com/{z}/{x}/{y}.png"));
        assertNull(SatelliteUrlTemplate.validate("https://example.com/{zoom}/{x}/{-y}.jpg"));
        assertNull(SatelliteUrlTemplate.validate("http://example.com/tiles/{u}.jpeg"));
        assertNull(SatelliteUrlTemplate.validate(
                "https://{switch:a,b}.example.com/{z}/{x}/{y}.png?key=abc"));
    }

    @Test
    public void rejectsUnknownPlaceholder() {
        // The old code left unknown placeholders in the URL, silently producing a broken request.
        String error = SatelliteUrlTemplate.validate("https://example.com/{z}/{x}/{y}/{apikey}.png");
        assertNotNull(error);
        assertTrue(error.contains("apikey"), "the error should name the offending placeholder");
    }

    @Test
    public void rejectsMissingCoordinates() {
        assertNotNull(SatelliteUrlTemplate.validate("https://example.com/tiles.png"));
        assertNotNull(SatelliteUrlTemplate.validate("https://example.com/{z}/{x}.png"));
    }

    @Test
    public void rejectsNonHttpAndEmpty() {
        assertNotNull(SatelliteUrlTemplate.validate(""));
        assertNotNull(SatelliteUrlTemplate.validate(null));
        assertNotNull(SatelliteUrlTemplate.validate("ftp://example.com/{z}/{x}/{y}.png"));
    }

    @Test
    public void rejectsMalformedSwitch() {
        assertNotNull(SatelliteUrlTemplate.validate("https://{switch:}.example.com/{z}/{x}/{y}.png"));
        assertNotNull(SatelliteUrlTemplate.validate("https://{switch:a,}.example.com/{z}/{x}/{y}.png"));
    }

    @Test
    public void constructorRejectsInvalidTemplate() {
        assertThrows(IllegalArgumentException.class,
                () -> new SatelliteUrlTemplate("https://example.com/nothing.png"));
    }

    // ---- cache file extension ----

    @Test
    public void guessesImageExtension() {
        assertEquals("png", SatelliteUrlTemplate.guessImageExtension("https://e.com/{z}/{x}/{y}.png"));
        assertEquals("jpg", SatelliteUrlTemplate.guessImageExtension("https://e.com/{z}/{x}/{y}.jpg"));
        assertEquals("jpg", SatelliteUrlTemplate.guessImageExtension("https://e.com/{z}/{x}/{y}.jpeg"));
    }

    @Test
    public void extensionlessUrlFallsBackInsteadOfThrowing() {
        // The previous code threw "could not detect image format" here, which for a custom
        // provider would have meant a crash rather than a usable layer.
        assertEquals("jpg", SatelliteUrlTemplate.guessImageExtension(
                "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"));
    }
}
