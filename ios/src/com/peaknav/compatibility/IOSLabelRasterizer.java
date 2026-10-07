package com.peaknav.compatibility;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.viewer.labels.LabelTextRasterizer;

import org.robovm.apple.coregraphics.CGPoint;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.coregraphics.CGSize;
import org.robovm.apple.coretext.CTLine;
import org.robovm.apple.coretext.CTLineBoundsOptions;
import org.robovm.apple.foundation.NSAttributedString;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSData;
import org.robovm.apple.uikit.NSAttributedStringAttributes;
import org.robovm.apple.uikit.UIColor;
import org.robovm.apple.uikit.UIFont;
import org.robovm.apple.uikit.UIGraphics;
import org.robovm.apple.uikit.UIImage;

/**
 * Map labels the app's fonts cannot draw, drawn by UIKit: the system font falls back through
 * fonts for every script iOS supports, and lays out joined and right-to-left scripts. See
 * {@link LabelTextRasterizer}. The app works in pixels (HdpiMode.Pixels), so a size in points
 * drawn into a context of scale 1 is a size in pixels.
 *
 * <p>UIKit's string drawing and image contexts may be used from any thread since iOS 4.
 */
public final class IOSLabelRasterizer implements LabelTextRasterizer {

    @Override
    public boolean canDraw(String text) {
        // The system font's fallback list covers every script iOS ships, which is all of them.
        return true;
    }

    @Override
    public float width(String text, float textSize) {
        return (float) attributed(text, textSize, Color.WHITE).getSize().getWidth();
    }

    @Override
    public Rendered draw(String text, float textSize, Color color) {
        NSAttributedString string = attributed(text, textSize, color);
        CGSize size = string.getSize();
        // The line's metrics, not the system font's: a name drawn by a fallback font with a
        // taller ascender (PingFang, the Thai, Arabic and Devanagari fonts) makes the line taller,
        // and draw(CGPoint) puts the top of that line at the point - the baseline sat lower than
        // Rendered said, and the bottom of the text was cut at the picture's edge. y is up from
        // the baseline in these bounds.
        CTLine line = CTLine.create(string);
        CGRect typographic = line.getBounds(CTLineBoundsOptions.ExcludeTypographicLeading);
        CGRect glyphs = line.getBounds(CTLineBoundsOptions.UseGlyphPathBounds);
        double lineAscent = typographic.getMaxY();
        double top = Math.max(lineAscent, glyphs.getMaxY() + 1);
        double bottom = Math.min(typographic.getMinY(), glyphs.getMinY() - 1);
        float ascent = (float) Math.ceil(top);
        int width = Math.max(1, (int) Math.ceil(size.getWidth()));
        int height = Math.max(1, (int) Math.ceil(ascent - bottom));
        byte[] bytes;
        // The image and its PNG are autoreleased, into a pool that drains when the thread ends -
        // and the label worker never ends: without a pool of its own, every label drawn stayed.
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            UIGraphics.beginImageContext(new CGSize(width, height), false, 1.0);
            UIImage image;
            try {
                // draw(CGPoint) puts the top of the line at the point, the baseline a line's
                // ascent below it: moved down so the baseline lands where Rendered says it is.
                string.draw(new CGPoint(0, ascent - lineAscent));
                image = UIGraphics.getImageFromCurrentImageContext();
            } finally {
                UIGraphics.endImageContext();
            }
            NSData png = image.toPNGData();
            bytes = png.getBytes();
        }
        Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
        return new Rendered(pixmap, ascent);
    }

    private static NSAttributedString attributed(String text, float textSize, Color color) {
        NSAttributedStringAttributes attributes = new NSAttributedStringAttributes()
                .setFont(UIFont.getSystemFont(textSize))
                .setForegroundColor(new UIColor(color.r, color.g, color.b, color.a));
        return new NSAttributedString(text, attributes);
    }
}
