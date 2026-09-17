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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
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
    /**
     * How long a search may take before the user is told it gave up. A 40 km walk over dense paths
     * can keep a phone busy for a long while, and nothing on screen says whether a route is still
     * coming - the GPX view just never appears.
     */
    static final long TIMEOUT_SECONDS = 45;
    /** Beyond the deadline, how long the watchdog allows a step that cannot check it (a tile read). */
    private static final long WATCHDOG_GRACE_SECONDS = 10;

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
    /** Which request is the live one; a result from an abandoned request is dropped. */
    private static final java.util.concurrent.atomic.AtomicInteger REQUEST = new java.util.concurrent.atomic.AtomicInteger();

    private RouteToPoint() {
    }

    /** Computes the route off the render thread and opens it, with a word to the user either way. */
    public static void start(final double fromLat, final double fromLon, final double toLat, final double toLon) {
        if (!RUNNING.compareAndSet(false, true)) {
            // one at a time: a second tap waits for the first route, and is told so
            toast(s("Route_computing"));
            return;
        }
        final int request = REQUEST.incrementAndGet();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        toast(s("Route_computing"));
        // The deadline is checked between tile reads and inside the search; the watchdog covers a
        // step that hangs where it cannot be checked, so the user always hears how it ended.
        Timer watchdog = new Timer("route-watchdog", true);
        watchdog.schedule(new TimerTask() {
            @Override
            public void run() {
                if (finish(request)) {
                    toast(s("Route_timeout"));
                }
            }
        }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS + WATCHDOG_GRACE_SECONDS));
        getC().submitExecutorGeneric(() -> {
            try {
                final Result result = compute(getC().mapDataManager.getMultiMapDataStore(),
                        fromLat, fromLon, toLat, toLon, deadline);
                if (result.route == null) {
                    if (finish(request)) {
                        toast(s(result.problem).replace("{km}", String.valueOf((int) MAX_STRAIGHT_KM)));
                    }
                    return;
                }
                final String gpx = gpxFor(result.route, toLat, toLon);
                final String found = s("Route_found")
                        .replace("{km}", String.format(Locale.ROOT, "%.1f", result.route.metres / 1000));
                if (!finish(request)) {
                    return; // the watchdog already told the user it gave up
                }
                Gdx.app.postRunnable(() -> {
                    // Opened as a GPX file is: the map frames the track, and the tour can fly it.
                    getC().gpxManager.loadComputedXml(gpx, String.format(Locale.ROOT,
                            "PeakNav_route_%.5f_%.5f", toLat, toLon), true);
                    toast(found);
                });
            } catch (WalkingRouter.TimedOut e) {
                if (finish(request)) {
                    toast(s("Route_timeout"));
                }
            } catch (Throwable e) {
                // Not only exceptions: an OutOfMemoryError over a huge area must still end the wait.
                if (finish(request)) {
                    toast(s("Route_not_found"));
                }
            } finally {
                watchdog.cancel();
            }
        });
    }

    /**
     * Ends a request once, whoever gets there first - the search or the watchdog - and frees the
     * button for the next tap. True for the one that ended it; false when it was already over.
     */
    private static boolean finish(int request) {
        if (REQUEST.get() != request || !RUNNING.get()) {
            return false;
        }
        synchronized (RUNNING) {
            if (REQUEST.get() != request || !RUNNING.get()) {
                return false;
            }
            REQUEST.incrementAndGet(); // a late result of this request no longer matches
            RUNNING.set(false);
            return true;
        }
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
        return compute(store, fromLat, fromLon, toLat, toLon, WalkingRouter.NO_DEADLINE);
    }

    /**
     * As {@link #compute(PbfMapDataStore, double, double, double, double)}, throwing
     * {@link WalkingRouter.TimedOut} once {@link System#nanoTime()} passes {@code deadlineNanos}.
     */
    public static Result compute(PbfMapDataStore store, double fromLat, double fromLon, double toLat, double toLon,
                                 long deadlineNanos) {
        double straightKm = WalkingRouter.metres(fromLat, fromLon, toLat, toLon) / 1000;
        if (straightKm > MAX_STRAIGHT_KM) {
            return new Result(null, "Route_too_far");
        }
        List<Way> ways = waysAround(store, fromLat, fromLon, toLat, toLon, straightKm, deadlineNanos);
        if (ways.isEmpty()) {
            return new Result(null, "Route_no_data");
        }
        WalkingRouter.Route route = WalkingRouter.route(ways, fromLat, fromLon, toLat, toLon, MAX_SNAP_METRES,
                // ElevationUtils' own lookup never finds a tile; the loaded terrain does. NaN where none is.
                (lat, lon) -> com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon),
                deadlineNanos);
        return route == null ? new Result(null, "Route_not_found") : new Result(route, null);
    }

    /** Every way of the map data crossing the box around both points, each once. */
    static List<Way> waysAround(PbfMapDataStore store, double fromLat, double fromLon, double toLat, double toLon,
                                double straightKm, long deadlineNanos) {
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
                WalkingRouter.checkDeadline(deadlineNanos);
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
