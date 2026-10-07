package com.peaknav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestUiScale {

    @Test
    void phonesKeepTheirSizes() {
        assertEquals(1f, Units.uiScale(1080, 2.75f), 1e-6f, "Pixel 5, 393 dp across");
        assertEquals(1f, Units.uiScale(1440, 3.5f), 1e-6f, "a large phone, 411 dp across");
        assertEquals(1f, Units.uiScale(1170, 460 / 160f), 1e-6f, "iPhone 13, 407 dp across");
    }

    @Test
    void tabletsGetPhoneSizedControls() {
        assertEquals(0.6f, Units.uiScale(1600, 2f), 1e-6f, "10-inch Android tablet, 800 dp across");
        assertEquals(480 / 931f, Units.uiScale(1536, 264 / 160f), 1e-3f, "iPad, 931 dp across");
    }

    @Test
    void anUnknownDensityChangesNothing() {
        assertEquals(1f, Units.uiScale(1600, 0f), 1e-6f);
        assertEquals(1f, Units.uiScale(0, 2f), 1e-6f);
    }
}
