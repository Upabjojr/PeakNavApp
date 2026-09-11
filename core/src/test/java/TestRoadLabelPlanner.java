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

    private static double along(RoadLabelCandidate c) {
        return RoadGeo.metersBetween(LAT, 7.0, c.anchorLatitude(), c.anchorLongitude());
    }

    /** A trail along the parallel at 46 N, from lon 7.0, with a name and a number. */
    private static RoadFeature trail(String name, String number, double meters) {
        return new RoadFeature(RoadClass.PATH, 0.5f, new double[]{LAT, LAT},
                new double[]{7.0, 7.0 + meters * DEG_PER_METER}, false, name, number);
    }

    /** The candidates written at this stride, in order along the way. */
    private static List<RoadLabelCandidate> shown(List<RoadLabelCandidate> all, int stride) {
        List<RoadLabelCandidate> out = new java.util.ArrayList<>();
        for (RoadLabelCandidate c : all) {
            if (c.label(stride) != null) {
                out.add(c);
            }
        }
        return out;
    }

    @Test
    @DisplayName("spots are laid out densely along a way, numbered from its middle")
    void spots() {
        List<RoadLabelCandidate> c = plan(way("Via Roma", RoadClass.ROAD, 3000), 6.9, 7.2);
        double spacing = RoadLabelPlanner.ROAD_SPACING_METERS;
        int count = (int) Math.floor(3000 / spacing);
        assertEquals(count, c.size());
        double first = (3000 - (count - 1) * spacing) / 2;
        for (int i = 0; i < count; i++) {
            assertEquals(first + spacing * i, along(c.get(i)), 1.0);
            assertEquals(i - (count - 1) / 2, c.get(i).spot);
        }
    }

    @Test
    @DisplayName("by default a road is named every 1.2 km, centred on its middle")
    void roadAtDefaultFrequency() {
        List<RoadLabelCandidate> c = shown(plan(way("Via Roma", RoadClass.ROAD, 3000), 6.9, 7.2), 2);
        assertEquals(3, c.size());
        assertEquals(300, along(c.get(0)), 1.0);
        assertEquals(1500, along(c.get(1)), 1.0, "the middle spot is always kept");
        assertEquals(2700, along(c.get(2)), 1.0);
        assertEquals("Via Roma", c.get(1).label(2));
    }

    @Test
    @DisplayName("each frequency step doubles or halves the spacing, and the middle always stays")
    void frequencySteps() {
        List<RoadLabelCandidate> all = plan(trail(null, "E5", 4800), 6.9, 7.2);
        for (int stride : new int[]{1, 2, 4, 8}) {
            List<RoadLabelCandidate> c = shown(all, stride);
            for (int i = 1; i < c.size(); i++) {
                assertEquals(RoadLabelPlanner.TRAIL_SPACING_METERS * stride,
                        along(c.get(i)) - along(c.get(i - 1)), 1.0, "stride " + stride);
            }
            boolean middle = false;
            for (RoadLabelCandidate x : c) {
                middle |= x.spot == 0;
            }
            assertTrue(middle, "stride " + stride + " keeps the middle spot");
        }
        assertTrue(shown(all, 1).size() > shown(all, 8).size());
    }

    @Test
    @DisplayName("a numbered trail, by default: number and name, then the number alone, every 300 m")
    void trailNumbersAlternateWithNames() {
        List<RoadLabelCandidate> c = shown(plan(trail("Sentiero", "12", 2000), 6.9, 7.2), 2);
        String both = "12" + RoadLabelPlanner.NUMBER_NAME_SEPARATOR + "Sentiero";
        assertTrue(c.size() >= 5);
        for (int i = 0; i < c.size(); i++) {
            RoadLabelCandidate x = c.get(i);
            if (i > 0) {
                assertEquals(2 * RoadLabelPlanner.TRAIL_SPACING_METERS, along(x) - along(c.get(i - 1)), 1.0);
            }
            boolean nameSpot = (x.spot / 2) % 2 == 0;
            assertEquals(nameSpot ? both : "12", x.label(2));
            assertEquals(!nameSpot, x.isNumberOnly(2));
            assertEquals(nameSpot ? "12" : null, x.shortText(2),
                    "a spot with both keeps the number to fall back on");
            assertTrue(x.isTrail());
            if (x.spot == 0) {
                assertEquals(both, x.label(2), "the middle of the trail carries both");
            }
        }
    }

    @Test
    @DisplayName("a trail with only a number shows it at every spot kept; one with only a name at every second")
    void trailsWithOnlyOne() {
        List<RoadLabelCandidate> numbers = shown(plan(trail(null, "E5", 2000), 6.9, 7.2), 2);
        for (RoadLabelCandidate c : numbers) {
            assertEquals("E5", c.label(2));
        }
        List<RoadLabelCandidate> names = shown(plan(trail("Sentiero", null, 2000), 6.9, 7.2), 2);
        assertEquals("Sentiero", names.get(0).label(2));
        assertEquals(null, names.get(0).shortText(2), "a name alone has nothing shorter to fall back on");
        assertEquals(4 * RoadLabelPlanner.TRAIL_SPACING_METERS, along(names.get(1)) - along(names.get(0)), 1.0);
        assertTrue(numbers.size() > names.size());
        List<RoadLabelCandidate> shortOne = shown(plan(trail("Sentiero", "12", 100), 6.9, 7.2), 8);
        assertEquals(1, shortOne.size(), "a short trail's one spot is kept at any frequency, with both");
        assertEquals("12" + RoadLabelPlanner.NUMBER_NAME_SEPARATOR + "Sentiero", shortOne.get(0).label(8));
    }

    @Test
    @DisplayName("a trail's bare number outranks its name in a crowded spot, and never a road")
    void numbersFirst() {
        List<RoadLabelCandidate> c = shown(plan(trail("Sentiero", "12", 2000), 6.9, 7.2), 2);
        RoadLabelCandidate number = null, both = null;
        for (RoadLabelCandidate x : c) {
            if (x.isNumberOnly(2)) {
                number = x;
            } else {
                both = x;
            }
        }
        assertTrue(number.priority(2) > both.priority(2));
        RoadLabelCandidate road = plan(way("R", RoadClass.ROAD, 500), 6.9, 7.2).get(0);
        assertTrue(road.priority(2) > number.priority(2));
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
        RoadFeature f = way("Via Roma", RoadClass.ROAD, 3000);
        int all = plan(f, 6.9, 7.2).size();
        double split = 7.0 + 1200 * DEG_PER_METER;   // between two spots
        List<RoadLabelCandidate> westPart = plan(f, 6.9, split);
        List<RoadLabelCandidate> eastPart = plan(f, split, 7.2);
        assertEquals(all, westPart.size() + eastPart.size(), "every spot kept by exactly one tile");
        assertTrue(!westPart.isEmpty() && !eastPart.isEmpty());
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
        assertTrue(road.priority(2) > trail.priority(2));
        assertTrue(trail.priority(2) > track.priority(2));
        assertTrue(longTrail.priority(2) > trail.priority(2));
        assertTrue(longTrail.priority(2) < road.priority(2), "length never lifts a trail over a road");
    }
}
