package com.peaknav.routing;

import com.peaknav.geo.LatLong;
import com.peaknav.pbf.Tag;
import com.peaknav.pbf.Way;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The quickest walk between two points over OpenStreetMap ways - roads, tracks, paths - for
 * "route to here".
 *
 * <p>The graph is made of the ways as the map data holds them: two ways meet where they share a
 * node, which in the decoded data means the same coordinates exactly. Ways that merely cross - a
 * bridge over a road, a tunnel under one - share no node and stay unconnected, as they should.
 * Both ends are snapped onto the nearest segment of the network and joined to it by a straight
 * leg, so the route starts at the walker and ends at the point chosen, not at the nearest junction.
 * The search is A* on walking time: every stretch is timed by {@link WalkingSpeed} from the slope
 * the terrain gives it, sampled every {@link WalkingSpeed#STRETCH_METRES} along it, so a longer
 * path round a hill can beat a shorter one over it. The heuristic is the straight line at the
 * fastest walking speed, which never overestimates. Without heights, the ground is taken as flat
 * and the quickest walk is the shortest.
 *
 * <p>Pure: no rendering and no files, so it is fully covered by unit tests.
 */
public final class WalkingRouter {

    /** Thrown when a search runs past its deadline; see {@link #route(List, double, double, double, double, double, Elevation, long)}. */
    public static final class TimedOut extends RuntimeException {
        TimedOut() {
            super("route search timed out");
        }
    }

    /** No deadline. */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    /** The terrain's height at a point, in metres; NaN where it is not known. */
    public interface Elevation {
        float metres(double lat, double lon);
    }

    /** A found route: the points in order, its length along them, and the time to walk it. */
    public static final class Route {
        public final double[] lat;
        public final double[] lon;
        public final double metres;
        public final double seconds;
        /**
         * The way each leg follows, leg {@code i} running from point {@code i} to {@code i + 1};
         * null for the straight legs joining the two ends to the network.
         */
        final WayInfo[] legWay;
        final double[] legMetres;
        final double[] legSeconds;

        Route(double[] lat, double[] lon, double metres, double seconds,
              WayInfo[] legWay, double[] legMetres, double[] legSeconds) {
            this.lat = lat;
            this.lon = lon;
            this.metres = metres;
            this.seconds = seconds;
            this.legWay = legWay;
            this.legMetres = legMetres;
            this.legSeconds = legSeconds;
        }

        public int size() {
            return lat.length;
        }

        /**
         * The route as the ways it follows, one stretch to each: consecutive legs along ways that
         * say the same (see {@link WayInfo#equals}) are one stretch.
         */
        public List<Stretch> stretches() {
            return stretches(null);
        }

        /**
         * As {@link #stretches()}, timed by another clock: {@code secondsAt[i]} is the time to walk
         * from the start to point {@code i}. The search times each leg from the heights at its two
         * ends, which is right for choosing between ways but, over the short legs of a winding
         * path on a coarse elevation grid, reads every wobble of the grid as a climb; the GPX pane
         * times a track over 50 m stretches instead, and the file must agree with it.
         */
        public List<Stretch> stretches(double[] secondsAt) {
            List<Stretch> out = new ArrayList<>();
            int from = 0;
            double metres = 0, seconds = 0;
            for (int i = 0; i < legWay.length; i++) {
                metres += legMetres[i];
                seconds += secondsAt != null ? secondsAt[i + 1] - secondsAt[i] : legSeconds[i];
                boolean last = i == legWay.length - 1;
                if (last || !same(legWay[i], legWay[i + 1])) {
                    out.add(new Stretch(from, i + 1, legWay[i], metres, seconds));
                    from = i + 1;
                    metres = 0;
                    seconds = 0;
                }
            }
            return withoutSlivers(out);
        }

        /**
         * Folds stretches of a metre or two - a junction's node shared by a third way, the walker
         * standing on the path already - into the stretch after them, or the last into the one
         * before: nobody walks them, and listed they are noise between the ways that matter.
         */
        private static List<Stretch> withoutSlivers(List<Stretch> stretches) {
            List<Stretch> out = new ArrayList<>(stretches.size());
            Stretch carried = null; // slivers waiting to join the next stretch
            for (Stretch s : stretches) {
                if (carried != null) {
                    s = new Stretch(carried.from, s.to, s.way, carried.metres + s.metres, carried.seconds + s.seconds);
                    carried = null;
                }
                if (s.metres < SLIVER_METRES) {
                    carried = s;
                } else {
                    out.add(s);
                }
            }
            if (carried != null) {
                if (out.isEmpty()) {
                    out.add(carried);
                } else {
                    Stretch last = out.remove(out.size() - 1);
                    out.add(new Stretch(last.from, carried.to, last.way, last.metres + carried.metres,
                            last.seconds + carried.seconds));
                }
            }
            return out;
        }

        private static boolean same(WayInfo a, WayInfo b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** Stretches shorter than this are folded into their neighbour; see Route.stretches. */
    static final double SLIVER_METRES = 2.0;

    /** A part of a route along one way: points {@code from} to {@code to}, both included. */
    public static final class Stretch {
        public final int from;
        public final int to;
        /** The way; null for a straight leg off the network, to or from an end. */
        public final WayInfo way;
        public final double metres;
        public final double seconds;

        Stretch(int from, int to, WayInfo way, double metres, double seconds) {
            this.from = from;
            this.to = to;
            this.way = way;
            this.metres = metres;
            this.seconds = seconds;
        }
    }

    private static final double EARTH_RADIUS = 6371008.8;

    private WalkingRouter() {
    }

    /**
     * Whether a person may walk along a way with these tags. Motorways and trunk roads are left
     * out, as are ways under construction and ways closed to the public, unless foot access is
     * granted explicitly.
     */
    static boolean walkable(List<Tag> tags) {
        String highway = value(tags, "highway");
        if (highway == null) {
            return false;
        }
        switch (highway.toLowerCase(Locale.ROOT)) {
            case "motorway": case "motorway_link": case "trunk": case "trunk_link":
            case "construction": case "proposed": case "abandoned": case "raceway":
            case "bus_guideway": case "escape": case "razed":
                return false;
            default:
                break;
        }
        String foot = lower(value(tags, "foot"));
        if ("no".equals(foot)) {
            return false;
        }
        boolean footAllowed = "yes".equals(foot) || "designated".equals(foot) || "permissive".equals(foot);
        String access = lower(value(tags, "access"));
        if (("no".equals(access) || "private".equals(access)) && !footAllowed) {
            return false;
        }
        return true;
    }

    /** The shortest walk, on ground taken as flat; see {@link #route(List, double, double, double, double, double, Elevation)}. */
    public static Route route(List<Way> ways, double fromLat, double fromLon, double toLat, double toLon,
                              double maxSnapMetres) {
        return route(ways, fromLat, fromLon, toLat, toLon, maxSnapMetres, null);
    }

    /**
     * The quickest walk, or null when either end is further than {@code maxSnapMetres} from any
     * walkable way, or the two are not connected.
     *
     * @param elevation the terrain's heights; null for flat ground
     */
    public static Route route(List<Way> ways, double fromLat, double fromLon, double toLat, double toLon,
                              double maxSnapMetres, Elevation elevation) {
        return route(ways, fromLat, fromLon, toLat, toLon, maxSnapMetres, elevation, NO_DEADLINE);
    }

    /**
     * As {@link #route(List, double, double, double, double, double, Elevation)}, giving up with
     * {@link TimedOut} once {@link System#nanoTime()} passes {@code deadlineNanos}: over a large
     * area of dense paths the search can take long enough on a phone that the user would otherwise
     * be left waiting for a route that is not coming.
     */
    public static Route route(List<Way> ways, double fromLat, double fromLon, double toLat, double toLon,
                              double maxSnapMetres, Elevation elevation, long deadlineNanos) {
        Graph graph = new Graph(elevation);
        graph.deadlineNanos = deadlineNanos;
        int built = 0;
        for (Way way : ways) {
            if ((++built & 255) == 0) {
                checkDeadline(deadlineNanos);
            }
            if (way == null || way.latLongs == null || !walkable(way.tags)) {
                continue;
            }
            WayInfo info = WayInfo.of(way.tags);
            for (LatLong[] line : way.latLongs) {
                if (line == null) {
                    continue;
                }
                for (int i = 0; i + 1 < line.length; i++) {
                    if (line[i] == null || line[i + 1] == null) {
                        continue;
                    }
                    graph.addEdge(graph.node(line[i].latitude, line[i].longitude),
                            graph.node(line[i + 1].latitude, line[i + 1].longitude), info);
                }
            }
        }
        if (graph.edgeCount() == 0) {
            return null;
        }
        Snap start = graph.snap(fromLat, fromLon);
        Snap end = graph.snap(toLat, toLon);
        if (start == null || end == null || start.metres > maxSnapMetres || end.metres > maxSnapMetres) {
            return null;
        }
        int s = graph.attach(start);
        int t = graph.attach(end);
        if (start.a == end.a && start.b == end.b) {
            // Both ends on one segment: the walk along it between them.
            graph.link(s, t, start.way);
        }
        int[] previous = graph.shortest(s, t);
        if (previous == null) {
            return null;
        }
        List<Integer> nodes = new ArrayList<>();
        for (int n = t; n != -1; n = previous[n]) {
            nodes.add(n);
        }
        int count = nodes.size() + 2;
        double[] lat = new double[count];
        double[] lon = new double[count];
        // Leg i runs from point i to i + 1. The first and last join the ends to the network in a
        // straight line, along no way; those between follow the edges the search took.
        WayInfo[] legWays = new WayInfo[count - 1];
        lat[0] = fromLat;
        lon[0] = fromLon;
        for (int i = 0; i < nodes.size(); i++) {
            int node = nodes.get(nodes.size() - 1 - i);
            lat[i + 1] = graph.lat(node);
            lon[i + 1] = graph.lon(node);
            if (i > 0) {
                legWays[i] = graph.way(nodes.get(nodes.size() - i), node);
            }
        }
        lat[count - 1] = toLat;
        lon[count - 1] = toLon;
        // Joining legs of zero length (an end exactly on a node) leave duplicate points: drop them.
        return dedupe(lat, lon, legWays, elevation);
    }

    /** Distance on the sphere, in metres. */
    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /**
     * Seconds to walk straight from one point to another, timing each stretch of about
     * {@link WalkingSpeed#STRETCH_METRES} by its slope. Heights not known count as level ground.
     *
     * @param fromHeight the height at the start, or NaN to look it up; likewise {@code toHeight}
     */
    static double legSeconds(double fromLat, double fromLon, double fromHeight,
                             double toLat, double toLon, double toHeight, Elevation elevation) {
        double length = metres(fromLat, fromLon, toLat, toLon);
        if (elevation == null) {
            return WalkingSpeed.seconds(length, 0);
        }
        int stretches = Math.max(1, (int) Math.ceil(length / WalkingSpeed.STRETCH_METRES));
        double previous = Double.isNaN(fromHeight) ? elevation.metres(fromLat, fromLon) : fromHeight;
        double seconds = 0;
        for (int k = 1; k <= stretches; k++) {
            double t = (double) k / stretches;
            double height = k == stretches && !Double.isNaN(toHeight) ? toHeight
                    : elevation.metres(fromLat + t * (toLat - fromLat), fromLon + t * (toLon - fromLon));
            double rise = Double.isNaN(height) || Double.isNaN(previous) ? 0 : height - previous;
            seconds += WalkingSpeed.seconds(length / stretches, rise);
            if (!Double.isNaN(height)) {
                previous = height;
            }
        }
        return seconds;
    }

    /**
     * @param ways the way of each leg, {@code ways[i]} from point i to i + 1. A point dropped as a
     *             duplicate of the one before takes its zero-length leg with it; the leg that
     *             reaches the next distinct point is the one before that point, whose way it keeps.
     */
    private static Route dedupe(double[] lat, double[] lon, WayInfo[] ways, Elevation elevation) {
        double[] outLat = new double[lat.length];
        double[] outLon = new double[lon.length];
        WayInfo[] outWays = new WayInfo[lat.length];
        double[] outMetres = new double[lat.length];
        double[] outSeconds = new double[lat.length];
        int n = 0;
        double length = 0, seconds = 0;
        for (int i = 0; i < lat.length; i++) {
            if (n > 0 && outLat[n - 1] == lat[i] && outLon[n - 1] == lon[i]) {
                continue;
            }
            if (n > 0) {
                double legMetres = metres(outLat[n - 1], outLon[n - 1], lat[i], lon[i]);
                double legSecs = legSeconds(outLat[n - 1], outLon[n - 1], Double.NaN, lat[i], lon[i], Double.NaN, elevation);
                length += legMetres;
                seconds += legSecs;
                outWays[n - 1] = ways[i - 1];
                outMetres[n - 1] = legMetres;
                outSeconds[n - 1] = legSecs;
            }
            outLat[n] = lat[i];
            outLon[n] = lon[i];
            n++;
        }
        int legs = Math.max(0, n - 1);
        return new Route(java.util.Arrays.copyOf(outLat, n), java.util.Arrays.copyOf(outLon, n), length, seconds,
                java.util.Arrays.copyOf(outWays, legs), java.util.Arrays.copyOf(outMetres, legs),
                java.util.Arrays.copyOf(outSeconds, legs));
    }

    /** Where a point meets the network: on segment a-b, {@code along} metres from a. */
    private static final class Snap {
        final int a;
        final int b;
        /** The way the segment belongs to. */
        final WayInfo way;
        final double along;
        final double lat;
        final double lon;
        final double metres;

        Snap(int a, int b, WayInfo way, double along, double lat, double lon, double metres) {
            this.a = a;
            this.b = b;
            this.way = way;
            this.along = along;
            this.lat = lat;
            this.lon = lon;
            this.metres = metres;
        }
    }

    static void checkDeadline(long deadlineNanos) {
        if (deadlineNanos != NO_DEADLINE && System.nanoTime() - deadlineNanos > 0) {
            throw new TimedOut();
        }
    }

    private static final class Graph {
        long deadlineNanos = NO_DEADLINE;
        private final Map<Long, Integer> index = new HashMap<>();
        private final List<double[]> coordinates = new ArrayList<>();
        private final List<int[]> segments = new ArrayList<>();
        private final List<WayInfo> segmentWays = new ArrayList<>();
        private final List<List<Integer>> neighbours = new ArrayList<>();
        /** The way of each link, in step with {@link #neighbours}. */
        private final List<List<WayInfo>> neighbourWays = new ArrayList<>();
        /** Each node's height, looked up the first time a walk touches it (NaN: not known). */
        private final List<Double> heights = new ArrayList<>();
        private final Elevation elevation;

        Graph(Elevation elevation) {
            this.elevation = elevation;
        }

        int node(double lat, double lon) {
            long key = Double.doubleToLongBits(lat) * 31 + Double.doubleToLongBits(lon);
            Integer found = index.get(key);
            if (found != null) {
                double[] c = coordinates.get(found);
                if (c[0] == lat && c[1] == lon) {
                    return found;
                }
            }
            int id = coordinates.size();
            coordinates.add(new double[]{lat, lon});
            neighbours.add(new ArrayList<Integer>());
            neighbourWays.add(new ArrayList<WayInfo>());
            heights.add(null);
            if (found == null) {
                index.put(key, id);
            }
            return id;
        }

        double lat(int node) {
            return coordinates.get(node)[0];
        }

        double lon(int node) {
            return coordinates.get(node)[1];
        }

        double height(int node) {
            Double h = heights.get(node);
            if (h == null) {
                h = elevation == null ? Double.NaN : (double) elevation.metres(lat(node), lon(node));
                heights.set(node, h);
            }
            return h;
        }

        /** Seconds to walk from one node to a neighbour: uphill and downhill differ. */
        double seconds(int from, int to) {
            return legSeconds(lat(from), lon(from), height(from), lat(to), lon(to), height(to), elevation);
        }

        void addEdge(int a, int b, WayInfo way) {
            if (a == b) {
                return;
            }
            segments.add(new int[]{a, b});
            segmentWays.add(way);
            link(a, b, way);
        }

        void link(int a, int b, WayInfo way) {
            neighbours.get(a).add(b);
            neighbourWays.get(a).add(way);
            neighbours.get(b).add(a);
            neighbourWays.get(b).add(way);
        }

        /**
         * The way the link from a to b follows. Where two ways join the same pair of nodes, the
         * first: the search took the link, not a way, and both walk the same ground.
         */
        WayInfo way(int a, int b) {
            List<Integer> next = neighbours.get(a);
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i) == b) {
                    return neighbourWays.get(a).get(i);
                }
            }
            return null;
        }

        int edgeCount() {
            return segments.size();
        }

        /** The nearest point of the network to (lat, lon), projected in a local flat frame. */
        Snap snap(double lat, double lon) {
            double cos = Math.cos(Math.toRadians(lat));
            Snap best = null;
            double bestSquared = Double.MAX_VALUE;
            for (int k = 0; k < segments.size(); k++) {
                int[] segment = segments.get(k);
                double ax = (lon(segment[0]) - lon) * cos, ay = lat(segment[0]) - lat;
                double bx = (lon(segment[1]) - lon) * cos, by = lat(segment[1]) - lat;
                double dx = bx - ax, dy = by - ay;
                double lengthSquared = dx * dx + dy * dy;
                double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
                double px = ax + t * dx, py = ay + t * dy;
                double squared = px * px + py * py;
                if (squared < bestSquared) {
                    bestSquared = squared;
                    double snapLat = lat + py;
                    double snapLon = lon + px / cos;
                    double segmentMetres = metres(lat(segment[0]), lon(segment[0]), lat(segment[1]), lon(segment[1]));
                    best = new Snap(segment[0], segment[1], segmentWays.get(k), t * segmentMetres, snapLat, snapLon,
                            metres(lat, lon, snapLat, snapLon));
                }
            }
            return best;
        }

        /** A new node where the snap meets its segment, linked to both of the segment's ends. */
        int attach(Snap snap) {
            int id = coordinates.size();
            coordinates.add(new double[]{snap.lat, snap.lon});
            neighbours.add(new ArrayList<Integer>());
            neighbourWays.add(new ArrayList<WayInfo>());
            heights.add(null);
            link(id, snap.a, snap.way);
            link(id, snap.b, snap.way);
            return id;
        }

        /** A* on time from s to t; the predecessor of every settled node, or null when t is unreachable. */
        int[] shortest(int s, int t) {
            int n = coordinates.size();
            double[] cost = new double[n];
            int[] previous = new int[n];
            boolean[] done = new boolean[n];
            java.util.Arrays.fill(cost, Double.MAX_VALUE);
            java.util.Arrays.fill(previous, -1);
            final double targetLat = lat(t), targetLon = lon(t);
            PriorityQueue<double[]> open = new PriorityQueue<>(64, (x, y) -> Double.compare(x[0], y[0]));
            cost[s] = 0;
            final double fastest = WalkingSpeed.maxKmh() / 3.6; // metres a second
            open.add(new double[]{metres(lat(s), lon(s), targetLat, targetLon) / fastest, s});
            int polled = 0;
            while (!open.isEmpty()) {
                if ((++polled & 1023) == 0) {
                    checkDeadline(deadlineNanos);
                }
                int current = (int) open.poll()[1];
                if (done[current]) {
                    continue;
                }
                if (current == t) {
                    return previous;
                }
                done[current] = true;
                List<Integer> next = neighbours.get(current);
                for (int i = 0; i < next.size(); i++) {
                    int to = next.get(i);
                    if (done[to]) {
                        continue;
                    }
                    double candidate = cost[current] + seconds(current, to);
                    if (candidate < cost[to]) {
                        cost[to] = candidate;
                        previous[to] = current;
                        open.add(new double[]{candidate + metres(lat(to), lon(to), targetLat, targetLon) / fastest, to});
                    }
                }
            }
            return null;
        }
    }

    private static String value(List<Tag> tags, String key) {
        if (tags == null) {
            return null;
        }
        for (Tag tag : tags) {
            if (key.equals(tag.key)) {
                return tag.value;
            }
        }
        return null;
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
