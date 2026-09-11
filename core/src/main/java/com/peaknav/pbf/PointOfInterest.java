package com.peaknav.pbf;

import com.peaknav.geo.LatLong;

import java.util.List;

/** A tagged node from the map data - a peak, a hut, a place - and where it is. */
public final class PointOfInterest {

    /** The zoom level of the data tile it was read from. */
    public final byte layer;
    public final List<Tag> tags;
    public final LatLong position;

    public PointOfInterest(byte layer, List<Tag> tags, LatLong position) {
        this.layer = layer;
        this.tags = tags;
        this.position = position;
    }
}
