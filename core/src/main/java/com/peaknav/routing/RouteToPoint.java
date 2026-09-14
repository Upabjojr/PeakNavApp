package com.peaknav.routing;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.geo.MercatorProjection;
import com.peaknav.geo.Tile;
import com.peaknav.pbf.PbfMapDataStore;
import com.peaknav.pbf.Way;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Route to here": from where the viewer stands to a point tapped on the map, the quickest walk
 * along the roads, tracks and paths of the downloaded map data - timed on the slopes of the loaded
 * terrain - opened as a GPX track.
 *
 * <p>The ways come from the same OpenStreetMap extracts the road layer draws ({@link
 * PbfMapDataStore}), read over the area between the two points with a margin - a path may leave
 * the straight line to get round a ridge - and handed to {@link WalkingRouter}. The result is
 * written as GPX with the terrain's heights and loaded the way a GPX file is: the map frames the
 * track, and the tour can be flown.
 */
public final class RouteToPoint {

    /** Beyond this the area's ways get too many to read on a phone, and it is not a walk. */
    static final double MAX_STRAIGHT_KM = 40;
    /** How far a point may be from the nearest way and still be joined to it. */
    static final double MAX_SNAP_METRES = 400;
    /** The margin read around the two points, as a share of the distance between them, and at least this. */
    private static final double MARGIN_SHARE = 0.35;
    private static final double MIN_MARGIN_KM = 1.5;
    private static final int READ_ZOOM = 12;
    private static final int MAX_TILES = 144;

    /** What a computation came to: a route, or why there is none. */
    public static final class Result {
        public final WalkingRouter.Route route;
        /** The string key of the reason there is no route; null when there is one. */
        public final String problem;

        Result(WalkingRouter.Route route, String problem) {
            this.route = route;
            this.problem = problem;
        }
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private RouteToPoint() {
    }

    /** Computes the route off the render thread and opens it, with a word to the user either way. */
    public static void start(final double fromLat, final double fromLon, final double toLat, final double toLon) {
        if (!RUNNING.compareAndSet(false, true)) {
            return; // one at a time: a second tap waits for the first route
        }
        toast(s("Route_computing"));
        getC().submitExecutorGeneric(() -> {
            try {
                final Result result = compute(getC().mapDataManager.getMultiMapDataStore(),
                        fromLat, fromLon, toLat, toLon);
                if (result.route == null) {
                    toast(s(result.problem).replace("{km}", String.valueOf((int) MAX_STRAIGHT_KM)));
                    return;
                }
                final String gpx = gpxFor(result.route, toLat, toLon);
                final String found = s("Route_found")
                        .replace("{km}", String.format(Locale.ROOT, "%.1f", result.route.metres / 1000));
                Gdx.app.postRunnable(() -> {
                    // Opened as a GPX file is: the map frames the track, and the tour can fly it.
                    getC().gpxManager.loadComputedXml(gpx, String.format(Locale.ROOT,
                            "PeakNav_route_%.5f_%.5f", toLat, toLon), true);
                    toast(found);
                });
            } catch (RuntimeException e) {
                toast(s("Route_not_found"));
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** The route as a GPX track named after its destination, with the terrain's heights. */
    public static String gpxFor(WalkingRouter.Route route, double toLat, double toLon) {
        return RouteGpx.toGpx(String.format(Locale.ROOT, s("Route_name"), toLat, toLon), route,
                (lat, lon) -> {
                    // ElevationUtils' own lookup never finds a tile; the loaded terrain does.
                    float metres = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
                    return Float.isNaN(metres) ? null : metres;
                });
    }

    /** The route between two points over the map data in {@code store}, or why there is none. */
    public static Result compute(PbfMapDataStore store, double fromLat, double fromLon, double toLat, double toLon) {
        double straightKm = WalkingRouter.metres(fromLat, fromLon, toLat, toLon) / 1000;
        if (straightKm > MAX_STRAIGHT_KM) {
            return new Result(null, "Route_too_far");
        }
        List<Way> ways = waysAround(store, fromLat, fromLon, toLat, toLon, straightKm);
        if (ways.isEmpty()) {
            return new Result(null, "Route_no_data");
        }
        WalkingRouter.Route route = WalkingRouter.route(ways, fromLat, fromLon, toLat, toLon, MAX_SNAP_METRES,
                // ElevationUtils' own lookup never finds a tile; the loaded terrain does. NaN where none is.
                (lat, lon) -> com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon));
        return route == null ? new Result(null, "Route_not_found") : new Result(route, null);
    }

    /** Every way of the map data crossing the box around both points, each once. */
    static List<Way> waysAround(PbfMapDataStore store, double fromLat, double fromLon, double toLat, double toLon,
                                double straightKm) {
        double marginKm = Math.max(MIN_MARGIN_KM, straightKm * MARGIN_SHARE);
        double midLat = (fromLat + toLat) / 2;
        double marginLat = marginKm / 111.2;
        double marginLon = marginKm / (111.2 * Math.cos(Math.toRadians(midLat)));
        double south = Math.min(fromLat, toLat) - marginLat, north = Math.max(fromLat, toLat) + marginLat;
        double west = Math.min(fromLon, toLon) - marginLon, east = Math.max(fromLon, toLon) + marginLon;

        int zoom = READ_ZOOM;
        int x0, x1, y0, y1;
        while (true) {
            x0 = MercatorProjection.longitudeToTileX(west, (byte) zoom);
            x1 = MercatorProjection.longitudeToTileX(east, (byte) zoom);
            y0 = MercatorProjection.latitudeToTileY(north, (byte) zoom);
            y1 = MercatorProjection.latitudeToTileY(south, (byte) zoom);
            if ((x1 - x0 + 1) * (y1 - y0 + 1) <= MAX_TILES || zoom <= 8) {
                break;
            }
            zoom--;
        }
        Set<Way> seen = Collections.newSetFromMap(new IdentityHashMap<Way, Boolean>());
        List<Way> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (Way way : store.readMapDataPadded(new Tile(x, y, (byte) zoom, 256), 0.0).ways) {
                    if (seen.add(way)) {
                        out.add(way);
                    }
                }
            }
        }
        return out;
    }

    private static void toast(final String text) {
        if (getNativeScreenCaller() != null) {
            getNativeScreenCaller().makeToast(text);
        }
    }
}
