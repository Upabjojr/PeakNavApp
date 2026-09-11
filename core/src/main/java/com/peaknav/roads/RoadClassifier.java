package com.peaknav.roads;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.map.datastore.Way;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns OpenStreetMap ways into {@link RoadFeature}s: which class each is drawn as, its rank or
 * difficulty, and the name to label it with.
 *
 * <p>The tag lists come from {@code PbfTileBinaryParser}, which appends the tags of every
 * relation a way belongs to after the way's own. That is useful - a hiking route's number and a
 * ski route's difficulty reach the ways it is made of - but also a trap: a bus route's name would
 * label every unnamed street it runs along. So the list is read in groups, split at each
 * {@code type} tag (relations carry one, ways do not): the first group is the way's own, and a
 * relation's name is borrowed only from routes worth labelling a trail with.
 */
public final class RoadClassifier {

    private RoadClassifier() {
    }

    /** Every drawable or labellable feature among these ways, in their order. */
    public static List<RoadFeature> classifyAll(List<Way> ways) {
        List<RoadFeature> out = new ArrayList<>(ways.size());
        for (Way way : ways) {
            if (way == null || way.latLongs == null) {
                continue;
            }
            for (LatLong[] line : way.latLongs) {
                if (line == null || line.length < 2) {
                    continue;
                }
                classify(way.tags, line, out);
            }
        }
        return out;
    }

    /**
     * Adds the features of one way to {@code out}: none, one, or two when it is both - a track
     * that is also a piste in winter is drawn as both.
     */
    public static void classify(List<Tag> tags, LatLong[] points, List<RoadFeature> out) {
        if (tags == null || points == null || points.length < 2) {
            return;
        }
        int ownEnd = ownTagCount(tags);
        double[] lat = new double[points.length];
        double[] lon = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            if (points[i] == null) {
                return; // a node the extract did not carry: nothing sensible to draw
            }
            lat[i] = points[i].latitude;
            lon[i] = points[i].longitude;
        }
        boolean closed = lat[0] == lat[lat.length - 1] && lon[0] == lon[lon.length - 1]
                && points.length > 3;

        String highway = value(tags, 0, ownEnd, "highway");
        boolean tunnel = isTunnel(value(tags, 0, ownEnd, "tunnel"));
        // A square or a pedestrian zone mapped as an area: its outline is not a way anyone
        // walks along, and drawn as one it fills a town centre with trail-coloured blobs.
        String areaTag = value(tags, 0, ownEnd, "area");
        boolean plaza = closed && areaTag != null && areaTag.equalsIgnoreCase("yes");

        if (highway != null && !tunnel && !plaza) {
            RoadFeature road = classifyHighway(tags, ownEnd, highway, lat, lon);
            if (road != null) {
                out.add(road);
            }
        }

        // Pistes: from the way's own tags, or from a piste route it belongs to (whose members
        // often carry nothing themselves).
        String pisteType = value(tags, 0, tags.size(), "piste:type");
        if (pisteType != null) {
            RoadFeature piste = classifyPiste(tags, pisteType, closed, lat, lon);
            if (piste != null) {
                out.add(piste);
            }
        }

