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
 * The shortest walk between two points over OpenStreetMap ways - roads, tracks, paths - for
 * "route to here".
 *
 * <p>The graph is made of the ways as the map data holds them: two ways meet where they share a
 * node, which in the decoded data means the same coordinates exactly. Ways that merely cross - a
 * bridge over a road, a tunnel under one - share no node and stay unconnected, as they should.
 * Both ends are snapped onto the nearest segment of the network and joined to it by a straight
 * leg, so the route starts at the walker and ends at the point chosen, not at the nearest junction.
 * The search is A* on distance, with the straight-line distance as its heuristic.
 *
 * <p>Pure: no rendering and no files, so it is fully covered by unit tests.
 */
public final class WalkingRouter {

    /** A found route: the points in order, and its length along them. */
    public static final class Route {
        public final double[] lat;
        public final double[] lon;
        public final double metres;

        Route(double[] lat, double[] lon, double metres) {
            this.lat = lat;
            this.lon = lon;
            this.metres = metres;
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

    /**
     * The shortest walk, or null when either end is further than {@code maxSnapMetres} from any
     * walkable way, or the two are not connected.
     */
    public static Route route(List<Way> ways, double fromLat, double fromLon, double toLat, double toLon,
                              double maxSnapMetres) {
        Graph graph = new Graph();
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
            graph.link(s, t, Math.abs(start.along - end.along));
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
        return dedupe(lat, lon);
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

    private static Route dedupe(double[] lat, double[] lon) {
        double[] outLat = new double[lat.length];
        double[] outLon = new double[lon.length];
        int n = 0;
        double length = 0;
        for (int i = 0; i < lat.length; i++) {
            if (n > 0 && outLat[n - 1] == lat[i] && outLon[n - 1] == lon[i]) {
                continue;
            }
            if (n > 0) {
                length += metres(outLat[n - 1], outLon[n - 1], lat[i], lon[i]);
            }
            outLat[n] = lat[i];
            outLon[n] = lon[i];
            n++;
        }
        double[] finalLat = new double[n];
        double[] finalLon = new double[n];
        System.arraycopy(outLat, 0, finalLat, 0, n);
        System.arraycopy(outLon, 0, finalLon, 0, n);
        return new Route(finalLat, finalLon, length);
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
        private final List<List<Double>> lengths = new ArrayList<>();

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
            lengths.add(new ArrayList<Double>());
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

        void addEdge(int a, int b) {
            if (a == b) {
                return;
            }
            segments.add(new int[]{a, b});
            link(a, b, metres(lat(a), lon(a), lat(b), lon(b)));
        }

        void link(int a, int b, double length) {
            neighbours.get(a).add(b);
            lengths.get(a).add(length);
            neighbours.get(b).add(a);
            lengths.get(b).add(length);
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
            lengths.add(new ArrayList<Double>());
            double segmentMetres = metres(lat(snap.a), lon(snap.a), lat(snap.b), lon(snap.b));
            link(id, snap.a, snap.along);
            link(id, snap.b, Math.max(0, segmentMetres - snap.along));
            return id;
        }

        /** A* from s to t; the predecessor of every settled node, or null when t is unreachable. */
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
            open.add(new double[]{metres(lat(s), lon(s), targetLat, targetLon), s});
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
                List<Double> length = lengths.get(current);
                for (int i = 0; i < next.size(); i++) {
                    int to = next.get(i);
                    double candidate = cost[current] + length.get(i);
                    if (!done[to] && candidate < cost[to]) {
                        cost[to] = candidate;
                        previous[to] = current;
                        open.add(new double[]{candidate + metres(lat(to), lon(to), targetLat, targetLon), to});
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
