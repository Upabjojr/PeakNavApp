package com.peaknav.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestWalkingSpeed {

    @Test
    void slowerUphillFasterOnAGentleDescent() {
        assertEquals(5.04, WalkingSpeed.kmh(0), 0.01, "about 5 km/h on the flat");
        assertEquals(6.0, WalkingSpeed.kmh(-0.05), 1e-9, "fastest going gently down");
        assertTrue(WalkingSpeed.kmh(0.2) < WalkingSpeed.kmh(0.1), "steeper up is slower");
        assertTrue(WalkingSpeed.kmh(-0.1) > WalkingSpeed.kmh(0.1), "down is faster than up");
        assertTrue(WalkingSpeed.kmh(-0.6) < WalkingSpeed.kmh(-0.1), "but a steep descent slows you down");
        assertEquals(WalkingSpeed.kmh(1.0), WalkingSpeed.kmh(3.0), 1e-12, "cliffs count as 45 degrees");
    }

    @Test
    void secondsForAStretch() {
        // A kilometre on the flat at 5.04 km/h: about 714 s.
        assertEquals(1000 / (5.0397 / 3.6), WalkingSpeed.seconds(1000, 0), 1.0);
        // The same kilometre climbing 200 m takes longer than dropping 200 m.
        assertTrue(WalkingSpeed.seconds(1000, 200) > WalkingSpeed.seconds(1000, -200));
        assertEquals(0, WalkingSpeed.seconds(0, 0), 1e-12);
        assertTrue(WalkingSpeed.seconds(0, 10) > 0, "straight up still takes time");
    }
}
