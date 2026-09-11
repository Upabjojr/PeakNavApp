package com.peaknav.pbf;

import com.peaknav.geo.LatLong;

import java.util.List;

/**
 * A way from the map data: its tags, and its nodes as one or more lines. Route relations it
 * belongs to add their tags after its own (see {@code RoadClassifier}).
 */
public final class Way {

    /** The zoom level of the data tile it was read from. */
    public final byte layer;
    public final List<Tag> tags;
    /** Its lines, each a run of nodes; a node the extract did not carry is null. */
    public final LatLong[][] latLongs;

    public Way(byte layer, List<Tag> tags, LatLong[][] latLongs) {
        this.layer = layer;
        this.tags = tags;
        this.latLongs = latLongs;
    }
}
