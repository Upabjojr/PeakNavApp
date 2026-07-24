package com.peaknav.gpx;

import java.util.ArrayList;
import java.util.List;

/**
 * One continuous path parsed from a GPX file: a track segment ({@code trkseg}) or a route
 * ({@code rte}). Points carry latitude/longitude and, when the file provides it, an elevation in
 * metres; {@link GpxPathRenderer} drapes them onto the terrain either way.
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

    private final String name;
    private final List<Point> points = new ArrayList<>();

    public GpxTrack(String name) {
        this.name = (name == null) ? "" : name;
    }

    public void add(float lat, float lon, float eleMeters, boolean hasElevation,
                    long timeMillis, boolean hasTime) {
        points.add(new Point(lat, lon, eleMeters, hasElevation, timeMillis, hasTime));
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
}
