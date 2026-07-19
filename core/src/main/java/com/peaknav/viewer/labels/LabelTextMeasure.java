package com.peaknav.viewer.labels;

import com.badlogic.gdx.graphics.g2d.BitmapFont;

/**
 * Measures label text without building a {@link com.badlogic.gdx.graphics.g2d.GlyphLayout}.
 *
 * <p>GlyphLayout keeps a single {@code private static final Pool<GlyphRun>}, shared by every
 * layout in the process and not thread safe. Labels are sized on background threads (POI loading
 * and the visibility pass) while the render thread lays out scene2d widgets, and those two using
 * the pool at the same time hands out a run whose glyph array still holds nulls — which then
 * crashes in {@code GlyphLayout.getGlyphWidth}.
 *
 * <p>Reading {@link BitmapFont.BitmapFontData} is safe from any thread once the font has been
 * generated: the glyph table is only written during generation, and nothing here mutates it.
 */
final class LabelTextMeasure {

    private LabelTextMeasure() {}

    /**
     * Width of a single line of text, matching what GlyphLayout would report: the sum of the
     * glyph advances plus kerning, scaled the same way.
     */
    static float width(BitmapFont font, String text) {
        BitmapFont.BitmapFontData data = font.getData();
        float width = 0f;
        BitmapFont.Glyph previous = null;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            BitmapFont.Glyph glyph = data.getGlyph(ch);
            if (glyph == null) {
                glyph = data.missingGlyph;
                if (glyph == null) {
                    continue;
                }
            }
            if (previous != null) {
                width += previous.getKerning(ch);
            }
            width += glyph.xadvance;
            previous = glyph;
        }
        return width * data.scaleX;
    }

    /** Height of a single line, which is what GlyphLayout reports for label text. */
    static float height(BitmapFont font) {
        // BitmapFontData.setScale bakes the scale into capHeight (unlike per-glyph xadvance, which
        // width() scales explicitly), so this already reads in display units even though the atlas
        // is generated supersampled.
        return font.getData().capHeight;
    }
}
