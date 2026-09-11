package com.peaknav.roads;

/**
 * One way as the road renderer sees it: its class, a sub-type, its geometry and the name to
 * label it with. Immutable, and free of any map-library type, so the rasterizer and the label
 * planner can be tested with nothing but coordinates.
 */
public final class RoadFeature {

    /** Road rank: service roads and pedestrian streets. */
    public static final float RANK_SERVICE = 0f;
    /** Road rank: tertiary, unclassified, residential. */
    public static final float RANK_LOCAL = 0.5f;
    /** Road rank: motorways, trunk, primary and secondary roads. */
    public static final float RANK_MAJOR = 1f;

    /** Trail difficulty: hiking (SAC T1), or no difficulty given. */
    public static final float TRAIL_EASY = 0f;
    /** Trail difficulty: mountain hiking (T2, T3). */
    public static final float TRAIL_MOUNTAIN = 0.5f;
    /** Trail difficulty: alpine hiking (T4 to T6) and via ferratas. */
    public static final float TRAIL_ALPINE = 1f;

    /** Piste difficulties, as stored in the aux texture and decoded by the shader. */
    public static final float PISTE_NOVICE = 0f;
    public static final float PISTE_EASY = 0.2f;
    public static final float PISTE_INTERMEDIATE = 0.4f;
    public static final float PISTE_ADVANCED = 0.6f;
    public static final float PISTE_EXPERT = 0.8f;
    public static final float PISTE_NORDIC = 1f;

    public final RoadClass roadClass;
    /**
     * The sub-type within the class, 0..1: road rank, trail difficulty or piste difficulty (see
     * the constants above). Unused for tracks and water.
     */
    public final float attribute;
    public final double[] lat;
    public final double[] lon;
    /** A closed way drawn filled (a piste area) rather than as a line along its outline. */
    public final boolean area;
    /** What to write beside it, or null when it should not be labelled. */
    public final String name;
    /**
     * For trails and tracks, the route number or numbers waymarked along it ("12", "12/E5"), or
     * null. Kept apart from the name because a hiker follows the number, so it is written more
     * often than the name is.
     */
    public final String number;

    public RoadFeature(RoadClass roadClass, float attribute, double[] lat, double[] lon,
                       boolean area, String name) {
        this(roadClass, attribute, lat, lon, area, name, null);
    }

    public RoadFeature(RoadClass roadClass, float attribute, double[] lat, double[] lon,
                       boolean area, String name, String number) {
        if (lat.length != lon.length) {
            throw new IllegalArgumentException("lat and lon differ in length");
        }
        this.roadClass = roadClass;
        this.attribute = attribute;
        this.lat = lat;
        this.lon = lon;
        this.area = area;
        this.name = name;
        this.number = number;
    }

    public int size() {
        return lat.length;
    }

    /** Ground length in metres, along the way. */
    public double lengthMeters() {
        double total = 0;
        for (int i = 1; i < lat.length; i++) {
            total += RoadGeo.metersBetween(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        return total;
    }
}
