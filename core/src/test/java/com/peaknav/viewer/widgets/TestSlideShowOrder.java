package com.peaknav.viewer.widgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order the waiting slideshow shows its pictures in: every picture once per pass, and never
 * the same caption twice running - which a plain shuffle got wrong, reading "Find the paths to
 * the summit" three and four times over.
 */
class TestSlideShowOrder {

    /** The selection as it stands: how many pictures carry each caption. */
    private static Map<String, List<String>> pictures() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("world", 17);
        counts.put("summit", 11);
        counts.put("gpx", 10);
        counts.put("peaks", 7);
        counts.put("photo", 5);
        counts.put("terrain", 4);
        counts.put("orbit", 4);
        counts.put("stars", 2);
        Map<String, List<String>> byCaption = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            List<String> group = new ArrayList<>();
            for (int i = 0; i < entry.getValue(); i++) {
                group.add(entry.getKey() + "_" + i + ".jpg");
            }
            byCaption.put(entry.getKey(), group);
        }
        return byCaption;
    }

    @Test
    void everyPictureOncePerPassAndNoCaptionTwiceRunning() {
        Map<String, List<String>> byCaption = pictures();
        int total = 0;
        for (List<String> group : byCaption.values()) {
            total += group.size();
        }
        String previous = null;
        for (int pass = 0; pass < 50; pass++) {
            List<String[]> order = SlideShow.plan(byCaption, previous);
            assertEquals(total, order.size(), "a pass shows every picture");
            Set<String> seen = new HashSet<>();
            for (String[] slide : order) {
                assertEquals(true, seen.add(slide[0]), "each picture once: " + slide[0]);
                assertNotEquals(previous, slide[1], "two pictures running with the same caption");
                previous = slide[1];
            }
        }
    }

    @Test
    void aSingleCaptionIsShownWithoutComplaint() {
        Map<String, List<String>> only = new LinkedHashMap<>();
        only.put("summit", new ArrayList<>(List.of("a.jpg", "b.jpg", "c.jpg")));
        assertEquals(3, SlideShow.plan(only, null).size(), "nothing to alternate with, but shown");
    }
}
