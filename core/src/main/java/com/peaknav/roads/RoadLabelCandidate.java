package com.peaknav.roads;

/**
 * A spot where a way's name or number may be written: a point on the way, and a short stretch
 * of it on either side - the stretch whose direction on screen the text is tilted to follow.
 *
 * <p>The planner lays spots out densely; which of them are used, and what each says, depends on
 * the label frequency the user has chosen, and is decided here ({@link #label(int)}) at the
 * moment of drawing - so moving the frequency slider redraws no tile and replans nothing.
 */
public final class RoadLabelCandidate {

    /** The way's name, or null. */
    public final String name;
    /** For trails and tracks, the route number(s) waymarked along it ("12/E5"), or null. */
    public final String number;
    public final RoadClass roadClass;
    /** The way's rank or difficulty, as in {@link RoadFeature#attribute}. */
    public final float attribute;
    /** Samples along the way, evenly spaced, with the label's anchor at {@link #anchor}. */
    public final double[] lat;
    public final double[] lon;
    public final int anchor;
    /** Length of the whole way, in metres: longer ways win a crowded spot. */
    public final double wayLengthMeters;
    /**
     * This spot's place along the way, counted from the way's middle spot (0) - negative before
     * it, positive after. A frequency keeping every n-th spot keeps those divisible by n, so
     * whatever the frequency, the labels stay centred on the way.
     */
    public final int spot;

    /**
     * World coordinates of the samples, three floats each, filled in on the render thread once
     * the terrain under them has loaded; valid for the target latitude in
     * {@link #worldTargetLatitude}, which the world's x axis is scaled by.
     */
    public float[] world;
    public float worldTargetLatitude = Float.NaN;

    public RoadLabelCandidate(String name, String number, RoadClass roadClass, float attribute,
                              double[] lat, double[] lon, int anchor, double wayLengthMeters,
                              int spot) {
        if (lat.length != lon.length || anchor < 0 || anchor >= lat.length) {
            throw new IllegalArgumentException("bad samples");
        }
        this.name = name;
        this.number = number;
        this.roadClass = roadClass;
        this.attribute = attribute;
        this.lat = lat;
        this.lon = lon;
        this.anchor = anchor;
        this.wayLengthMeters = wayLengthMeters;
        this.spot = spot;
    }

    public double anchorLatitude() {
        return lat[anchor];
    }

    public double anchorLongitude() {
        return lon[anchor];
    }

    public int size() {
        return lat.length;
    }

    /** Trails and tracks are written on a plate of their own colour; roads and rivers are not. */
    public boolean isTrail() {
        return roadClass == RoadClass.PATH || roadClass == RoadClass.TRACK;
    }

    /** Whether a frequency keeping every {@code stride}-th spot keeps this one. */
    public boolean kept(int stride) {
        int s = Math.max(1, stride);
        return ((spot % s) + s) % s == 0;
    }

    /**
     * Whether, at this stride, this is one of a trail's name spots: they alternate with number
     * spots, starting from the middle of the way.
     */
    private boolean nameSpot(int stride) {
        int j = spot / Math.max(1, stride);
        return ((j % 2) + 2) % 2 == 0;
    }

    /**
     * What is written here when every {@code stride}-th spot is used, or null when nothing is.
     * A road: its name. A trail or track: at a name spot its number and name together ("12 ·
     * Sentiero dei Fiori"), at the spots between its number alone; a trail with only a name is
     * named at every second spot, one with only a number numbered at every spot.
     */
    public String label(int stride) {
        if (!kept(stride)) {
            return null;
        }
        if (!isTrail()) {
            return name;
        }
        if (nameSpot(stride) && name != null) {
            return number != null ? number + RoadLabelPlanner.NUMBER_NAME_SEPARATOR + name : name;
        }
        return number;
    }

    /** Whether what is written here at this stride is a trail's number alone. */
    public boolean isNumberOnly(int stride) {
        return isTrail() && kept(stride) && number != null && !(nameSpot(stride) && name != null);
    }

    /**
     * For a spot carrying both a trail's number and its name, the number alone: written instead
     * where the whole label is too long for the stretch of trail the camera sees, so the number
     * still shows. Null otherwise.
     */
    public String shortText(int stride) {
        return isTrail() && kept(stride) && nameSpot(stride) && name != null ? number : null;
    }

    /**
     * Precedence in a crowded spot: roads over trails over tracks, a major road over a minor
     * one, and within that the longer way. A trail's bare number outranks its name: it is what
     * the waymarks carry, and being short it rarely displaces anything.
     */
    public float priority(int stride) {
        float base;
        switch (roadClass) {
            case ROAD:
                base = 3f + attribute;
                break;
            case WATER:
                base = 3.5f;
                break;
            case PATH:
                base = 2f;
                break;
            default:
                base = 1f;
                break;
        }
        if (isNumberOnly(stride)) {
            return base + 0.95f;
        }
        return base + (float) Math.min(0.9, wayLengthMeters / 20000.0);
    }
}
