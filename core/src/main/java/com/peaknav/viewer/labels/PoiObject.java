package com.peaknav.viewer.labels;

import static com.peaknav.elevation.ElevationUtils.getElevationCorrectionForRoundEarth;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.Units.convertLatitsToMeters;
import static com.peaknav.utils.Units.convertLonitsToLatits;
import static com.peaknav.utils.Units.convertMetersToLatits;
import static com.peaknav.viewer.labels.DrawLabelCategory.PLACE;

import com.badlogic.gdx.math.Vector3;

import java.util.Comparator;
import java.util.Map;

public class PoiObject {

    public final String name;
    public final float lat, lon;
    /** Metres; NaN for a place or hut whose terrain was not loaded when it was read. */
    public float elevation;
    public transient DrawLabel drawLabel;
    public final int isolationParent;
    /** Metres of prominence from the map data, or -1 when it carries none. */
    public final float prominence;
    public final DrawLabelCategory drawLabelCategory;
    /** Its OpenStreetMap node id, for a link to it; 0 where not known. */
    public long osmId;
    private final Map<String, String> tags;


    private final Vector3 pos;
    private int population = -1;

    private static int getDistanceIntegerRange(PoiObject o1, double curLat, double curLon) {
        double distance1 = Math.sqrt(Math.pow(o1.lat - curLat, 2) + Math.pow(o1.lon - curLon, 2));
        int d1 = (int) Math.floor(2*distance1);
        return d1;
    }

    private static final double isolationFactor = 10.0;

    /**
     * The value {@code isolation_parent} carries when there is nothing higher anywhere near -
     * a sentinel, not a distance.
     *
     * <p>Divided by {@link #isolationFactor} it produced a score of a hundred million, which
     * EVERY prominent peak shared. So every pair of them compared equal, the sort left them
     * in whatever order the list happened to hold, and which name won a contested spot was
     * luck. That is why Mount Fuji appeared only sometimes, and why it lost to Kengamine -
     * the 380 m distant point on its own crater rim, which is 20 cm higher and tied with it
     * on every other term.
     */
    private static final int ISOLATION_UNKNOWN = 1_000_000_000;

    /**
     * How strong a claim a summit has on a contested label spot - larger wins.
     *
     * <p>Prominence decides where the data gives it. It is the measure that says which of two
     * summits IS the mountain: Fuji's is the whole 3776 m, while the point on its crater rim
     * 380 m away has none at all. Elevation cannot do that job here - the rim is 20 cm HIGHER,
     * which is exactly how it kept winning.
     *
     * <p>A documented prominence outranks any peak without one, hence the offset. OSM carries
     * the tag on the summits that matter, so "someone has recorded this mountain's prominence"
     * is itself the signal; a lesser hill that happens to carry one appearing ahead of an
     * untagged giant is the cost, and it is small, since both are drawn when there is room.
     *
     * <p>Without prominence the original rule stands: height, or isolation where that says
     * more - minus the sentinel, which used to make every prominent peak score alike.
     */
    public static double peakRank(float prominence, float elevation, int isolationParent) {
        if (prominence > 0) {
            return DOCUMENTED_PROMINENCE_OFFSET + prominence;
        }
        return Math.max(elevation, isolationScoreOf(isolationParent));
    }

    /** Above any elevation or isolation score, so a documented mountain always sorts first. */
    private static final double DOCUMENTED_PROMINENCE_OFFSET = 1e9;

    private static double isolationScoreOf(int isolationParent) {
        return isolationParent >= ISOLATION_UNKNOWN
                ? Double.NEGATIVE_INFINITY : isolationParent / isolationFactor;
    }

    /** The isolation-derived score, or nothing when the value is the sentinel above. */
    private static double isolationScore(PoiObject peak) {
        return peak.isolationParent >= ISOLATION_UNKNOWN
                ? Double.NEGATIVE_INFINITY : peak.isolationParent / isolationFactor;
    }

    public static Comparator<PoiObject> getComparatorPeaks(double curLat, double curLon, double curEle) {
        // double ele = convertLatitsToMeters((float)curEle);
        return (o1, o2) -> {
            return Double.compare(peakRank(o2.prominence, o2.elevation, o2.isolationParent),
                    peakRank(o1.prominence, o1.elevation, o1.isolationParent));
        };
    }

    /**
     * The order the non-peak labels - places and huts - claim contested spots in: earlier wins.
     *
     * <p>By {@link #labelWeight} over the distance, in km, from the viewer: how much a place
     * matters from here. The list spans some 130 km around the viewer, so size alone let every
     * mid-sized village in three provinces outrank the one the viewer stands in; distance alone
     * let a nameless locality nearby outrank the town behind it.
     *
     * <p>It used to be population first, which in the map data is patchy - around Trento half
     * the villages carry none while 188 hamlets do, so a hamlet of 49 outranked every untagged
     * village - and then a distance scaled by a list of place kinds that put suburb and
     * neighbourhood above town.
     */
    public static Comparator<PoiObject> getComparatorPois(double curLat, double curLon, double curEle) {
        final double kmPerDegreeLon = KM_PER_DEGREE * Math.cos(Math.toRadians(curLat));
        return (o1, o2) -> Double.compare(
                labelScore(o2.getLabelWeight(), distanceKm(o2, curLat, curLon, kmPerDegreeLon)),
                labelScore(o1.getLabelWeight(), distanceKm(o1, curLat, curLon, kmPerDegreeLon)));
    }

