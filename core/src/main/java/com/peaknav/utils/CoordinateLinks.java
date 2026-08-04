package com.peaknav.utils;

/**
 * Coordinates crossing the boundary between PeakNav and other apps: reading a point out of a
 * {@code geo:} URI, and building the web link that lists map services for a point.
 *
 * <p>Plain strings and doubles, no platform types, for two reasons. Android and the desktop
 * both need the same web link, and this is the one place it is written. And parsing is exactly
 * the part that can be wrong in ways a person would notice - a point in the wrong hemisphere,
 * or the Gulf of Guinea instead of nothing - so it belongs where it can be tested.
 */
public final class CoordinateLinks {

    private CoordinateLinks() {}

    /**
     * The coordinate carried by a {@code geo:} URI, in the forms other apps actually send.
     *
     * <pre>
     * geo:46.0207,7.7491                     a bare point
     * geo:46.0207,7.7491?z=15                with a zoom, which is not ours to use
     * geo:0,0?q=46.0207,7.7491(Matterhorn)   the marker form: q= carries the point
     * geo:0,0?q=Zermatt                      a search term - no coordinate to be had
     * </pre>
     *
     * @param uri the whole URI, or the part after {@code geo:}
     * @return {latitude, longitude}, or null when the URI carries no usable coordinate
     */
    public static double[] parseGeoUri(String uri) {
        if (uri == null) {
            return null;
        }
        String rest = uri;
        int scheme = rest.indexOf(':');
        if (scheme >= 0 && rest.substring(0, scheme).equalsIgnoreCase("geo")) {
            rest = rest.substring(scheme + 1);
        }
        String path = rest;
        String query = null;
        int mark = rest.indexOf('?');
        if (mark >= 0) {
            path = rest.substring(0, mark);
            query = rest.substring(mark + 1);
        }

        // q= wins when it holds a coordinate: the marker form leaves the path at 0,0.
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.length() > 2 && part.substring(0, 2).equalsIgnoreCase("q=")) {
                    String value = decode(part.substring(2));
                    int label = value.indexOf('(');
                    if (label >= 0) {
                        value = value.substring(0, label);
                    }
                    double[] fromQuery = parseLatLon(value);
                    if (fromQuery != null) {
                        return fromQuery;
                    }
                }
            }
        }

        double[] fromPath = parseLatLon(path);
        // geo:0,0 with no usable q= means "no location given", not the Gulf of Guinea.
        if (fromPath != null && fromPath[0] == 0 && fromPath[1] == 0) {
            return null;
        }
        return fromPath;
    }

    /** "46.0207,7.7491" as a checked pair, or null if it is not one. */
    public static double[] parseLatLon(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                return null;
            }
            return new double[]{lat, lon};
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Wikipedia's GeoHack page for a point: the page its coordinate links lead to, listing the
     * services that can show it - OpenStreetMap, Google, Bing, topographic and aerial imagery,
     * and the national mapping agency for whichever country the point falls in.
     *
     * <p>Used on the desktop, where there is no system-wide notion of "open this point", and on
     * Android only if the device has no map app at all.
     *
     * @param languageCode two-letter language for the page, e.g. from the interface language
     */
    public static String geoHackUrl(double latitude, double longitude, String languageCode) {
        String language = (languageCode == null || languageCode.trim().isEmpty())
                ? "en" : languageCode.trim();
        return String.format(java.util.Locale.ENGLISH,
                "https://geohack.toolforge.org/geohack.php?params=%.6f;%.6f&language=%s",
                latitude, longitude, language);
    }

    /** Percent-decoding, enough for the query values a geo: URI carries. */
    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return s;
        }
    }
}
