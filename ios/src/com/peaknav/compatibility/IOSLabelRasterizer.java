package com.peaknav.compatibility;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.viewer.labels.LabelTextRasterizer;

import org.robovm.apple.coregraphics.CGPoint;
import org.robovm.apple.coregraphics.CGSize;
import org.robovm.apple.foundation.NSAttributedString;
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
        UIFont font = UIFont.getSystemFont(textSize);
        float ascent = (float) font.getAscender();
        float descent = (float) -font.getDescender();
        NSAttributedString string = attributed(text, textSize, color);
        CGSize size = string.getSize();
        int width = Math.max(1, (int) Math.ceil(size.getWidth()));
        int height = Math.max(1, (int) Math.ceil(ascent + descent));
        UIGraphics.beginImageContext(new CGSize(width, height), false, 1.0);
        UIImage image;
        try {
            // draw(CGPoint) puts the top of the line at the point: the baseline lands an ascent
            // below it, where Rendered says it is.
            string.draw(new CGPoint(0, 0));
            image = UIGraphics.getImageFromCurrentImageContext();
        } finally {
            UIGraphics.endImageContext();
        }
        NSData png = image.toPNGData();
        byte[] bytes = png.getBytes();
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
