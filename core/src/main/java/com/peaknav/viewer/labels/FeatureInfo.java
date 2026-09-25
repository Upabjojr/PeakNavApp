package com.peaknav.viewer.labels;

import static com.peaknav.utils.PeakNavUtils.s;

import com.peaknav.areas.MapArea;
import com.peaknav.gpx.GpxTrackStats;
import com.peaknav.utils.FontCharacters;
import com.peaknav.utils.PreferencesManager.UnitSystem;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is known about the thing a label names, told in the reader's words, for the pane a tapped
 * label opens: what it is, how high, how far and which way from here, where, and what the map
 * data says of it - its other names, who runs it, how many beds, its website and encyclopaedia
 * entries - then every tag the data carries, as it carries them.
 *
 * <p>Peaks, huts and places come from the map's nodes with all their OpenStreetMap tags; lakes,
 * islands, ranges and towns drawn as areas carry only their size, height, population and a
 * Wikidata item, so that is what they tell.
 */
public final class FeatureInfo {

    /** One line of the pane: what it is, its value, and where tapping it leads (null for nowhere). */
    public static final class Row {
        public final String label;
        public final String value;
        public final String url;

        Row(String label, String value, String url) {
            this.label = label;
            this.value = value;
            this.url = url;
        }

        @Override
        public String toString() {
            return label + ": " + value + (url != null ? " <" + url + ">" : "");
        }
    }

    public final String title;
    public final String kind;
    public final List<Row> rows;
    /** The map data's own tags, key and value, sorted by key; empty for an area. */
    public final List<Row> tags;
    public final double latitude, longitude;
    /** Its Wikidata entry ("Q1374"), whose picture the pane shows when online; null if none. */
    public final String wikidataId;

    private FeatureInfo(String title, String kind, List<Row> rows, List<Row> tags, double latitude, double longitude,
                        String wikidataId) {
        this.title = title;
        this.kind = kind;
        this.rows = Collections.unmodifiableList(rows);
        this.tags = Collections.unmodifiableList(tags);
        this.latitude = latitude;
        this.longitude = longitude;
        this.wikidataId = wikidataLink(wikidataId) != null ? wikidataId.trim() : null;
    }

    /** Where the reader stands, to tell how far away and which way; NaN for not known. */
    public static final class Viewer {
        final double latitude, longitude;
        final String language;
        final UnitSystem units;

