package com.peaknav.viewer.desktop;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.viewer.labels.LabelTextRasterizer;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;

/**
 * Map labels the app's fonts cannot draw, drawn by Java2D - on the desktop, and in the headless
 * renderer. The logical "SansSerif" font is a composite of the system's fonts, so it reaches
 * whatever scripts they cover; TextLayout joins and orders the letters of scripts that need it
 * (Arabic, Hebrew, Devanagari). Works without a display.
 */
public final class Java2DLabelRasterizer implements LabelTextRasterizer {

    /** Antialiased, with fractional advances, as the pictures will be. */
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private static final Font BASE = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

    @Override
    public boolean canDraw(String text) {
        return BASE.canDisplayUpTo(text) == -1;
    }

    @Override
    public float width(String text, float textSize) {
        return new TextLayout(text, BASE.deriveFont(textSize), CONTEXT).getAdvance();
    }

    @Override
    public Rendered draw(String text, float textSize, Color color) {
        TextLayout layout = new TextLayout(text, BASE.deriveFont(textSize), CONTEXT);
        float ascent = layout.getAscent();
        int width = Math.max(1, (int) Math.ceil(layout.getAdvance()));
        int height = Math.max(1, (int) Math.ceil(ascent + layout.getDescent()));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(new java.awt.Color(color.r, color.g, color.b, color.a));
            layout.draw(g, 0f, ascent);
        } finally {
            g.dispose();
        }
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);   // not premultiplied
                pixmap.drawPixel(x, y, (argb << 8) | (argb >>> 24));
            }
        }
        return new Rendered(pixmap, ascent);
    }
}
