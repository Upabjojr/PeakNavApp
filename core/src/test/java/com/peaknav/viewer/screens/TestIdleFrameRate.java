package com.peaknav.viewer.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestIdleFrameRate {

    private static final float FRAME = 1f / 60f;

    @Test
    void fullRateWhileActive() {
        IdleFrameRate r = new IdleFrameRate();
        for (int i = 0; i < 600; i++) {
            assertEquals(IdleFrameRate.ACTIVE_FPS, r.nextFps(true, FRAME), "ten seconds of activity");
        }
    }

    @Test
    void dropsOnlyAfterThreeStillSeconds() {
        IdleFrameRate r = new IdleFrameRate();
        assertEquals(IdleFrameRate.ACTIVE_FPS, r.nextFps(false, 2.9f), "2.9 s still: not yet");
        assertEquals(IdleFrameRate.IDLE_FPS, r.nextFps(false, 0.2f), "3.1 s still: idle");
        assertEquals(IdleFrameRate.IDLE_FPS, r.nextFps(false, 60f), "and stays idle");
    }

    @Test
    void activityRestoresTheFullRateAndRestartsTheCount() {
        IdleFrameRate r = new IdleFrameRate();
        r.nextFps(false, 10f);
        assertEquals(IdleFrameRate.ACTIVE_FPS, r.nextFps(true, FRAME), "anything happening: straight back");
        assertEquals(IdleFrameRate.ACTIVE_FPS, r.nextFps(false, 2.5f), "the three seconds start again");
        assertEquals(IdleFrameRate.IDLE_FPS, r.nextFps(false, 0.6f));
    }
}
