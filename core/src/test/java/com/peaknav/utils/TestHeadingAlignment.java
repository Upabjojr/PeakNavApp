package com.peaknav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestHeadingAlignment {

    private static float[] heading(double degrees) {
        double r = Math.toRadians(degrees);
        return new float[]{(float) Math.sin(r), (float) Math.cos(r)};
    }

    private static double headingOf(float x, float y) {
        double d = Math.toDegrees(Math.atan2(x, y));
        return d < 0 ? d + 360 : d;
    }

    @Test
    void theDeviceStartsWhereTheCameraFaces() {
        HeadingAlignment a = new HeadingAlignment();
        float[] device = heading(30), camera = heading(250);
        assertTrue(a.align(device[0], device[1], camera[0], camera[1]));
        assertEquals(250, headingOf(a.rotatedX(device[0], device[1]), a.rotatedY(device[0], device[1])), 1e-3);
    }

    @Test
    void laterTurnsFollowTheDevice() {
        HeadingAlignment a = new HeadingAlignment();
        float[] device = heading(30), camera = heading(250);
        a.align(device[0], device[1], camera[0], camera[1]);
        float[] turned = heading(30 + 45);
        assertEquals(295, headingOf(a.rotatedX(turned[0], turned[1]), a.rotatedY(turned[0], turned[1])), 1e-3, "45 degrees right of the start");
    }

    @Test
    void lengthIsKept() {
        HeadingAlignment a = new HeadingAlignment();
        a.align(0.6f, 0.3f, -0.2f, 0.9f);
        float x = a.rotatedX(0.3f, -0.4f), y = a.rotatedY(0.3f, -0.4f);
        assertEquals(0.5, Math.hypot(x, y), 1e-6);
    }

    @Test
    void noPairingWhilePointingStraightUpOrDown() {
        HeadingAlignment a = new HeadingAlignment();
        assertFalse(a.align(0.05f, 0.05f, 0f, 1f), "device pointing at the sky");
        assertFalse(a.align(0f, 1f, 0.01f, 0.1f), "camera looking at the ground");
        assertFalse(a.isAligned());
        assertEquals(0.3f, a.rotatedX(0.3f, 0.4f), 0f, "unpaired: unchanged");
    }
}
