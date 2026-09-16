package com.peaknav.utils;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What was typed or pasted into the search box: coordinates to go to, or text to search for.
 *
 * <p>Recognition is by regular expressions only, one per family of the ways coordinates are
 * commonly printed. Whatever does not match is not coordinates, and is searched for as a name.
 *
 * <pre>
 * 46.0207, 7.7491            decimal degrees, the order latitude then longitude
 * 46.0207 7.7491             ... separated by a space, a semicolon or a slash
 * 46,0207; 7,7491            ... with decimal commas
 * -43.595, 170.1418          ... signed for south and west
 * 46.0207° N, 7.7491° E      decimal degrees with hemispheres, before or after the number
 * N 46.0207 E 7.7491
 * 45°58'35"N 7°39'31"E       degrees, minutes, seconds (Wikipedia, Google Maps)
 * 45° 58′ 35″ N, 7° 39′ 31″ E
 * 45 58 35 N 7 39 31 E
 * N 45° 58.583' E 007° 39.517'   degrees and decimal minutes (GPS receivers, geocaching)
 * lat: 46.0207, lon: 7.7491  labelled
 * geo:46.0207,7.7491         a geo: URI
 * https://www.google.com/maps/place/.../@46.02,7.74,15z/data=...!3d46.0207!4d7.7491
 * https://www.openstreetmap.org/#map=15/46.0207/7.7491  (or ?mlat=46.0207&amp;mlon=7.7491)
 * </pre>
 *
 * <p>Pasted text is cleaned first (see {@link #cleanQuery}), for both uses.
 */
public final class CoordinateSearch {

    private CoordinateSearch() {}

    // ------------------------------------------------------------------ cleaning

    /** Zero-width characters and the soft hyphen, which copied web text is full of. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200F\\u2060\\uFEFF]");
    /** HTML tags, and Markdown emphasis and code marks: {@code <b>}, {@code **}, {@code __}, {@code `}, {@code ~~}. */
    private static final Pattern MARKUP = Pattern.compile("<[^>]*>|\\*+|_{2,}|`+|~{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    /**
     * The text without its formatting: HTML tags and Markdown marks removed, "bold" and
     * other styled Unicode letters and digits (𝟒𝟔, 𝐍) turned into plain ones, invisible
     * characters dropped, whitespace collapsed and trimmed. Look-alike degree, minute and
     * second marks become {@code ° ' "}.
     */
    public static String cleanQuery(String text) {
        if (text == null) {
            return "";
        }
        // Before the NFKC step, which would turn º into "o" and ″ into two primes.
        String s = text
                .replaceAll("[º˚]", "°")
                .replaceAll("[′’‘´]", "'")
                .replaceAll("[″“”]", "\"")
                .replace('−', '-');
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = INVISIBLE.matcher(s).replaceAll("");
        s = MARKUP.matcher(s).replaceAll(" ");
        s = s.replace("''", "\"");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    // ------------------------------------------------------------------ links

    private static final String DECIMAL = "([-+]?\\d{1,3}(?:\\.\\d+)?)";

    /** In a link, most precise first: Google's place pin, then the marker forms, then a view's centre. */
    private static final Pattern[] LINK_PATTERNS = {
            Pattern.compile("!3d" + DECIMAL + "!4d" + DECIMAL),
            Pattern.compile("[?&]mlat=" + DECIMAL + "&mlon=" + DECIMAL),
            Pattern.compile("[?&](?:q|query|ll|center|destination)=" + DECIMAL
                    + "(?:,|%2C)(?:\\+|%20)?" + DECIMAL),
            Pattern.compile("@" + DECIMAL + "," + DECIMAL),
            Pattern.compile("map=\\d+(?:\\.\\d+)?/" + DECIMAL + "/" + DECIMAL),
    };

    // ------------------------------------------------------------------ printed coordinates

    /** Degrees, and minutes or seconds, with a decimal point or comma. */
    private static final String DEGREES = "\\d{1,3}(?:[.,]\\d+)?";
    private static final String SIXTIETHS = "\\d{1,2}(?:[.,]\\d+)?";

    /**
     * One coordinate without its hemisphere letter: sign, degrees, and optionally minutes and
     * seconds, each with its mark or without. Groups: sign, degrees, minutes, seconds.
     *
     * <p>Minutes and seconds are lazy ({@code ??}): without marks, "46,02 7,74" is read as two
     * decimal numbers before it is read as 46° 2' and 7° 74'. Each follows its mark or a space,
     * so digits are never split ("0207" is not 020° 7'), and spaces are only taken together
     * with what follows them, so a space can still be the separator between the two.
     */
    private static final String ANGLE = "([-+])?\\s*(" + DEGREES + ")(?:\\s*°)?"
            + "(?:(?:(?<=°)\\s*|\\s+)(" + SIXTIETHS + ")(?:\\s*')?"
            + "(?:(?:(?<=')\\s*|\\s+)(" + SIXTIETHS + ")(?:\\s*\")?)??)??";

    private static final String SEPARATOR = "(?:\\s*[,;/]\\s*|\\s+)";

    /** Letters after the numbers, or none: 45°58'35"N 7°39'31"E, 46.02, 7.74. Groups 1-5 and 6-10. */
    private static final Pattern PAIR_LETTERS_AFTER = pair(ANGLE + "(?:\\s*([NSEW]))?");

    /** Letters before the numbers: N 45° 58.583' E 007° 39.517'. Groups 1-5 and 6-10. */
    private static final Pattern PAIR_LETTERS_BEFORE = pair("([NSEW])\\s*" + ANGLE);

    private static Pattern pair(String one) {
        return Pattern.compile("^[(\\[{]?\\s*" + one + SEPARATOR + one + "\\s*[)\\]}]?$");
    }

    /** "lat:", "Latitude =", "lng", "longitudine:" and the like. */
    private static final Pattern LABELS = Pattern.compile(
            "\\b(?:lat|lon|lng)[a-z]*\\b\\s*[:=]?", Pattern.CASE_INSENSITIVE);

    /** Two bare whole numbers are much more likely a search than a place in the ocean. */
    private static final Pattern TWO_INTEGERS = Pattern.compile("^\\d+ \\d+$");

    /**
     * The coordinates written in {@code text}, or null if it is not coordinates.
     *
     * @return {latitude, longitude} in decimal degrees
     */
    public static double[] parseCoordinates(String text) {
        String s = cleanQuery(text);
        if (s.isEmpty()) {
            return null;
        }
        if (s.regionMatches(true, 0, "geo:", 0, 4)) {
            return CoordinateLinks.parseGeoUri(s);
        }
        if (s.contains("://")) {
            for (Pattern pattern : LINK_PATTERNS) {
                Matcher m = pattern.matcher(s);
                if (m.find()) {
                    return inRange(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)));
                }
            }
            return null;
        }

        s = LABELS.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim().toUpperCase(Locale.ROOT);
        if (TWO_INTEGERS.matcher(s).matches()) {
            return null;
        }
        Coordinate first;
        Coordinate second;
        Matcher m = PAIR_LETTERS_AFTER.matcher(s);
        if (m.matches()) {
            first = Coordinate.of(m, 1, m.group(5));
            second = Coordinate.of(m, 6, m.group(10));
        } else if ((m = PAIR_LETTERS_BEFORE.matcher(s)).matches()) {
            first = Coordinate.of(m, 2, m.group(1));
            second = Coordinate.of(m, 7, m.group(6));
        } else {
            return null;
        }
        if (first == null || second == null) {
            return null;
        }
        if (first.axis == 0 && second.axis == 0) {
            return inRange(first.value, second.value); // no letters: latitude first
        }
        if (first.axis == 'N' && second.axis == 'E') {
            return inRange(first.value, second.value);
        }
        if (first.axis == 'E' && second.axis == 'N') {
            return inRange(second.value, first.value);
        }
        return null; // two latitudes, or letters on only one of them
    }

    private static double[] inRange(double lat, double lon) {
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return null;
        }
        return new double[] {lat, lon};
    }

    /** One matched coordinate: its value in degrees, and 'N' (latitude), 'E' (longitude) or 0 (no letter). */
    private static final class Coordinate {
        final double value;
        final char axis;

        private Coordinate(double value, char axis) {
            this.value = value;
            this.axis = axis;
        }

        /** From the four {@link #ANGLE} groups starting at {@code sign}, and the letter if any. */
        static Coordinate of(Matcher m, int sign, String letter) {
            String degrees = m.group(sign + 1);
            String minutes = m.group(sign + 2);
            String seconds = m.group(sign + 3);
            // Only the last part given may have decimals: 46.5° 30' is not a coordinate.
            if ((minutes != null && hasDecimals(degrees)) || (seconds != null && hasDecimals(minutes))) {
                return null;
            }
            double min = minutes == null ? 0 : number(minutes);
            double sec = seconds == null ? 0 : number(seconds);
            if (min >= 60 || sec >= 60) {
                return null;
            }
            double value = number(degrees) + min / 60 + sec / 3600;
            boolean negative = "-".equals(m.group(sign))
                    ^ ("S".equals(letter) || "W".equals(letter));
            char axis = letter == null ? 0 : ("N".equals(letter) || "S".equals(letter)) ? 'N' : 'E';
            return new Coordinate(negative ? -value : value, axis);
        }

        private static boolean hasDecimals(String number) {
            return number.indexOf('.') >= 0 || number.indexOf(',') >= 0;
        }

        private static double number(String number) {
            return Double.parseDouble(number.replace(',', '.'));
        }
    }
}
