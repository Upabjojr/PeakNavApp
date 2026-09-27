package com.peaknav.viewer.mapscreens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Backgrounds for the place screen's buttons and search box: rounded corners, smoothed, with a
 * soft shadow below - in place of the flat square white boxes they had, which looked like the
 * buttons of an old desktop. Drawn once per colour and size into a small texture, and kept.
 */
final class RoundedDrawables {

    private RoundedDrawables() {
    }

    private static final Map<String, Texture> TEXTURES = new HashMap<>();

    /** How dark the shadow is where it meets the shape, fading to nothing over its width. */
    private static final float SHADOW_ALPHA = 0.22f;

    /**
     * A box of any size with corners of {@code radius} pixels, as a nine-patch; its content
     * padding is left to the caller.
     */
    static NinePatchDrawable box(Color fill, float radius) {
        int r = Math.max(3, Math.round(radius));
        int s = shadowOf(r);
        int stretch = 4;
        int side = 2 * (r + s) + stretch;
        Texture texture = texture("box", fill, r, side, side, s);
        int edge = r + s;
        NinePatchDrawable drawable = new NinePatchDrawable(new NinePatch(texture, edge, edge, edge, edge));
        return drawable;
    }

    /** A disc {@code size} pixels across, with the same shadow: the map's round buttons. */
    static TextureRegionDrawable disc(Color fill, float size) {
        int d = Math.max(8, Math.round(size));
        int r = d / 2;
        int s = shadowOf(r / 2);
        Texture texture = texture("disc", fill, r, d + 2 * s, d + 2 * s, s);
        TextureRegionDrawable drawable = new TextureRegionDrawable(new TextureRegion(texture));
        drawable.setMinWidth(d + 2 * s);
        drawable.setMinHeight(d + 2 * s);
        return drawable;
    }

    private static int shadowOf(int radius) {
        return Math.max(2, Math.round(radius * 0.35f));
    }

    private static Texture texture(String kind, Color fill, int r, int w, int h, int s) {
        String key = kind + fill + r + "x" + w + "x" + h;
        Texture texture = TEXTURES.get(key);
        if (texture == null) {
            Pixmap pixmap = draw(fill, r, w, h, s);
            texture = new Texture(pixmap);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap.dispose();
            TEXTURES.put(key, texture);
        }
        return texture;
    }

    /**
     * The shape inset by the shadow's width on every side, antialiased by its distance to each
     * pixel's centre, over its shadow: the same shape a little lower, fading outwards.
     */
    private static Pixmap draw(Color fill, int r, int w, int h, int s) {
        Pixmap pixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        float x0 = s, y0 = s, x1 = w - s, y1 = h - s;
        float shadowDrop = Math.max(1f, s * 0.4f);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float d = distance(px, py, x0, y0, x1, y1, r);
                float coverage = clamp(0.5f - d);
                // Pixmap rows run downwards: the shadow sits lower, at larger y.
                float ds = distance(px, py - shadowDrop, x0, y0, x1, y1, r);
                float shadow = ds <= 0 ? SHADOW_ALPHA : SHADOW_ALPHA * clamp(1f - ds / s);
                shadow *= shadow / SHADOW_ALPHA;   // eased: soft at its outer edge
                // The fill over the shadow.
                float a = coverage * fill.a + shadow * (1f - coverage * fill.a);
                float cr = 0, cg = 0, cb = 0;
                if (a > 0) {
                    float fillWeight = coverage * fill.a / a;
                    cr = fill.r * fillWeight;
                    cg = fill.g * fillWeight;
                    cb = fill.b * fillWeight;
                }
                pixmap.drawPixel(x, y, Color.rgba8888(cr, cg, cb, a));
            }
        }
        return pixmap;
    }

    /** Signed distance from a point to a rounded rectangle: negative inside. */
    private static float distance(float px, float py, float x0, float y0, float x1, float y1, float r) {
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        float hx = (x1 - x0) / 2 - r, hy = (y1 - y0) / 2 - r;
        float qx = Math.abs(px - cx) - hx, qy = Math.abs(py - cy) - hy;
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
