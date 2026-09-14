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

        Route(double[] lat, double[] lon, double metres, double seconds) {
            this.lat = lat;
            this.lon = lon;
            this.metres = metres;
            this.seconds = seconds;
        }

        public int size() {
            return lat.length;
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
        Graph graph = new Graph(elevation);
        for (Way way : ways) {
            if (way == null || way.latLongs == null || !walkable(way.tags)) {
                continue;
            }
            for (LatLong[] line : way.latLongs) {
                if (line == null) {
                    continue;
                }
                for (int i = 0; i + 1 < line.length; i++) {
                    if (line[i] == null || line[i + 1] == null) {
                        continue;
                    }
                    graph.addEdge(graph.node(line[i].latitude, line[i].longitude),
                            graph.node(line[i + 1].latitude, line[i + 1].longitude));
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
            graph.link(s, t);
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
        lat[0] = fromLat;
        lon[0] = fromLon;
        for (int i = 0; i < nodes.size(); i++) {
            int node = nodes.get(nodes.size() - 1 - i);
            lat[i + 1] = graph.lat(node);
            lon[i + 1] = graph.lon(node);
        }
        lat[count - 1] = toLat;
        lon[count - 1] = toLon;
        // Joining legs of zero length (an end exactly on a node) leave duplicate points: drop them.
        return dedupe(lat, lon, elevation);
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

    private static Route dedupe(double[] lat, double[] lon, Elevation elevation) {
        double[] outLat = new double[lat.length];
        double[] outLon = new double[lon.length];
        int n = 0;
        double length = 0, seconds = 0;
        for (int i = 0; i < lat.length; i++) {
            if (n > 0 && outLat[n - 1] == lat[i] && outLon[n - 1] == lon[i]) {
                continue;
            }
            if (n > 0) {
                length += metres(outLat[n - 1], outLon[n - 1], lat[i], lon[i]);
                seconds += legSeconds(outLat[n - 1], outLon[n - 1], Double.NaN, lat[i], lon[i], Double.NaN, elevation);
            }
            outLat[n] = lat[i];
            outLon[n] = lon[i];
            n++;
        }
        double[] finalLat = new double[n];
        double[] finalLon = new double[n];
        System.arraycopy(outLat, 0, finalLat, 0, n);
        System.arraycopy(outLon, 0, finalLon, 0, n);
        return new Route(finalLat, finalLon, length, seconds);
    }

    /** Where a point meets the network: on segment a-b, {@code along} metres from a. */
    private static final class Snap {
        final int a;
        final int b;
        final double along;
        final double lat;
        final double lon;
        final double metres;

        Snap(int a, int b, double along, double lat, double lon, double metres) {
            this.a = a;
            this.b = b;
            this.along = along;
            this.lat = lat;
            this.lon = lon;
            this.metres = metres;
        }
    }

    private static final class Graph {
        private final Map<Long, Integer> index = new HashMap<>();
        private final List<double[]> coordinates = new ArrayList<>();
        private final List<int[]> segments = new ArrayList<>();
        private final List<List<Integer>> neighbours = new ArrayList<>();
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

        void addEdge(int a, int b) {
            if (a == b) {
                return;
            }
            segments.add(new int[]{a, b});
            link(a, b);
        }

        void link(int a, int b) {
            neighbours.get(a).add(b);
            neighbours.get(b).add(a);
        }

        int edgeCount() {
            return segments.size();
        }

        /** The nearest point of the network to (lat, lon), projected in a local flat frame. */
        Snap snap(double lat, double lon) {
            double cos = Math.cos(Math.toRadians(lat));
            Snap best = null;
            double bestSquared = Double.MAX_VALUE;
            for (int[] segment : segments) {
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
                    best = new Snap(segment[0], segment[1], t * segmentMetres, snapLat, snapLon,
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
            heights.add(null);
            link(id, snap.a);
            link(id, snap.b);
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
            while (!open.isEmpty()) {
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
