import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.viewer.imgmapprovider.SatelliteUrlTemplate;

import org.junit.jupiter.api.Test;

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
