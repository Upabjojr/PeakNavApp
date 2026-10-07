package com.peaknav.gpx;

import com.peaknav.routing.WayInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * One continuous path parsed from a GPX file: a track segment ({@code trkseg}) or a route
 * ({@code rte}). Points carry latitude/longitude and, when the file provides it, an elevation in
 * metres; {@link GpxTileRasterizer} paints them onto the map tiles either way.
 */
public class GpxTrack {

    public static final class Point {
        public final float lat;
        public final float lon;
        public final float eleMeters;
        public final boolean hasElevation;
        public final long timeMillis;   // epoch millis from the <time> tag, 0 if absent
        public final boolean hasTime;

        public Point(float lat, float lon, float eleMeters, boolean hasElevation,
                     long timeMillis, boolean hasTime) {
            this.lat = lat;
            this.lon = lon;
            this.eleMeters = eleMeters;
            this.hasElevation = hasElevation;
            this.timeMillis = timeMillis;
            this.hasTime = hasTime;
        }
    }

    /**
     * Where the track starts following a way, as a computed route records it (see RouteGpx): from
     * point {@code firstPoint} up to the next stretch's first point, or the end.
     */
    public static final class Stretch {
        public final int firstPoint;
        /** The way; null for a stretch along none, such as the leg joining an end to the paths. */
        public final WayInfo way;

        public Stretch(int firstPoint, WayInfo way) {
            this.firstPoint = firstPoint;
            this.way = way;
        }
    }

    private final String name;
    private final List<Point> points = new ArrayList<>();
    private final List<Stretch> stretches = new ArrayList<>();
    /** The heights were not recorded but computed from the terrain, as "route to here" writes them. */
    private boolean computedHeights;

    public GpxTrack(String name) {
        this.name = (name == null) ? "" : name;
    }

    public void add(float lat, float lon, float eleMeters, boolean hasElevation,
                    long timeMillis, boolean hasTime) {
        points.add(new Point(lat, lon, eleMeters, hasElevation, timeMillis, hasTime));
    }

    /** The next point added starts following {@code way} (null: none). */
    public void startStretch(WayInfo way) {
        stretches.add(new Stretch(points.size(), way));
    }

    /** The ways the track follows, in order; empty for a track that does not say. */
    public List<Stretch> getStretches() {
        return stretches;
    }

    public String getName() {
        return name;
    }

    public List<Point> getPoints() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public boolean hasComputedHeights() {
        return computedHeights;
    }

    public void markHeightsComputed() {
        computedHeights = true;
    }
}
