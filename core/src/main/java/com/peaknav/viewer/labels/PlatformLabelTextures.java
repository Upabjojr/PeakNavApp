package com.peaknav.viewer.labels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The pictures of the labels the platform draws ({@link LabelTextRasterizer}), one texture per
 * text, size and colour, kept for the labels that come round again as the view turns.
 *
 * <p>A label asked for before its picture exists is drawn into one on a worker thread, and
 * uploaded on the render thread in a later frame; until then it shows its plate without text,
 * for the frame or two that takes. The least recently drawn pictures go once there are more
 * than {@link #PIXEL_BUDGET} can hold. Render thread only, apart from the worker.
 */
public final class PlatformLabelTextures {

    private PlatformLabelTextures() {
    }

    /**
     * Texture pixels kept, some 16 MB: many screenfuls of labels. Past it the least recently
     * drawn pictures go - but never one drawn in this frame or the last, or a view with more
     * such labels than the budget holds would drop and redraw them in turn, and flicker.
     */
    private static final long PIXEL_BUDGET = 4L * 1024 * 1024;

    private static final class Picture {
        final Texture texture;
        final float ascent;
        long lastFrame;

        Picture(Texture texture, float ascent) {
            this.texture = texture;
            this.ascent = ascent;
        }

        long pixels() {
            return (long) texture.getWidth() * texture.getHeight();
        }
    }

    /** In the order they were last drawn, least recent first. */
    private static final LinkedHashMap<String, Picture> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static long pixels;
    private static final Set<String> PENDING = new HashSet<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "platform-labels");
        t.setDaemon(true);
        return t;
    });

    /**
     * Draws {@code text} with its baseline at {@code baselineY} and its start at {@code x}, in
     * the batch's current coordinates (the labels' rotated ones). Nothing yet if its picture
     * is still being made.
     */
    public static void draw(SpriteBatch batch, String text, float textSize, Color color, float x, float baselineY) {
        String key = Math.round(textSize * 4f) + "|" + color.toIntBits() + "|" + text;
        Picture entry = CACHE.get(key);
        if (entry == null) {
            request(key, text, textSize, new Color(color));
            return;
        }
        entry.lastFrame = Gdx.graphics.getFrameId();
        Texture t = entry.texture;
        batch.draw(t, x, baselineY - (t.getHeight() - entry.ascent), t.getWidth(), t.getHeight());
    }

    private static void request(String key, String text, float textSize, Color color) {
        final LabelTextRasterizer rasterizer = LabelTextRasterizers.get();
        if (rasterizer == null || !PENDING.add(key)) {
            return;
        }
        WORKER.execute(() -> {
            LabelTextRasterizer.Rendered rendered;
            try {
                rendered = rasterizer.draw(text, textSize, color);
            } catch (RuntimeException failed) {
                Gdx.app.error("PlatformLabelTextures", "cannot draw " + text, failed);
                rendered = null;
            }
            final LabelTextRasterizer.Rendered done = rendered;
            Gdx.app.postRunnable(() -> {
                PENDING.remove(key);
                if (done == null) {
                    return;
                }
                Texture texture = new Texture(done.pixmap);
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                done.pixmap.dispose();
                Picture picture = new Picture(texture, done.ascent);
                picture.lastFrame = Gdx.graphics.getFrameId();
                Picture previous = CACHE.put(key, picture);
                pixels += picture.pixels();
                if (previous != null) {
                    pixels -= previous.pixels();
                    previous.texture.dispose();
                }
                trim();
            });
        });
    }

    /** Drops the least recently drawn pictures while over budget, sparing the ones on screen. */
    private static void trim() {
        long recent = Gdx.graphics.getFrameId() - 1;
        java.util.Iterator<Picture> it = CACHE.values().iterator();
        while (pixels > PIXEL_BUDGET && it.hasNext()) {
            Picture eldest = it.next();
            if (eldest.lastFrame >= recent) {
                break;   // everything after it was drawn as recently
            }
            it.remove();
            pixels -= eldest.pixels();
            eldest.texture.dispose();
        }
    }
}
