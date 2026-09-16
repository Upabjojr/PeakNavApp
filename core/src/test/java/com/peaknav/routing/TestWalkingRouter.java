package com.peaknav.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.geo.LatLong;
import com.peaknav.gpx.GpxParser;
import com.peaknav.gpx.GpxTrack;
import com.peaknav.pbf.Tag;
import com.peaknav.pbf.Way;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestWalkingRouter {

    private static Way way(String[] tags, double... latLon) {
        List<Tag> list = new ArrayList<>();
        for (int i = 0; i + 1 < tags.length; i += 2) {
            list.add(new Tag(tags[i], tags[i + 1]));
        }
        LatLong[] points = new LatLong[latLon.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new LatLong(latLon[2 * i], latLon[2 * i + 1]);
        }
        return new Way((byte) 0, list, new LatLong[][]{points});
    }

    private static String[] t(String... kv) {
        return kv;
    }

    // A square of four junctions about 780 m (east-west) by 1110 m (north-south) around Trento.
    private static final double S = 46.00, N = 46.01, W = 11.00, E = 11.01;

    @Test
    void takesTheShorterOfTwoWaysBetweenSharedJunctions() {
        List<Way> ways = Arrays.asList(
                way(t("highway", "residential"), S, W, S, E),                  // direct, south side
                way(t("highway", "path"), S, W, N, W),                         // round the north...
                way(t("highway", "path"), N, W, N, E),
                way(t("highway", "path"), N, E, S, E));
        WalkingRouter.Route route = WalkingRouter.route(ways, S, W, S, E, 50);
        assertNotNull(route);
        assertEquals(2, route.size(), "straight along the south side");
        assertEquals(WalkingRouter.metres(S, W, S, E), route.metres, 0.5);
    }

    @Test
    void aLongerWayRoundAHillBeatsAShorterOneOverIt() {
        List<Way> ways = Arrays.asList(
                way(t("highway", "path"), S, W, S, E),                         // short, over a 400 m hump
                way(t("highway", "path"), S, W, N, W),                         // long, on the level
                way(t("highway", "path"), N, W, N, E),
                way(t("highway", "path"), N, E, S, E));
        WalkingRouter.Elevation hump = (lat, lon) -> lat < S + 0.002
                ? (float) (1000 + 400 * Math.sin(Math.PI * (lon - W) / (E - W))) : 1000f;

        WalkingRouter.Route flat = WalkingRouter.route(ways, S, W, S, E, 50);
        assertEquals(2, flat.size(), "on flat ground, the short way");
        assertEquals(flat.metres / (WalkingSpeed.kmh(0) / 3.6), flat.seconds, 1.0, "walked at 5 km/h");

        WalkingRouter.Route hilly = WalkingRouter.route(ways, S, W, S, E, 50, hump);
        assertNotNull(hilly);
        assertEquals(4, hilly.size(), "over the hill it is quicker to go round");
        assertTrue(hilly.metres > flat.metres * 3);
        assertEquals(hilly.metres / (WalkingSpeed.kmh(0) / 3.6), hilly.seconds, 1.0, "and round is all level");

        // Up a slope and back down it: the climb takes longer.
        double up = WalkingRouter.legSeconds(S, W, 1000, N, W, 1200, null);
        assertEquals(WalkingRouter.legSeconds(N, W, 1200, S, W, 1000, null), up, 1e-9, "without heights, direction does not matter");
        WalkingRouter.Elevation slope = (lat, lon) -> (float) (1000 + (lat - S) / (N - S) * 200);
        assertTrue(WalkingRouter.legSeconds(S, W, Double.NaN, N, W, Double.NaN, slope)
                > WalkingRouter.legSeconds(N, W, Double.NaN, S, W, Double.NaN, slope));
    }

    @Test
    void motorwaysAndPrivateWaysAreNotWalked() {
        List<Way> ways = Arrays.asList(
                way(t("highway", "motorway"), S, W, S, E),
                way(t("highway", "service", "access", "private"), S, W, S, E),
                way(t("highway", "track"), S, W, N, W),
                way(t("highway", "track"), N, W, N, E),
                way(t("highway", "track"), N, E, S, E));
        WalkingRouter.Route route = WalkingRouter.route(ways, S, W, S, E, 50);
        assertNotNull(route);
        assertEquals(4, route.size(), "round by the tracks");
        assertTrue(route.metres > WalkingRouter.metres(S, W, S, E) * 3);

        assertTrue(WalkingRouter.walkable(Arrays.asList(new Tag("highway", "service"),
                new Tag("access", "private"), new Tag("foot", "yes"))), "foot=yes opens a private way");
        assertFalse(WalkingRouter.walkable(Arrays.asList(new Tag("highway", "footway"), new Tag("foot", "no"))));
        assertFalse(WalkingRouter.walkable(Arrays.asList(new Tag("waterway", "stream"))));
    }

    @Test
    void waysThatCrossWithoutSharingANodeAreNotConnected() {
        double mid = (S + N) / 2, middle = (W + E) / 2;
        // A bridge: the east-west way passes over the north-south one without a common node.
        List<Way> ways = Arrays.asList(
                way(t("highway", "path"), mid, W, mid, E),
                way(t("highway", "path"), S, middle, N, middle));
        assertNull(WalkingRouter.route(ways, mid, W, S, middle, 50), "no junction, no route");
        // With the node shared, they meet.
        List<Way> junction = Arrays.asList(
                way(t("highway", "path"), mid, W, mid, middle, mid, E),
                way(t("highway", "path"), S, middle, mid, middle, N, middle));
        WalkingRouter.Route route = WalkingRouter.route(junction, mid, W, S, middle, 50);
        assertNotNull(route);
        assertEquals(3, route.size());
    }

    @Test
    void endsAreJoinedToTheNearestWayAndMustBeCloseToOne() {
        List<Way> ways = Arrays.asList(way(t("highway", "path"), S, W, S, E));
        // 30 m north of the path at each end, and in the middle of it.
        double off = 30 / 111_195.0;
        double midLon = (W + E) / 2;
        WalkingRouter.Route route = WalkingRouter.route(ways, S + off, W + 0.002, S + off, E - 0.002, 50);
        assertNotNull(route);
        assertEquals(4, route.size(), "walker, on the path, on the path, destination");
        assertEquals(S + off, route.lat[0], 1e-12, "starts where the walker is");
        assertEquals(S, route.lat[1], 1e-9, "then steps onto the path");
        assertEquals(S, route.lat[2], 1e-9);
        assertEquals(E - 0.002, route.lon[3], 1e-12, "and ends at the chosen point");

        assertNull(WalkingRouter.route(ways, S + 0.01, midLon, S, E, 50), "a kilometre from any way");
        assertNull(WalkingRouter.route(new ArrayList<Way>(), S, W, S, E, 50));
    }

    @Test
    void theRouteOpensAsAGpxTrack() {
        List<Way> ways = Arrays.asList(
                way(t("highway", "path"), S, W, S, E),
                way(t("highway", "path"), S, E, N, E));
        WalkingRouter.Route route = WalkingRouter.route(ways, S, W, N, E, 50);
        assertNotNull(route);
        String gpx = RouteGpx.toGpx("To <Monte Bondone> & back", route, (lat, lon) -> (float) (200 + lat * 10));
        List<GpxTrack> tracks = GpxParser.parse(gpx);
        assertEquals(1, tracks.size());
        GpxTrack track = tracks.get(0);
        assertEquals(route.size(), track.getPoints().size());
        assertTrue(track.getPoints().get(0).hasElevation);
        assertEquals((float) S, track.getPoints().get(0).lat, 1e-5f);
        String noElevation = RouteGpx.toGpx("x", route, (lat, lon) -> null);
        assertFalse(GpxParser.parse(noElevation).get(0).getPoints().get(0).hasElevation);
    }
}