        String waterway = value(tags, 0, ownEnd, "waterway");
        if (waterway != null && !tunnel && highway == null) {
            String w = waterway.toLowerCase(Locale.ROOT);
            if (w.equals("river") || w.equals("stream") || w.equals("canal")) {
                out.add(new RoadFeature(RoadClass.WATER, 0f, lat, lon, false,
                        roadName(tags, ownEnd)));
            }
        }
    }

    private static RoadFeature classifyHighway(List<Tag> tags, int ownEnd, String highway,
                                               double[] lat, double[] lon) {
        String h = highway.toLowerCase(Locale.ROOT);
        switch (h) {
            case "motorway": case "trunk": case "primary": case "secondary":
            case "motorway_link": case "trunk_link": case "primary_link": case "secondary_link":
                return new RoadFeature(RoadClass.ROAD, RoadFeature.RANK_MAJOR, lat, lon, false,
                        roadName(tags, ownEnd));
            case "tertiary": case "tertiary_link": case "unclassified": case "residential":
            case "living_street": case "road":
                return new RoadFeature(RoadClass.ROAD, RoadFeature.RANK_LOCAL, lat, lon, false,
                        roadName(tags, ownEnd));
            case "service": case "pedestrian":
                return new RoadFeature(RoadClass.ROAD, RoadFeature.RANK_SERVICE, lat, lon, false,
                        roadName(tags, ownEnd));
            case "track":
                return new RoadFeature(RoadClass.TRACK, 0f, lat, lon, false,
                        trailName(tags, ownEnd), trailNumber(tags, ownEnd));
            case "footway": case "cycleway":
                // The pavements along every street, and the crossings between them: in a town
                // they would trace each road twice more with trail dashes.
                String footway = value(tags, 0, ownEnd, "footway");
                if (footway != null) {
                    String f = footway.toLowerCase(Locale.ROOT);
                    if (f.equals("sidewalk") || f.equals("crossing")) {
                        return null;
                    }
                }
                return path(tags, ownEnd, lat, lon, false);
            case "path": case "steps": case "bridleway":
                return path(tags, ownEnd, lat, lon, false);
            case "via_ferrata":
                return path(tags, ownEnd, lat, lon, true);
            default:
                // construction, proposed, platform, raceway, corridor, bus_stop, ...: not ways
                // anyone walks or drives on the ground the map shows.
                return null;
        }
    }

    private static RoadFeature path(List<Tag> tags, int ownEnd, double[] lat, double[] lon,
                                    boolean viaFerrata) {
        float difficulty = viaFerrata ? RoadFeature.TRAIL_ALPINE
                : trailDifficulty(value(tags, 0, ownEnd, "sac_scale"));
        return new RoadFeature(RoadClass.PATH, difficulty, lat, lon, false,
                trailName(tags, ownEnd), trailNumber(tags, ownEnd));
    }

    /**
     * The SAC hiking scale, folded into the three grades a trail map shows: hiking (yellow),
     * mountain hiking (red) and alpine (blue) - the colours of Swiss waymarks, and close to how
     * most Alpine maps grade their trails. Free-form values such as "T4 - exposed" are read by
     * their grade.
     */
    public static float trailDifficulty(String sacScale) {
        if (sacScale == null) {
            return RoadFeature.TRAIL_EASY;
        }
        String s = sacScale.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("t") && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            int grade = s.charAt(1) - '0';
            if (grade <= 1) {
                return RoadFeature.TRAIL_EASY;
            }
            return grade <= 3 ? RoadFeature.TRAIL_MOUNTAIN : RoadFeature.TRAIL_ALPINE;
        }
        switch (s) {
            case "mountain_hiking":
            case "demanding_mountain_hiking":
                return RoadFeature.TRAIL_MOUNTAIN;
            case "alpine_hiking":
            case "demanding_alpine_hiking":
            case "difficult_alpine_hiking":
                return RoadFeature.TRAIL_ALPINE;
            default:
                return RoadFeature.TRAIL_EASY;
        }
    }

    private static RoadFeature classifyPiste(List<Tag> tags, String pisteType, boolean closed,
                                             double[] lat, double[] lon) {
        String t = pisteType.toLowerCase(Locale.ROOT);
        String area = value(tags, 0, tags.size(), "area");
        boolean isArea = closed && (area == null || !area.equalsIgnoreCase("no"));
        if (t.equals("downhill")) {
            String difficulty = value(tags, 0, tags.size(), "piste:difficulty");
            return new RoadFeature(RoadClass.PISTE, pisteDifficulty(difficulty), lat, lon,
                    isArea, null);
        }
        if (t.equals("nordic")) {
            // Cross-country loops are closed lines, not areas, unless they say otherwise.
            boolean nordicArea = closed && area != null && area.equalsIgnoreCase("yes");
            return new RoadFeature(RoadClass.PISTE, RoadFeature.PISTE_NORDIC, lat, lon,
                    nordicArea, null);
        }
        return null;
    }

    public static float pisteDifficulty(String difficulty) {
        if (difficulty == null) {
            return RoadFeature.PISTE_INTERMEDIATE; // the most common grade, and a neutral red
        }
        switch (difficulty.trim().toLowerCase(Locale.ROOT)) {
            case "novice":
                return RoadFeature.PISTE_NOVICE;
            case "easy":
                return RoadFeature.PISTE_EASY;
            case "advanced":
                return RoadFeature.PISTE_ADVANCED;
            case "expert":
            case "freeride":
            case "extreme":
                return RoadFeature.PISTE_EXPERT;
            default:
                return RoadFeature.PISTE_INTERMEDIATE;
        }
    }

    /** A road's name: its own, else its own reference ("SS38", "E62"). */
    static String roadName(List<Tag> tags, int ownEnd) {
        String name = value(tags, 0, ownEnd, "name");
        return name != null ? name : value(tags, 0, ownEnd, "ref");
    }

    /**
     * A trail's name: its own, else the name of a hiking route it is part of - on a signposted
     * network, what the signposts say.
     */
    static String trailName(List<Tag> tags, int ownEnd) {
        String name = value(tags, 0, ownEnd, "name");
        if (name != null) {
            return name;
        }
        // Relation groups: [type=route, route=hiking, name=..., ref=...] one after another.
        int start = ownEnd;
        while (start < tags.size()) {
            int end = nextGroup(tags, start + 1);
            String route = value(tags, start, end, "route");
            if (route != null && isTrailRoute(route)) {
                String routeName = value(tags, start, end, "name");
                if (routeName != null) {
                    return routeName;
                }
            }
            start = end;
        }
        return null;
    }

    /** At most this many route numbers are written together: past it the label is a list. */
    private static final int MAX_NUMBERS = 3;

    /**
     * A trail's numbers, as the waymarks show them: its own reference first, then those of the
     * hiking routes it carries, each once, joined with a slash ("12/E5"). Null if it has none.
     */
    static String trailNumber(List<Tag> tags, int ownEnd) {
        List<String> numbers = new ArrayList<>(MAX_NUMBERS);
        addNumber(numbers, value(tags, 0, ownEnd, "ref"));
        int start = ownEnd;
        while (start < tags.size() && numbers.size() < MAX_NUMBERS) {
            int end = nextGroup(tags, start + 1);
            String route = value(tags, start, end, "route");
            if (route != null && isTrailRoute(route)) {
                addNumber(numbers, value(tags, start, end, "ref"));
            }
            start = end;
        }
        if (numbers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(numbers.get(i));
        }
        return sb.toString();
    }

    private static void addNumber(List<String> numbers, String ref) {
        if (ref == null) {
            return;
        }
        String r = ref.trim();
        if (!r.isEmpty() && !numbers.contains(r) && numbers.size() < MAX_NUMBERS) {
            numbers.add(r);
        }
    }

    private static boolean isTrailRoute(String route) {
        String r = route.toLowerCase(Locale.ROOT);
        return r.equals("hiking") || r.equals("foot") || r.equals("mtb") || r.equals("bicycle");
    }

    private static boolean isTunnel(String tunnel) {
        if (tunnel == null) {
            return false;
        }
        String t = tunnel.toLowerCase(Locale.ROOT);
        // A road under a mountain is not on the surface the texture is draped over.
        return t.equals("yes") || t.equals("culvert") || t.equals("building_passage");
    }

    /** How many tags at the head of the list are the way's own: everything before a relation. */
    static int ownTagCount(List<Tag> tags) {
        return nextGroup(tags, 0);
    }

    private static int nextGroup(List<Tag> tags, int from) {
        for (int i = from; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (tag != null && "type".equals(tag.key)) {
                return i;
            }
        }
        return tags.size();
    }

    /** The first non-empty value of {@code key} within {@code [from, to)}. */
    static String value(List<Tag> tags, int from, int to, String key) {
        for (int i = from; i < to && i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (tag != null && key.equals(tag.key) && tag.value != null && !tag.value.isEmpty()) {
                return tag.value;
            }
        }
        return null;
    }
}
