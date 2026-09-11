package com.peaknav.roads;

/**
 * What a way on the map is drawn as. Each class that is drawn has its own channel in the tile's
 * distance texture (see {@link RoadTileRasterizer}), so the shader can give it its own colour,
 * width and - for trails - dashes.
 */
public enum RoadClass {
    /** Motorways down to service roads; the rank is baked into the line's width. */
    ROAD,
    /** Tracks: unpaved roads for farm and forestry vehicles, the backbone of most valleys. */
    TRACK,
    /** Footpaths, hiking trails, steps and via ferratas, coloured by difficulty and dashed. */
    PATH,
    /** Ski pistes, coloured by difficulty the way every ski map is. */
    PISTE,
    /**
     * Rivers, streams and canals. Classified so their names can be labelled, but not drawn:
     * the map-data extracts keep highways, and waterways are all but absent from them.
     */
    WATER;

    /** Whether this class has a channel in the distance texture. */
    public boolean isDrawn() {
        return this != WATER;
    }
}
