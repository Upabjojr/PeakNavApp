package com.peaknav.roads;

/**
 * A place where a way's name or number may be written: a point on the way, and a short stretch
 * of it on either side - the stretch whose direction on screen the text is tilted to follow.
 */
public final class RoadLabelCandidate {

    public final String text;
    public final RoadClass roadClass;
    /** The way's rank or difficulty, as in {@link RoadFeature#attribute}. */
    public final float attribute;
    /** Samples along the way, evenly spaced, with the label's anchor at {@link #anchor}. */
    public final double[] lat;
    public final double[] lon;
    public final int anchor;
    /** Length of the whole way, in metres: longer ways win a crowded spot. */
    public final double wayLengthMeters;
    /** A trail's number on its own, written between its names (see RoadLabelPlanner). */
    public final boolean numberOnly;
    /**
     * For a label carrying both a trail's number and its name, the number alone: written
     * instead where the whole label is too long for the stretch of trail the camera sees, so
     * the number still shows. Null otherwise.
     */
    public final String shortText;

    /**
     * World coordinates of the samples, three floats each, filled in on the render thread once
     * the terrain under them has loaded; valid for the target latitude in
     * {@link #worldTargetLatitude}, which the world's x axis is scaled by.
     */
    public float[] world;
    public float worldTargetLatitude = Float.NaN;

    public RoadLabelCandidate(String text, RoadClass roadClass, float attribute,
                              double[] lat, double[] lon, int anchor, double wayLengthMeters) {
        this(text, roadClass, attribute, lat, lon, anchor, wayLengthMeters, false, null);
    }

    public RoadLabelCandidate(String text, RoadClass roadClass, float attribute,
                              double[] lat, double[] lon, int anchor, double wayLengthMeters,
                              boolean numberOnly, String shortText) {
        if (lat.length != lon.length || anchor < 0 || anchor >= lat.length) {
            throw new IllegalArgumentException("bad samples");
        }
        this.text = text;
        this.roadClass = roadClass;
        this.attribute = attribute;
        this.lat = lat;
        this.lon = lon;
        this.anchor = anchor;
        this.wayLengthMeters = wayLengthMeters;
        this.numberOnly = numberOnly;
        this.shortText = shortText;
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

    /**
     * Precedence in a crowded spot: roads over trails over tracks, a major road over a minor
     * one, and within that the longer way. A trail's bare number outranks its name: it is what
     * the waymarks carry, and being short it rarely displaces anything.
     */
    public float priority() {
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
        if (numberOnly) {
            return base + 0.95f;
        }
        return base + (float) Math.min(0.9, wayLengthMeters / 20000.0);
    }
}
