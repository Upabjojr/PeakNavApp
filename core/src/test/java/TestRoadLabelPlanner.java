import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadFeature;
import com.peaknav.roads.RoadGeo;
import com.peaknav.roads.RoadLabelCandidate;
import com.peaknav.roads.RoadLabelPlanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where along a named way its name may be written, and which tile keeps each spot. */
class TestRoadLabelPlanner {

    private static final double LAT = 46.0;
    private static final double DEG_PER_METER = 1.0 / (RoadGeo.METERS_PER_DEGREE * Math.cos(Math.toRadians(LAT)));

    /** A named way along the parallel at 46 N, from lon 7.0, this many metres long. */
    private static RoadFeature way(String name, RoadClass cls, double meters) {
        return new RoadFeature(cls, 0f, new double[]{LAT, LAT},
                new double[]{7.0, 7.0 + meters * DEG_PER_METER}, false, name);
    }

    private static List<RoadLabelCandidate> plan(RoadFeature f, double west, double east) {
        return RoadLabelPlanner.plan(Collections.singletonList(f), LAT + 0.01, LAT - 0.01, east, west);
    }

    @Test
    @DisplayName("a long way gets a label every 1.2 km, centred on its length")
    void spacing() {
        List<RoadLabelCandidate> c = plan(way("Sentiero", RoadClass.PATH, 3000), 6.9, 7.2);
        assertEquals(2, c.size());
        double first = RoadGeo.metersBetween(LAT, 7.0, c.get(0).anchorLatitude(), c.get(0).anchorLongitude());
        double second = RoadGeo.metersBetween(LAT, 7.0, c.get(1).anchorLatitude(), c.get(1).anchorLongitude());
        assertEquals(900, first, 1.0);
        assertEquals(2100, second, 1.0);
        assertEquals("Sentiero", c.get(0).text);
    }

    @Test
    @DisplayName("each label spot carries the stretch of way around it, evenly sampled")
    void window() {
        RoadLabelCandidate c = plan(way("Via", RoadClass.ROAD, 3000), 6.9, 7.2).get(0);
        int half = (int) Math.round(RoadLabelPlanner.HALF_WINDOW_METERS / RoadLabelPlanner.SAMPLE_METERS);
        assertEquals(2 * half + 1, c.size());
        assertEquals(half, c.anchor);
        for (int i = 1; i < c.size(); i++) {
            assertEquals(RoadLabelPlanner.SAMPLE_METERS,
                    RoadGeo.metersBetween(c.lat[i - 1], c.lon[i - 1], c.lat[i], c.lon[i]), 0.05);
        }
    }

    @Test
    @DisplayName("near the end of a way the stretch is cut short, and the anchor still found")
    void shortWay() {
        RoadLabelCandidate c = plan(way("Vicolo", RoadClass.ROAD, 100), 6.9, 7.2).get(0);
        // Anchor at 50 m; samples every 15 m from 5 m to 95 m.
        assertEquals(7, c.size());
        assertEquals(3, c.anchor);
        assertEquals(50, RoadGeo.metersBetween(LAT, 7.0, c.anchorLatitude(), c.anchorLongitude()), 0.5);
    }

    @Test
    @DisplayName("a tile keeps only the spots inside it, so a way is not labelled once per tile")
    void tilesSplitTheSpots() {
        RoadFeature f = way("Sentiero", RoadClass.PATH, 3000);
        double mid = 7.0 + 1500 * DEG_PER_METER;
        List<RoadLabelCandidate> westHalf = plan(f, 6.9, mid);
        List<RoadLabelCandidate> eastHalf = plan(f, mid, 7.2);
        assertEquals(1, westHalf.size());
        assertEquals(1, eastHalf.size());
        // A spot exactly on a shared edge belongs to one side only.
        RoadFeature straddle = new RoadFeature(RoadClass.ROAD, 0f, new double[]{LAT, LAT},
                new double[]{7.005, 7.015}, false, "Edge");
        int total = plan(straddle, 6.9, 7.01).size() + plan(straddle, 7.01, 7.2).size();
        assertEquals(1, total);
    }

    @Test
    @DisplayName("unnamed ways, pistes, areas and ways shorter than their name are not labelled")
    void skipped() {
        List<RoadFeature> fs = Arrays.asList(
                way(null, RoadClass.ROAD, 500),
                way("  ", RoadClass.ROAD, 500),
                way("Pista", RoadClass.PISTE, 500),
                way("Tiny", RoadClass.ROAD, 20),
                new RoadFeature(RoadClass.ROAD, 0f, new double[]{LAT, LAT + 0.001, LAT},
                        new double[]{7.0, 7.001, 7.0}, true, "Piazza"));
        assertTrue(RoadLabelPlanner.plan(fs, LAT + 0.01, LAT - 0.01, 7.2, 6.9).isEmpty());
        assertEquals(1, plan(way("Vispa", RoadClass.WATER, 500), 6.9, 7.2).size(),
                "rivers are labelled when the data has them");
    }

    @Test
    @DisplayName("roads outrank trails, trails outrank tracks, and a longer way breaks a tie")
    void priority() {
        RoadLabelCandidate road = plan(way("R", RoadClass.ROAD, 500), 6.9, 7.2).get(0);
        RoadLabelCandidate trail = plan(way("P", RoadClass.PATH, 500), 6.9, 7.2).get(0);
        RoadLabelCandidate longTrail = plan(way("P", RoadClass.PATH, 5000), 6.9, 7.2).get(0);
        RoadLabelCandidate track = plan(way("T", RoadClass.TRACK, 500), 6.9, 7.2).get(0);
        assertTrue(road.priority() > trail.priority());
        assertTrue(trail.priority() > track.priority());
        assertTrue(longTrail.priority() > trail.priority());
        assertTrue(longTrail.priority() < road.priority(), "length never lifts a trail over a road");
    }
}
