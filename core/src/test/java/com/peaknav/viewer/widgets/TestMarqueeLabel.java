package com.peaknav.viewer.widgets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestMarqueeLabel {

    private static final float W = MarqueeLabel.WAIT_SECONDS, R = MarqueeLabel.REST_SECONDS;

    @Test
    void aCaptionThatFitsNeverMoves() {
        for (float t = 0; t < 30; t += 0.7f) {
            assertEquals(0f, MarqueeLabel.offsetAt(t, 0f, 50f));
        }
    }

    @Test
    void waitsThenSlidesToTheEndRestsAndStartsOver() {
        // 100 px too long at 50 px a second: 2 s still, 2 s sliding, 1.5 s resting at the end.
        assertEquals(0f, MarqueeLabel.offsetAt(0f, 100f, 50f));
        assertEquals(0f, MarqueeLabel.offsetAt(W - 0.01f, 100f, 50f));
        assertEquals(50f, MarqueeLabel.offsetAt(W + 1f, 100f, 50f), 1e-3f);
        assertEquals(100f, MarqueeLabel.offsetAt(W + 2f, 100f, 50f), 1e-3f);
        assertEquals(100f, MarqueeLabel.offsetAt(W + 2f + R - 0.01f, 100f, 50f), 1e-3f);
        assertEquals(0f, MarqueeLabel.offsetAt(W + 2f + R + 0.01f, 100f, 50f), 1e-3f);
        assertEquals(50f, MarqueeLabel.offsetAt(2 * (W + 2f + R) + W + 1f, 100f, 50f), 1e-2f);
    }
}
