package com.peaknav.views;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.viewer.labels.LabelTextRasterizer;

/**
 * Map labels the app's fonts cannot draw, drawn by Android's own text: its fonts cover every
 * script the system supports, and it joins, shapes and orders letters (Arabic, Devanagari,
 * Hebrew) as no glyph table can. See {@link LabelTextRasterizer}.
 */
final class AndroidLabelRasterizer implements LabelTextRasterizer {

    /** A Paint per thread: labels are measured on several at once, and Paint is not thread safe. */
    private final ThreadLocal<Paint> paints = new ThreadLocal<Paint>() {
        @Override
        protected Paint initialValue() {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            paint.setTypeface(Typeface.SANS_SERIF);
            return paint;
        }
    };

    @Override
    public boolean canDraw(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;   // no way to ask before Android 6; its fonts cover the major scripts
        }
        Paint paint = paints.get();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int length = Character.charCount(codePoint);
            // One character at a time: hasGlyph answers for a single grapheme.
            if (!Character.isWhitespace(codePoint) && !paint.hasGlyph(text.substring(i, i + length))) {
                return false;
            }
            i += length;
        }
        return true;
    }

    @Override
    public float width(String text, float textSize) {
        Paint paint = paints.get();
        paint.setTextSize(textSize);
        return paint.measureText(text);
    }

    @Override
    public Rendered draw(String text, float textSize, Color color) {
        Paint paint = paints.get();
        paint.setTextSize(textSize);
        paint.setColor(Color.argb8888(color));
        // The paint's metrics are its typeface's; a name in another script is drawn by a
        // fallback font, whose marks can reach above or below them and were cut off at the
        // picture's edge. The glyphs' own bounds say how far they go.
        Paint.FontMetrics metrics = paint.getFontMetrics();
        android.graphics.Rect ink = new android.graphics.Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        float ascent = (float) Math.ceil(Math.max(-metrics.ascent, -ink.top + 1));
        float descent = Math.max(metrics.descent, ink.bottom + 1);
        int width = Math.max(1, (int) Math.ceil(paint.measureText(text)));
        int height = Math.max(1, (int) Math.ceil(ascent + descent));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawText(text, 0f, ascent, paint);
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);   // not premultiplied
        bitmap.recycle();
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = argb[y * width + x];
                pixmap.drawPixel(x, y, (c << 8) | (c >>> 24));
            }
        }
        return new Rendered(pixmap, ascent);
    }
}
