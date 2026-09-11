import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadClassifier;
import com.peaknav.roads.RoadFeature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.map.datastore.Way;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which OpenStreetMap ways the road renderer draws, as what, and what it writes beside them -
 * including the relation tags the PBF parser appends to a way's own.
 */
class TestRoadClassifier {

    private static List<Tag> tags(String... kv) {
        List<Tag> out = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.add(new Tag(kv[i], kv[i + 1]));
        }
        return out;
    }

    private static final LatLong[] LINE = {
            new LatLong(46.00, 7.00), new LatLong(46.01, 7.01), new LatLong(46.02, 7.03)};
    private static final LatLong[] SQUARE = {
            new LatLong(46.00, 7.00), new LatLong(46.00, 7.01), new LatLong(46.01, 7.01),
            new LatLong(46.01, 7.00), new LatLong(46.00, 7.00)};

    private static List<RoadFeature> classify(List<Tag> tags, LatLong[] points) {
        List<RoadFeature> out = new ArrayList<>();
        RoadClassifier.classify(tags, points, out);
        return out;
    }

    private static RoadFeature only(List<Tag> tags) {
        List<RoadFeature> out = classify(tags, LINE);
        assertEquals(1, out.size(), "features for " + tags);
        return out.get(0);
    }

    @Test
    @DisplayName("roads are one class, their rank carried as an attribute")
    void roadRanks() {
        RoadFeature primary = only(tags("highway", "primary", "name", "Via Roma"));
        assertEquals(RoadClass.ROAD, primary.roadClass);
        assertEquals(RoadFeature.RANK_MAJOR, primary.attribute);
        assertEquals("Via Roma", primary.name);
        assertEquals(RoadFeature.RANK_LOCAL, only(tags("highway", "residential")).attribute);
        assertEquals(RoadFeature.RANK_SERVICE, only(tags("highway", "service")).attribute);
        assertEquals(RoadFeature.RANK_MAJOR, only(tags("highway", "trunk_link")).attribute);
    }

    @Test
    @DisplayName("tracks and trails get their own classes")
    void tracksAndTrails() {
        assertEquals(RoadClass.TRACK, only(tags("highway", "track")).roadClass);
        assertEquals(RoadClass.PATH, only(tags("highway", "path")).roadClass);
        assertEquals(RoadClass.PATH, only(tags("highway", "steps")).roadClass);
        assertEquals(RoadClass.PATH, only(tags("highway", "footway")).roadClass);
    }

    @Test
    @DisplayName("trail difficulty follows the SAC scale, folded into three grades")
    void trailDifficulty() {
        assertEquals(RoadFeature.TRAIL_EASY, only(tags("highway", "path")).attribute);
        assertEquals(RoadFeature.TRAIL_EASY,
                only(tags("highway", "path", "sac_scale", "hiking")).attribute);
        assertEquals(RoadFeature.TRAIL_MOUNTAIN,
                only(tags("highway", "path", "sac_scale", "mountain_hiking")).attribute);
        assertEquals(RoadFeature.TRAIL_MOUNTAIN,
                only(tags("highway", "path", "sac_scale", "demanding_mountain_hiking")).attribute);
        assertEquals(RoadFeature.TRAIL_ALPINE,
                only(tags("highway", "path", "sac_scale", "difficult_alpine_hiking")).attribute);
        assertEquals(RoadFeature.TRAIL_ALPINE,
                only(tags("highway", "via_ferrata")).attribute, "a via ferrata is alpine");
        // Free-form values, as mappers sometimes write them, are read by their grade.
        assertEquals(RoadFeature.TRAIL_ALPINE,
                RoadClassifier.trailDifficulty("T4 - difficult, exposed, steep alpine trail"));
        assertEquals(RoadFeature.TRAIL_MOUNTAIN, RoadClassifier.trailDifficulty("T3"));
        assertEquals(RoadFeature.TRAIL_EASY, RoadClassifier.trailDifficulty("T1"));
        assertEquals(RoadFeature.TRAIL_EASY, RoadClassifier.trailDifficulty("nonsense"));
    }

    @Test
    @DisplayName("pavements, crossings, tunnels and roads not yet built are not drawn")
    void excluded() {
        assertTrue(classify(tags("highway", "footway", "footway", "sidewalk"), LINE).isEmpty());
        assertTrue(classify(tags("highway", "footway", "footway", "crossing"), LINE).isEmpty());
        assertTrue(classify(tags("highway", "primary", "tunnel", "yes"), LINE).isEmpty(),
                "a road under a mountain is not on the surface");
        assertTrue(classify(tags("highway", "construction"), LINE).isEmpty());
        assertTrue(classify(tags("highway", "platform"), LINE).isEmpty());
        assertTrue(classify(tags("building", "yes"), LINE).isEmpty());
    }

    @Test
    @DisplayName("a square mapped as an area is not a way, but a closed walking loop still is")
    void plazas() {
        assertTrue(classify(tags("highway", "pedestrian", "area", "yes"), SQUARE).isEmpty());
        assertTrue(classify(tags("highway", "footway", "area", "yes"), SQUARE).isEmpty());
        assertEquals(1, classify(tags("highway", "footway"), SQUARE).size(),
                "a path round a park is walked like any other");
        assertEquals(1, classify(tags("highway", "footway", "area", "yes"), LINE).size(),
                "an open way cannot be an area, whatever it says");
    }

    @Test
    @DisplayName("pistes are coloured by difficulty; closed downhill pistes are areas")
    void pistes() {
        List<RoadFeature> area = classify(
                tags("piste:type", "downhill", "piste:difficulty", "advanced"), SQUARE);
        assertEquals(1, area.size());
        assertEquals(RoadClass.PISTE, area.get(0).roadClass);
        assertEquals(RoadFeature.PISTE_ADVANCED, area.get(0).attribute);
        assertTrue(area.get(0).area);
        assertNull(area.get(0).name, "piste names are the POI labels' job");

        RoadFeature line = classify(tags("piste:type", "downhill", "piste:difficulty", "easy"),
                LINE).get(0);
        assertFalse(line.area);
        assertEquals(RoadFeature.PISTE_EASY, line.attribute);

        RoadFeature loop = classify(tags("piste:type", "nordic"), SQUARE).get(0);
        assertFalse(loop.area, "a cross-country loop is a closed line, not a field of snow");
        assertEquals(RoadFeature.PISTE_NORDIC, loop.attribute);
    }

    @Test
    @DisplayName("a track that is a piste in winter is drawn as both")
    void trackAndPiste() {
        List<RoadFeature> both = classify(
                tags("highway", "track", "piste:type", "downhill", "piste:difficulty", "easy"),
                LINE);
        assertEquals(2, both.size());
        assertEquals(RoadClass.TRACK, both.get(0).roadClass);
        assertEquals(RoadClass.PISTE, both.get(1).roadClass);
    }

    @Test
    @DisplayName("a way's own name wins; a hiking route lends its number to an unnamed trail")
    void relationNames() {
        // [way tags] + [relation tags], the way PbfTileBinaryParser appends them.
        assertEquals("12", only(tags("highway", "path",
                "type", "route", "route", "hiking", "ref", "12", "name", "Alta Via")).name);
        assertEquals("Alta Via", only(tags("highway", "path",
                "type", "route", "route", "hiking", "name", "Alta Via")).name);
        assertEquals("Sentiero dei Fiori", only(tags("highway", "path", "name", "Sentiero dei Fiori",
                "type", "route", "route", "hiking", "ref", "12")).name);
        assertEquals("E62", only(tags("highway", "primary", "ref", "E62")).name,
                "a road with only a reference is labelled with it");
    }

    @Test
    @DisplayName("a bus route's name does not label the streets it runs along")
    void busRouteNamesAreIgnored() {
        assertNull(only(tags("highway", "residential",
                "type", "route", "route", "bus", "name", "Bus 3: Station => Hospital")).name);
        assertNull(only(tags("highway", "track",
                "type", "route", "route", "bus", "ref", "3")).name);
    }

    @Test
    @DisplayName("a piste route's tags make its members pistes")
    void pisteRelation() {
        List<RoadFeature> out = classify(tags("highway", "track",
                "type", "route", "route", "piste", "piste:type", "downhill",
                "piste:difficulty", "easy"), LINE);
        assertEquals(2, out.size());
        assertEquals(RoadFeature.PISTE_EASY, out.get(1).attribute);
    }

    @Test
    @DisplayName("rivers are classified for their names, but have no channel to be drawn in")
    void water() {
        RoadFeature river = only(tags("waterway", "river", "name", "Vispa"));
        assertEquals(RoadClass.WATER, river.roadClass);
        assertEquals("Vispa", river.name);
        assertFalse(RoadClass.WATER.isDrawn());
        assertTrue(classify(tags("waterway", "dam"), LINE).isEmpty());
    }

    @Test
    @DisplayName("ways straight from the map data store are classified, broken ones skipped")
    void classifyAllFromWays() {
        List<Way> ways = new ArrayList<>();
        ways.add(new Way((byte) 10, tags("highway", "track"), new LatLong[][]{LINE}, null));
        ways.add(new Way((byte) 10, tags("highway", "path"),
                new LatLong[][]{{new LatLong(46, 7), null, new LatLong(46.1, 7.1)}}, null));
        ways.add(new Way((byte) 10, tags("highway", "path"),
                new LatLong[][]{{new LatLong(46, 7)}}, null));
        ways.add(new Way((byte) 10, Collections.<Tag>emptyList(), new LatLong[][]{LINE}, null));
        List<RoadFeature> out = RoadClassifier.classifyAll(ways);
        assertEquals(1, out.size(), "only the complete track survives");
        assertEquals(RoadClass.TRACK, out.get(0).roadClass);
        assertEquals(3, out.get(0).size());
    }
}
