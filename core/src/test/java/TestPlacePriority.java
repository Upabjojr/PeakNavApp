import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.labels.PoiObject;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Which of two place names claims a contested label spot, seen from Strembo in Val Rendena.
 *
 * <p>From the POI extract there. Population is patchy - half the villages carry none while
 * many hamlets do - and the list spans some 130 km, so neither size alone nor distance alone
 * orders it: size alone let every village in three provinces outrank the one underfoot.
 */
public class TestPlacePriority {

    private static Map<String, String> place(String kind, String population) {
        Map<String, String> tags = new HashMap<>();
        tags.put("place", kind);
        if (population != null)
            tags.put("population", population);
        return tags;
    }

    private static double score(Map<String, String> tags, double km) {
        return PoiObject.labelScore(PoiObject.labelWeight(DrawLabelCategory.PLACE, tags), km);
    }

    private static double hut(double km) {
        return PoiObject.labelScore(PoiObject.labelWeight(DrawLabelCategory.ALPINE_HUT, new HashMap<>()), km);
    }

    @Test
    public void theVillageUnderfootOutranksAFarUntaggedOne() {
        // Strembo (452, 100 m away) against any untagged village 60 km off.
        assertTrue(score(place("village", "452"), 0.1) > score(place("village", null), 60));
    }

    @Test
    public void anUntaggedVillageOutranksATaggedHamletAsNear() {
        assertTrue(score(place("village", null), 5) > score(place("hamlet", "49"), 5));
        // Nor does a hamlet tagged with its municipality's figure beat a real village nearby.
        assertTrue(score(place("village", "900"), 5) > score(place("hamlet", "1923"), 5));
    }

    @Test
    public void aCityOutranksTheVillagesAroundTheViewer() {
        // Trento (117317, 29.5 km) over Pinzolo (3118, 4.7 km); Rovereto (39289, 34 km) over Giustino.
        assertTrue(score(place("city", "117317"), 29.5) > score(place("village", "3118"), 4.7));
        assertTrue(score(place("town", "39289"), 34.4) > score(place("village", "694"), 3.4));
    }

    @Test
    public void aTownOutranksASuburbOrNeighbourhoodAtTheSameDistance() {
        assertTrue(score(place("town", null), 10) > score(place("suburb", null), 10));
        assertTrue(score(place("town", null), 10) > score(place("neighbourhood", null), 10));
    }

    @Test
    public void uninhabitedNamesAndRegionsCountForLittle() {
        assertTrue(hut(4.5) > score(place("locality", null), 1));
        assertTrue(score(place("hamlet", null), 5) > score(place("locality", null), 1));
        // "Lombardia", tagged with ten million people, is a name at one point 97 km away.
        assertTrue(score(place("village", null), 10) > score(place("state", "9917714"), 97));
    }

    @Test
    public void populationIsReadWithThousandsSeparators() {
        assertEquals(39289, PoiObject.parsePopulation(place("town", "39.289")));
        assertEquals(0, PoiObject.parsePopulation(place("town", "about 500")));
    }
}
