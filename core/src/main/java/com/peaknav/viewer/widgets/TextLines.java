package com.peaknav.viewer.widgets;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a message into a width by breaking it into lines, for labels whose text is a sentence in
 * whatever language the app speaks.
 *
 * <p>A scene2d {@code Label} draws its text on one line however wide that is, and a centred one
 * spills off both sides of the screen: "No downloaded data for this area" in the large font is
 * twelve buttons wide in English and eighteen in German, on a phone ten buttons across. Label's
 * own wrapping is no answer for a label with a background, because a wrapped label takes the
 * whole width of its cell, and the black plate behind "Loading..." would become a band across
 * the screen.
 *
 * <p>So the line breaks are put into the text itself: the label stays as wide as its widest
 * line, and the plate still hugs the words. The lines are balanced, not filled greedily - "No
 * downloaded data / for this area" rather than "No downloaded data for this / area" - by finding
 * the narrowest width at which the text still takes the fewest lines it can. Only when even the
 * last allowed line is not enough is the font made smaller. Line breaks already in the text (the
 * download's percentage, on a line of its own) are kept, each paragraph broken on its own.
 */
public final class TextLines {

    private TextLines() {
    }

    /** The smallest the font is scaled down to, when the lines alone cannot make the text fit. */
    private static final float MIN_SCALE = 0.5f;
    private static final float SCALE_STEP = 0.05f;

    /**
     * Sets the label's text, broken into at most {@code maxLines} lines no wider than
     * {@code maxWidth}, and its font scale, the font's own unless the text could not fit otherwise.
     *
     * @param maxWidth the widest a line may be, in stage units; nothing is done to the text when
     *                 it is not positive (no stage yet to measure against)
     */
    public static void fit(Label label, String text, float maxWidth, int maxLines) {
        if (text == null) {
            text = "";
        }
        BitmapFont font = label.getStyle().font;
        if (font == null) {
            label.setText(text);
            return;
        }
        // Label.setFontScale does not multiply the font's own scale, it replaces it - and the
        // app's fonts are baked at twice their size and scaled back by half (see
        // StyleSingleton.FONT_SUPERSAMPLE). A label set to scale 1 draws its text twice as large.
        // So every scale here is relative to the font's own.
        float ownScaleX = font.getData().scaleX;
        float ownScaleY = font.getData().scaleY;
        if (maxWidth <= 0) {
            label.setFontScale(ownScaleX, ownScaleY);
            label.setText(text);
            return;
        }
        // One layout for this call: the loading label is set from the tile threads as well as
        // the render thread, so nothing here may be shared between calls.
        GlyphLayout layout = new GlyphLayout();
        String[] paragraphs = text.split("\n", -1);
        for (float scale = 1f; scale >= MIN_SCALE - 1e-4f; scale -= SCALE_STEP) {
            // Measured at the font's own scale, so widths are compared unscaled.
            float width = maxWidth / scale;
            List<String> lines = balanced(font, layout, paragraphs, width, maxLines);
            if (lines != null) {
                label.setFontScale(ownScaleX * scale, ownScaleY * scale);
                label.setText(joinLines(lines));
                return;
            }
        }
        // Too long even at the smallest scale: the fewest lines it can take there, which is still
        // better than one line off both edges of the screen.
        label.setFontScale(ownScaleX * MIN_SCALE, ownScaleY * MIN_SCALE);
        label.setText(joinLines(greedy(font, layout, paragraphs, maxWidth / MIN_SCALE)));
    }

    /**
     * The lines as one string, one per row.
     *
     * <p>Joined by hand rather than with String.join: that is a Java 8 method, and RoboVM's
     * runtime is Android's, which does not have it - the iOS build would compile and then die
     * with NoSuchMethodError the first time a message was fitted, which on iOS is while the
     * first screen is still being built.
     */
    private static String joinLines(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(lines.get(i));
        }
        return joined.toString();
    }

    /**
     * The text in the fewest lines it takes at {@code width}, each as short as that number allows;
     * null if it takes more than {@code maxLines} lines or has a word wider than {@code width}.
     */
    private static List<String> balanced(BitmapFont font, GlyphLayout layout, String[] paragraphs,
                                         float width, int maxLines) {
        List<String> lines = greedy(font, layout, paragraphs, width);
        if (lines == null || lines.size() > maxLines) {
            return null;
        }
        // Narrow the width as far as the same number of lines still holds: the lines even out.
        int count = lines.size();
        float lo = 0f, hi = width;
        for (int i = 0; i < 20; i++) {
            float mid = 0.5f * (lo + hi);
            List<String> tried = greedy(font, layout, paragraphs, mid);
            if (tried != null && tried.size() <= count) {
                hi = mid;
                lines = tried;
            } else {
                lo = mid;
            }
        }
        return lines;
    }

    /** Fills each line with as many words as fit; null if a single word is wider than the width. */
    private static List<String> greedy(BitmapFont font, GlyphLayout layout, String[] paragraphs,
                                       float width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String[] words = paragraph.trim().split(" +");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (measure(font, layout, word) > width) {
                    return null;
                }
                if (line.length() == 0) {
                    line.append(word);
                } else if (measure(font, layout, line + " " + word) <= width) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static float measure(BitmapFont font, GlyphLayout layout, String text) {
        layout.setText(font, text);
        return layout.width;
    }
}
