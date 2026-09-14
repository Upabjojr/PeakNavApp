package com.peaknav.gpx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.peaknav.utils.PreferencesManager.UnitSystem;

import org.junit.jupiter.api.Test;

public class TestGraphTicks {

    @Test
    void heightsAreRoundInTheChosenUnits() {
        assertArrayEquals(new double[]{2000, 2500, 3000},
                GraphTicks.heightTicksMetres(1608, 3089, UnitSystem.METRIC, 3), 1e-9);
        // 5276 to 10135 ft: every 2000 ft, returned in metres.
        assertArrayEquals(new double[]{6000 / 3.28084, 8000 / 3.28084, 10000 / 3.28084},
                GraphTicks.heightTicksMetres(1608, 3089, UnitSystem.IMPERIAL, 3), 1e-6);
        assertArrayEquals(new double[]{1010, 1020},
                GraphTicks.heightTicksMetres(1003, 1021, UnitSystem.METRIC, 3), 1e-9);
    }

    @Test
    void timesAreRoundFromTheStart() {
        assertArrayEquals(new double[]{0, 120, 240}, GraphTicks.timeTicksMinutes(352, 3), 1e-9);
        assertArrayEquals(new double[]{0, 15, 30}, GraphTicks.timeTicksMinutes(40, 3), 1e-9);
        assertArrayEquals(new double[]{0, 60, 120, 180, 240, 300}, GraphTicks.timeTicksMinutes(352, 6), 1e-9);
        assertEquals("0:00", GraphTicks.formatClock(0));
        assertEquals("2:56", GraphTicks.formatClock(176));
        assertEquals("25:10", GraphTicks.formatClock(1510));
    }

    @Test
    void aTimeIsPlacedWhereTheTrackGotToThen() {
        float[] elapsed = {0, 10, 20, 40};
        assertEquals(0f, GraphTicks.fractionAt(elapsed, 0), 1e-6);
        assertEquals((2 + 0.5f) / 3, GraphTicks.fractionAt(elapsed, 30), 1e-6);
        assertEquals(1f, GraphTicks.fractionAt(elapsed, 50), 1e-6);
        assertEquals(5.0, GraphTicks.niceStep(4.2), 1e-9);
        assertEquals(500.0, GraphTicks.niceStep(493.7), 1e-9);
        assertEquals(1000.0, GraphTicks.niceStep(1000), 1e-9);
    }
}