        public Viewer(double latitude, double longitude, String language, UnitSystem units) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.language = language;
            this.units = units;
        }
    }

    /** Names kept out of the list of tags: a capital's sixty translations would bury the rest. */
    private static boolean listed(String key) {
        return !key.startsWith("name:") && !key.equals("isolation_parent");
    }

    /** A peak, a hut or a place, from its map tags. */
    public static FeatureInfo of(PoiObject poi, Viewer viewer) {
        Map<String, String> tags = poi.getTags();
        List<Row> rows = new ArrayList<>();
        String elevation = GpxTrackStats.formatHeight(poi.elevation, viewer.units);
        // Without an ele tag the height is the terrain's under the point, and says so.
        add(rows, "Feature_elevation", tags.containsKey("ele") ? elevation : "~ " + elevation, null);
        if (poi.prominence > 0) {
            add(rows, "Feature_prominence", GpxTrackStats.formatHeight(poi.prominence, viewer.units), null);
        }
        whereFrom(rows, poi.lat, poi.lon, viewer);
        add(rows, "Feature_other_names", otherNames(poi.name, tags, viewer.language), null);
        add(rows, "Feature_operator", tags.get("operator"), null);
        add(rows, "Feature_capacity", tags.get("capacity"), null);
        add(rows, "Feature_opening_hours", tags.get("opening_hours"), null);
        String phone = first(tags, "phone", "contact:phone");
        add(rows, "Feature_phone", phone, phone == null ? null : "tel:" + phone.replaceAll("[^+0-9]", ""));
        String email = first(tags, "email", "contact:email");
        add(rows, "Feature_email", email, email == null ? null : "mailto:" + email);
        String website = first(tags, "website", "contact:website", "url");
        add(rows, "Feature_website", website, webLink(website));
        String population = tags.get("population");
        add(rows, "Feature_population", population, null);
        add(rows, "Feature_description", tags.get("description"), null);
        String wikipedia = tags.get("wikipedia");
        add(rows, "Feature_wikipedia", wikipedia, wikipediaLink(wikipedia));
        String wikidata = tags.get("wikidata");
        add(rows, "Feature_wikidata", wikidata, wikidataLink(wikidata));
        if (poi.osmId > 0) {
            add(rows, "Feature_openstreetmap", "node " + poi.osmId, osmNodeLink(poi.osmId));
        }

        List<String> keys = new ArrayList<>(tags.keySet());
        Collections.sort(keys);
        List<Row> listed = new ArrayList<>();
        for (String key : keys) {
            // A value the fonts cannot draw would be a row of boxes.
            if (listed(key) && !FontCharacters.containsUnrenderable(tags.get(key))) {
                listed.add(new Row(key, tags.get(key), tagLink(key, tags.get(key))));
            }
        }
        return new FeatureInfo(poi.name, kindOf(poi.drawLabelCategory, tags), rows, listed, poi.lat, poi.lon,
                wikidata);
    }

    /** A lake, an island, a range or a town, from what the areas' data carries. */
    public static FeatureInfo of(MapArea area, Viewer viewer) {
        List<Row> rows = new ArrayList<>();
        String type = area.type.toLowerCase(Locale.ROOT);
        boolean lake = "lake".equals(type);
        if (area.peakMeters != 0 || lake) {
            // A lake's height is its surface's; anything else's, its highest point's.
            add(rows, lake ? "Feature_elevation" : "Feature_highest_point",
                    GpxTrackStats.formatHeight(area.peakMeters, viewer.units), null);
        }
        if (area.semiMajorKm > 0) {
            add(rows, "Feature_size", "~ " + GpxTrackStats.formatDistance(2000 * area.semiMajorKm, viewer.units)
                    + " × " + GpxTrackStats.formatDistance(2000 * area.semiMinorKm, viewer.units), null);
        }
        whereFrom(rows, area.lat, area.lon, viewer);
        if (area.population > 0) {
            add(rows, "Feature_population", String.valueOf(area.population), null);
        }
        add(rows, "Feature_wikidata", area.wikidataId, wikidataLink(area.wikidataId));
        return new FeatureInfo(area.name, areaKind(type), rows, new ArrayList<Row>(), area.lat, area.lon,
                area.wikidataId);
    }

    /** A marker the user saved: its height, where it is from here, and when it was saved. */
    public static FeatureInfo of(com.peaknav.markers.Marker marker, Viewer viewer) {
        List<Row> rows = new ArrayList<>();
        if (!Double.isNaN(marker.elevation)) {
            add(rows, "Feature_elevation", GpxTrackStats.formatHeight(marker.elevation, viewer.units), null);
        }
        whereFrom(rows, marker.latitude, marker.longitude, viewer);
        if (marker.created > 0) {
            add(rows, "Marker_saved_on", java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(marker.created)), null);
        }
        return new FeatureInfo(marker.name, s("Marker_kind"), rows, new ArrayList<Row>(),
                marker.latitude, marker.longitude, null);
    }

    /** Distance, direction and coordinates. */
    private static void whereFrom(List<Row> rows, double lat, double lon, Viewer viewer) {
        if (!Double.isNaN(viewer.latitude) && !Double.isNaN(viewer.longitude)) {
            double metres = distanceMetres(viewer.latitude, viewer.longitude, lat, lon);
            add(rows, "Feature_distance", GpxTrackStats.formatDistance(metres, viewer.units), null);
            if (metres >= 1) {
                double bearing = bearingDegrees(viewer.latitude, viewer.longitude, lat, lon);
                add(rows, "Feature_direction", compassPoint(bearing) + " " + Math.round(bearing) % 360 + "°", null);
            }
        }
        add(rows, "Feature_coordinates", GpxTrackStats.formatPosition(lat, lon),
                String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=15/%.5f/%.5f",
                        lat, lon, lat, lon));
    }

    private static void add(List<Row> rows, String key, String value, String url) {
        if (value != null && !value.trim().isEmpty() && !FontCharacters.containsUnrenderable(value)) {
            rows.add(new Row(s(key), value.trim(), url));
        }
    }

    private static String first(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    /** Its names other than the one on the label: in the reader's language, and the alternatives. */
    static String otherNames(String shown, Map<String, String> tags, String language) {
        List<String> names = new ArrayList<>();
        String[] keys = {"name", language == null ? null : "name:" + language, "official_name", "alt_name",
                "old_name", "loc_name", "short_name"};
        for (String key : keys) {
            String value = key == null ? null : tags.get(key);
            if (value == null) {
                continue;
            }
            // alt_name may hold several, separated by semicolons.
            for (String name : value.split(";")) {
                String n = name.trim();
                if (!n.isEmpty() && !n.equals(shown) && !names.contains(n)
                        && !FontCharacters.containsUnrenderable(n)) {
                    names.add(n);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(name);
        }
        return out.length() == 0 ? null : out.toString();
    }

    static String kindOf(DrawLabelCategory category, Map<String, String> tags) {
        if ("volcano".equals(tags.get("natural"))) {
            return s("Feature_kind_volcano");
        }
        if (category == DrawLabelCategory.PEAK) {
            return s("Feature_kind_peak");
        }
        if (category == DrawLabelCategory.ALPINE_HUT) {
            return s("Feature_kind_alpine_hut");
        }
        String place = tags.get("place");
        if (place != null) {
            switch (place) {
                case "city": case "town": case "village": case "hamlet": case "suburb": case "locality":
                case "isolated_dwelling": case "island":
                    return s("Feature_kind_" + place);
                default:
                    return s("Feature_kind_place");
            }
        }
        return s("Feature_kind_place");
    }

    static String areaKind(String type) {
        switch (type) {
            case "lake": case "island": case "city": case "region":
                return s("Feature_kind_" + type);
            case "mountain_range": case "mountain_group":
                return s("Feature_kind_mountain_range");
            default:
                return s("Feature_kind_place");
        }
    }

    /** A website as tagged, often without its scheme. */
    static String webLink(String website) {
        if (website == null) {
            return null;
        }
        String w = website.trim();
        int semicolon = w.indexOf(';');
        if (semicolon > 0) {
            w = w.substring(0, semicolon).trim();
        }
        return w.contains("://") ? w : "https://" + w;
    }

    /** "de:Matterhorn" to the article on the German Wikipedia. */
    static String wikipediaLink(String wikipedia) {
        if (wikipedia == null) {
            return null;
        }
        int colon = wikipedia.indexOf(':');
        if (colon <= 0 || colon == wikipedia.length() - 1) {
            return null;
        }
        String language = wikipedia.substring(0, colon).trim();
        String title = wikipedia.substring(colon + 1).trim().replace(' ', '_');
        return "https://" + language + ".wikipedia.org/wiki/" + encode(title);
    }

    static String osmNodeLink(long id) {
        return "https://www.openstreetmap.org/node/" + id;
    }

    /**
     * Where a tag's value leads, if anywhere: a web address as it is, a website tagged without
     * its scheme, an e-mail or a phone number, and the references to Wikipedia, Wikidata and
     * Wikimedia Commons - also the prefixed ones, like brand:wikidata. Null for the rest.
     */
    static String tagLink(String key, String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("http://") || v.startsWith("https://")) {
            int semicolon = v.indexOf(';');
            return semicolon > 0 ? v.substring(0, semicolon).trim() : v;
        }
        if (key.equals("website") || key.equals("url") || key.equals("contact:website")) {
            return webLink(v);
        }
        if (key.equals("email") || key.equals("contact:email")) {
            return "mailto:" + v;
        }
        if (key.equals("phone") || key.equals("contact:phone") || key.equals("contact:mobile")) {
            return "tel:" + v.replaceAll("[^+0-9]", "");
        }
        if (key.equals("wikidata") || key.endsWith(":wikidata")) {
            return wikidataLink(v);
        }
        if (key.equals("wikipedia") || key.endsWith(":wikipedia")) {
            return wikipediaLink(v);
        }
        if (key.equals("wikimedia_commons") || key.equals("image") && v.startsWith("File:")) {
            return "https://commons.wikimedia.org/wiki/" + encode(v.replace(' ', '_'));
        }
        return null;
    }

    static String wikidataLink(String wikidata) {
        if (wikidata == null || !wikidata.trim().matches("Q[0-9]+")) {
            return null;
        }
        return "https://www.wikidata.org/wiki/" + wikidata.trim();
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%2F", "/").replace("%3A", ":");
        } catch (UnsupportedEncodingException e) {
            return text;
        }
    }

    static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** The initial bearing from the first point to the second, degrees clockwise from north. */
    static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return b < 0 ? b + 360 : b;
    }

    private static final String[] COMPASS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    /** "NNE", in the letters the reader's language gives the cardinal points. */
    static String compassPoint(double bearing) {
        String pattern = COMPASS[(int) Math.round(bearing / 22.5) % 16];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            switch (pattern.charAt(i)) {
                case 'N': out.append(s("Compass_north")); break;
                case 'E': out.append(s("Compass_east")); break;
                case 'S': out.append(s("Compass_south")); break;
                default: out.append(s("Compass_west")); break;
            }
        }
        return out.toString();
    }
}
