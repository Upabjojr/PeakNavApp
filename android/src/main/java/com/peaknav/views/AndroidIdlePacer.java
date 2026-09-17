package com.peaknav.views;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.badlogic.gdx.Gdx;
import com.peaknav.viewer.screens.IdleFrameRate;

/**
 * Lowers the frame rate while the map is idle, for {@link IdleFrameRate}.
 *
 * <p>libGDX's Android backend ignores {@code Gdx.graphics.setForegroundFPS}: it renders on every
 * display refresh (60 Hz, see {@link AndroidMainFragment}) or only on request. At the full rate
 * nothing changes - continuous rendering, as before. At a lower rate continuous rendering is
 * switched off and a frame is requested on the display's vsync, every third refresh for 20 fps,
 * so the frames stay evenly spaced. A touch still draws a frame at once: libGDX requests one for
 * every input event, and the map then asks for the full rate back.
 */
final class AndroidIdlePacer implements IdleFrameRate.Pacer, Choreographer.FrameCallback {

    /** Half a 60 Hz refresh: vsync timestamps jitter, and a frame must not slip to the next one. */
    private static final long SLACK_NANOS = 8_000_000L;

    private final LifecycleOwner owner;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only.
    private boolean ticking = false;
    private long intervalNanos = 0L;
    private long lastRequestNanos = 0L;

    AndroidIdlePacer(LifecycleOwner owner) {
        this.owner = owner;
    }

    @Override
    public void setFps(int fps) {
        if (fps >= IdleFrameRate.ACTIVE_FPS) {
            Gdx.graphics.setContinuousRendering(true);
            main.post(this::stopTicking);
        } else {
            long interval = 1_000_000_000L / Math.max(1, fps);
            main.post(() -> startTicking(interval));
            Gdx.graphics.setContinuousRendering(false);
        }
    }

    private void startTicking(long interval) {
        intervalNanos = interval;
        if (!ticking) {
            ticking = true;
            lastRequestNanos = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopTicking() {
        if (ticking) {
            ticking = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!ticking) {
            return;
        }
        // In the background there is nothing to draw; the map asks for the full rate again
        // when it resumes, which switches continuous rendering back on.
        if (!owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            ticking = false;
            return;
        }
        if (frameTimeNanos - lastRequestNanos >= intervalNanos - SLACK_NANOS) {
            lastRequestNanos = frameTimeNanos;
            Gdx.graphics.requestRendering();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}
