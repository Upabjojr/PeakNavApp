package com.peaknav.viewer.mapscreens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.util.HashMap;
import java.util.Map;

/**
 * Diagonal stripes for the downloaded areas, one colour to a kind of data. Each kind has its own
 * lane in the pattern - the stripes of {@code count} kinds side by side, never over one another -
 * so where a tile holds several kinds all their colours show, and a kind that is missing shows
 * as a missing colour. A flat tint of each, drawn over the others, mixed them into one colour
 * that said nothing about which were there.
 *
 * <p>The texture is one period of the pattern, square and a power of two so that it can repeat
 * on any GPU; drawn with texture coordinates taken from the map's own pixels, the stripes run on
 * unbroken from one tile to the next and move with the map.
 */
final class Hatching {

    private Hatching() {
    }

    private static final Map<String, Texture> TEXTURES = new HashMap<>();

    /** The context the textures were made under (GlContext): replaced with the activity, or when lost. */
    private static Object context;

    /**
     * A texture belongs to the graphics context that made it, and this cache, being static,
     * outlives one: Android rebuilds the activity and the process lives on. The textures of a
     * context that has gone draw as whatever now has their number, or as nothing, so the cache
     * starts again. They are not disposed, their context having taken them with it.
     */
    private static void checkContext() {
        Object current = com.peaknav.utils.GlContext.current();
        if (context != current) {
            TEXTURES.clear();
            context = current;
        }
    }

    /** How opaque a stripe is: the imagery shows through it, and between the stripes. */
    static final float ALPHA = 0.4f;

    /** One period in pixels: most of a button, rounded to a power of two - wide enough that four
     * colours side by side are each a stripe of their own, not a haze. */
    static int period(float unit) {
        int period = 8;
        while (period * 2 <= 0.9f * unit) {
            period *= 2;
        }
        return period;
    }

    /**
     * The stripes of kind {@code index} of {@code count}: diagonal bands one {@code count}th of
     * the period wide, each kind shifted along by its own share, antialiased.
     */
    static Texture texture(Color color, int index, int count, int period) {
        checkContext();
        String key = color + "/" + index + "/" + count + "/" + period;
        Texture texture = TEXTURES.get(key);
        if (texture != null) {
            return texture;
        }
        Pixmap pixmap = new Pixmap(period, period, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        float lane = (float) period / count;
        // About half its lane: the imagery shows between every two colours, which keeps them
        // apart and leaves the map readable under four of them.
        float width = lane * 0.45f;
        float start = index * lane;
        int samples = 4;
        for (int y = 0; y < period; y++) {
            for (int x = 0; x < period; x++) {
                int inside = 0;
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        float d = x + (sx + 0.5f) / samples + y + (sy + 0.5f) / samples;
                        float along = ((d - start) % period + period) % period;
                        if (along < width) {
                            inside++;
                        }
                    }
                }
                float coverage = (float) inside / (samples * samples);
                pixmap.drawPixel(x, y, Color.rgba8888(color.r, color.g, color.b, coverage * ALPHA));
            }
        }
        texture = new Texture(pixmap);
        pixmap.dispose();
        texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        TEXTURES.put(key, texture);
        return texture;
    }
}
