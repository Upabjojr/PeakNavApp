package com.peaknav.viewer.labels;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;

/**
 * The platform's own text drawing, for the map labels the app's fonts cannot draw: names in
 * Chinese, Japanese, Korean, Arabic, Hebrew, Thai, the Indian scripts and the rest. The app's
 * fonts are baked bitmaps of Latin, Greek and Cyrillic (see FontCharacters); the platform has
 * fonts for every script, and lays out what a glyph table cannot - letters that join and change
 * shape, text that runs right to left.
 *
 * <p>One per platform: Android's Canvas, iOS's UIKit, Java2D on the desktop and in the headless
 * renderer. Set by the launcher; with none set, such labels fall back to their
 * Latin form, as every label did before. Installed with LabelTextRasterizers.set.
 *
 * <p>Every method may be called from any thread, and at once from several: labels are measured
 * on the label threads and drawn into pictures on a worker.
 */
public interface LabelTextRasterizer {

    /** Whether the platform has a glyph for every character of {@code text}. */
    boolean canDraw(String text);

    /** The width the text takes at {@code textSize} pixels to the em: its advance, laid out. */
    float width(String text, float textSize);

    /**
     * The text drawn in {@code color} at {@code textSize} pixels to the em, on a transparent
     * picture exactly {@link #width} wide (rounded up) and as tall as the font's ascent plus
     * descent. RGBA8888, not yet a texture: this may run off the render thread.
     */
    Rendered draw(String text, float textSize, Color color);

    /** A drawn label: the picture, and where the baseline is in it. */
    final class Rendered {
        public final Pixmap pixmap;
        /** Pixels from the picture's top to the baseline. */
        public final float ascent;

        public Rendered(Pixmap pixmap, float ascent) {
            this.pixmap = pixmap;
            this.ascent = ascent;
        }
    }
}
