package com.peaknav.roads;

/**
 * A place where a way's name may be written: a point on the way, and a short stretch of it on
 * either side - the stretch whose direction on screen the text is tilted to follow.
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

    /**
     * World coordinates of the samples, three floats each, filled in on the render thread once
     * the terrain under them has loaded; valid for the target latitude in
     * {@link #worldTargetLatitude}, which the world's x axis is scaled by.
     */
    public float[] world;
    public float worldTargetLatitude = Float.NaN;

    public RoadLabelCandidate(String text, RoadClass roadClass, float attribute,
                              double[] lat, double[] lon, int anchor, double wayLengthMeters) {
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

    /**
     * Precedence in a crowded spot: roads over trails over tracks, a major road over a minor
     * one, and within that the longer way.
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
        return base + (float) Math.min(0.9, wayLengthMeters / 20000.0);
    }
}
