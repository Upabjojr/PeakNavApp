package com.peaknav.pbf;

import java.util.ArrayList;
import java.util.List;

/** What reading the map data for an area gives: its ways and its points of interest. */
public final class MapReadResult {

    public final List<Way> ways = new ArrayList<>();
    public final List<PointOfInterest> pointOfInterests = new ArrayList<>();

    /** Appends everything {@code other} holds, duplicates included. */
    public void add(MapReadResult other) {
        ways.addAll(other.ways);
        pointOfInterests.addAll(other.pointOfInterests);
    }
}
