package com.peaknav.viewer.widgets;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.peaknav.utils.FontCharacters;

/**
 * The app's fonts, described once for both places that make them: the build, which bakes them
 * into {@code assets/fonts_baked/} (com.peaknav.tools.FontBaker, run by {@code :core:bakeFonts}),
 * and {@link StyleSingleton}, which loads those - or, where the screen needs a font larger than
 * was baked, generates it itself as it always used to.
 *
 * <p>Each font is a fraction of the screen's short side ({@link com.peaknav.utils.Units#getUiShortSide}),
 * so its size in pixels differs from one device to the next. They are baked once, for the largest
 * short side a phone has, and drawn scaled down: the atlas pages are square powers of two, so they
 * can carry mipmaps, which keep text smooth at any reduction.
 */
public final class FontSpecs {

    private FontSpecs() {
    }

    /** The screen short side the fonts are baked for: a QHD phone's, the largest a phone has. */
    public static final int BAKED_SHORT_SIDE = 1440;

    /** Where the baked fonts are, among the assets. */
    public static final String DIRECTORY = "fonts_baked";

    /** The font file every one is made from. */
    public static final String TTF = "liberation_fonts/LiberationSans-Regular.ttf";

    /** Side of an atlas page: a power of two, for mipmaps; small enough for any GPU. */
    public static final int PAGE_SIZE = 1024;

    /** Empty pixels round each glyph, so the smaller mipmap levels do not bleed neighbours in. */
    public static final int PAGE_PADDING = 4;

    /**
     * The short side border widths and shadow offsets are given for: a common 1080-pixel phone.
     * They scale with the font, so the outline keeps its weight at any size.
     */
    private static final float OUTLINE_REFERENCE_SHORT_SIDE = 1080f;

    /** One of the app's fonts. */
    public static final class Spec {
        /** File name among the baked fonts. */
        public final String name;
        /** Size as a fraction of the screen's short side. */
        public final float fraction;
        final Color color;
        final Color borderColor;
        final float borderWidth;
        final Color shadowColor;
        final int shadowOffset;

        Spec(String name, float fraction, Color color) {
            this(name, fraction, color, null, 0f, null, 0);
        }

        Spec(String name, float fraction, Color color, Color borderColor, float borderWidth,
             Color shadowColor, int shadowOffset) {
            this.name = name;
            this.fraction = fraction;
            this.color = color;
            this.borderColor = borderColor;
            this.borderWidth = borderWidth;
            this.shadowColor = shadowColor;
            this.shadowOffset = shadowOffset;
        }

        /** Its size in pixels on a screen whose short side is {@code shortSide}. */
        public int displaySize(float shortSide) {
            return Math.max(1, Math.round(shortSide * fraction));
        }

        /** Its size as baked. */
        public int bakedSize() {
            return displaySize(BAKED_SHORT_SIDE);
        }

        /**
         * FreeType's parameters for this font at {@code pixelSize} pixels, drawn for a screen
         * whose short side is {@code shortSide} (which scales the outline and shadow).
         */
        public FreeTypeFontGenerator.FreeTypeFontParameter parameter(int pixelSize, float shortSide) {
            FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
            // Every font has the same glyphs (see FontCharacters).
            parameter.characters = FontCharacters.BAKED;
            parameter.size = pixelSize;
            parameter.color = color;
            parameter.minFilter = Texture.TextureFilter.Linear;
            parameter.magFilter = Texture.TextureFilter.Linear;
            float outline = pixelSize / (shortSide * fraction) * shortSide / OUTLINE_REFERENCE_SHORT_SIDE;
            if (borderColor != null) {
                parameter.borderColor = borderColor;
                parameter.borderWidth = borderWidth * outline;
            }
            if (shadowColor != null) {
                parameter.shadowColor = shadowColor;
                parameter.shadowOffsetX = Math.max(1, Math.round(shadowOffset * outline));
                parameter.shadowOffsetY = parameter.shadowOffsetX;
            }
            return parameter;
        }
    }

    public static final Spec LARGE = new Spec("large", 0.08f, Color.WHITE);
    public static final Spec MEDIUM = new Spec("medium", 0.06f, Color.BLACK);
    public static final Spec SMALL = new Spec("small", 0.04f, Color.BLACK);
    /**
     * The small text that sits on the map itself - the copyright line, the download's progress
     * and its percentage - is read against snow, forest, rock and sky in turn. White on a dark
     * outline, not black on a white one: a dark outline is the one thing that never disappears
     * into the map, white glyphs carry at this size where black ones fill in, and the pair reads
     * the same way road and peak labels do. A soft shadow under it pulls the letters off pale
     * ground without thickening them.
     */
    public static final Spec VERY_SMALL = new Spec("very_small", 0.025f, Color.WHITE,
            new Color(0f, 0f, 0f, 0.9f), 2f, new Color(0f, 0f, 0f, 0.45f), 1);
    /**
     * The same size in plain black, for the captions inside the menus, which have a white panel
     * behind them: an outlined font tinted dark would come out as a black smudge.
     */
    public static final Spec VERY_SMALL_DARK = new Spec("very_small_dark", 0.025f, Color.BLACK);
    public static final Spec SMALL_WHITE = new Spec("small_white", 0.04f, Color.WHITE);
    /** White, and smaller than the small font: for panes of figures that should not cover the map. */
    public static final Spec VERY_SMALL_WHITE = new Spec("very_small_white", 0.03f, Color.WHITE);

    public static final Spec[] ALL = {
            LARGE, MEDIUM, SMALL, VERY_SMALL, VERY_SMALL_DARK, SMALL_WHITE, VERY_SMALL_WHITE,
    };
}