    private static final double KM_PER_DEGREE = 111.2;

    private static double distanceKm(PoiObject o, double curLat, double curLon, double kmPerDegreeLon) {
        double dy = (o.lat - curLat) * KM_PER_DEGREE;
        double dx = (o.lon - curLon) * kmPerDegreeLon;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * How much a label matters from a distance: its weight per km. Within the first km
     * distance stops counting, so the village underfoot does not outrank a city.
     */
    public static double labelScore(double weight, double distanceKm) {
        return weight / Math.max(distanceKm, 1.0);
    }

    private double labelWeight = -1;

    private double getLabelWeight() {
        if (labelWeight < 0)
            labelWeight = labelWeight(drawLabelCategory, tags);
        return labelWeight;
    }

    /**
     * How much a place matters, in something like inhabitants: its population where the data
     * has one, else a typical figure for its kind, so an untagged village ranks as a village
     * rather than last. A hamlet's figure is capped, since hamlets are often tagged with their
     * whole municipality's. A hut counts as a sizeable hamlet. Uninhabited names - localities,
     * squares - and regions, whose name at a single point says little, count for almost
     * nothing. A Wikipedia article, or at least a Wikidata entry, adds a little: better known.
     */
    public static double labelWeight(DrawLabelCategory category, Map<String, String> tags) {
        double weight;
        if (category == DrawLabelCategory.ALPINE_HUT) {
            weight = 100;
        } else {
            String place = tags == null ? null : tags.get("place");
            int population = parsePopulation(tags);
            weight = settlementWeight(place == null ? "" : place, population);
        }
        if (tags != null) {
            if (tags.containsKey("wikipedia"))
                weight *= 1.5;
            else if (tags.containsKey("wikidata"))
                weight *= 1.2;
        }
        return weight;
    }

    private static double settlementWeight(String place, int population) {
        switch (place) {
            case "city":
                return population > 0 ? population : 100_000;
            case "town":
            case "borough":
                return population > 0 ? population : 10_000;
            case "municipality":
            case "suburb":
                return population > 0 ? population : 1_000;
            case "village":
            case "island":
                return population > 0 ? population : 300;
            case "quarter":
                return population > 0 ? Math.min(population, 5_000) : 300;
            case "neighbourhood":
                return population > 0 ? Math.min(population, 2_000) : 100;
            case "hamlet":
                return population > 0 ? Math.min(population, 500) : 50;
            case "isolated_dwelling":
            case "farm":
                return 5;
            default:
                return 2;
        }
    }

    private int getPopulation() {
        if (population < 0)
            population = parsePopulation(tags);
        return population;
    }

    /** The {@code population} tag as a number, or 0 where it is missing or unreadable. */
    public static int parsePopulation(Map<String, String> tags) {
        String popS = tags == null ? null : tags.get("population");
        if (popS == null)
            return 0;
        try {
            return Integer.parseInt(popS.replaceAll("[., ]", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public Vector3 getPosition3D(Vector3 targetVector) {
        return targetVector.set(pos);
    }

    public void updatePositionToTargetLongitude() {
        pos.x = (float) convertLonitsToLatits(lon, getC().L.getTargetLatitude());
    }

    /** The object's map tags (OSM-style key/value pairs), read-only. Never null. */
    public Map<String, String> getTags() {
        return tags == null ? java.util.Collections.<String, String>emptyMap()
                : java.util.Collections.unmodifiableMap(tags);
    }

    public void fillDrawLabel(DrawLabelCategory drawLabelCategory) {
        drawLabel = new DrawLabel(drawLabelCategory, this);
        // drawLabel.updatePosition(true);
    }

    public PoiObject(
            String name, float lon, float lat,
            float elevation, Map<String, String> tags, float prominence,
            int isolationParent, DrawLabelCategory drawLabelCategory) {
        this.name = getC().transliterator.transliterate(name);
        this.lon = lon;
        this.lat = lat;
        float dz = convertLatitsToMeters(getElevationCorrectionForRoundEarth(lat, lon));
        this.elevation = elevation;
        this.isolationParent = isolationParent;
        this.drawLabelCategory = drawLabelCategory;
        this.tags = tags;
        this.prominence = prominence;
        this.pos = new Vector3();
        this.pos.set(
                (float)convertLonitsToLatits(lon, lat),
                lat,
                convertMetersToLatits(elevation - dz));
    }

    /**
     * Whether the height is known, filling it in from the terrain if it was not when the
     * object was read (see MapDataManager). Until it is, the object has no place in 3D and
     * is not labelled.
     */
    public boolean resolveElevation() {
        if (!Float.isNaN(elevation))
            return true;
        float terrain = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
        if (Float.isNaN(terrain))
            return false;
        float dz = convertLatitsToMeters(getElevationCorrectionForRoundEarth(lat, lon));
        pos.z = convertMetersToLatits(terrain - dz);
        elevation = terrain;
        return true;
    }

    public int hashCode() {
        return name.hashCode() + drawLabel.hashCode() + ((int)lat*1000000) + ((int)lon*1000000)
                + ((int)elevation*1000000);
    }

    public boolean equals(Object o) {
        if (o instanceof PoiObject) {
            PoiObject o1 = (PoiObject) o;
            return o1.hashCode() == hashCode();
        }
        return false;
    }
}
