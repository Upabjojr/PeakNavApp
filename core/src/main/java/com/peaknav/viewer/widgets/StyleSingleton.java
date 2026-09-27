package com.peaknav.viewer.widgets;

import static com.peaknav.utils.Constants.peakNavGreyColor;
import static com.peaknav.utils.PeakNavUtils.getC;


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

public class StyleSingleton {
    private BitmapFont bitmapFont = null;
    private BitmapFont bitmapFontSmall = null;
    private BitmapFont bitmapFontVerySmall = null;
    private BitmapFont bitmapFontVerySmallDark = null;
    private BitmapFont bitmapFontSmallWhite = null;
    private BitmapFont bitmapFontVerySmallWhite = null;
    private BitmapFont bitmapFontMedium = null;
    private volatile TextButton.TextButtonStyle textButtonStyle = null;

    private volatile CheckBox.CheckBoxStyle checkBoxStyle = null;
    private float minSize;

    public void updateMinSize() {
        // The short side as far as sizes go: capped on a tablet, fixed on the desktop.
        minSize = com.peaknav.utils.Units.getUiShortSide();
    }

    /**
     * How much larger than the on-screen size a font is laid out when the app has to make it
     * itself (see {@link #generateAllFonts}). Text is often drawn larger than its size - map
     * and area labels, toasts - and a font scaled beyond its atlas samples too few pixels.
     */
    private static final float FONT_SUPERSAMPLE = 2.0f;

    /**
     * The fonts, as {@link FontSpecs} describes them. Normally the ones baked when the app was
     * built (assets/fonts_baked/), drawn at their size for this screen: loading them is much
     * quicker than laying them out, which with Greek and Cyrillic took seconds on a phone at
     * every start. A font is made here with FreeType, as all of them used to be, only when this
     * screen needs it larger than it was baked - the headless renderer's large pictures - or
     * when the baked one is missing or unreadable.
     */
    public synchronized void generateAllFonts() {
        FreeTypeFontGenerator generator = null;
        BitmapFont[] fonts = new BitmapFont[FontSpecs.ALL.length];
        try {
            for (int i = 0; i < FontSpecs.ALL.length; i++) {
                FontSpecs.Spec spec = FontSpecs.ALL[i];
                int displaySize = spec.displaySize(minSize);
                BitmapFont font = loadBakedFont(spec, displaySize);
                if (font == null) {
                    if (generator == null) {
                        generator = new FreeTypeFontGenerator(Gdx.files.internal(FontSpecs.TTF));
                    }
                    font = generateFont(generator, spec, displaySize);
                }
                fonts[i] = font;
            }
        } finally {
            if (generator != null) {
                generator.dispose();
            }
        }
        bitmapFont = fonts[indexOf(FontSpecs.LARGE)];
        bitmapFontMedium = fonts[indexOf(FontSpecs.MEDIUM)];
        bitmapFontSmall = fonts[indexOf(FontSpecs.SMALL)];
        bitmapFontVerySmall = fonts[indexOf(FontSpecs.VERY_SMALL)];
        bitmapFontVerySmallDark = fonts[indexOf(FontSpecs.VERY_SMALL_DARK)];
        bitmapFontSmallWhite = fonts[indexOf(FontSpecs.SMALL_WHITE)];
        bitmapFontVerySmallWhite = fonts[indexOf(FontSpecs.VERY_SMALL_WHITE)];
    }

    private static int indexOf(FontSpecs.Spec spec) {
        for (int i = 0; i < FontSpecs.ALL.length; i++) {
            if (FontSpecs.ALL[i] == spec) {
                return i;
            }
        }
        throw new IllegalArgumentException(spec.name);
    }

    /**
     * The baked font, scaled to {@code displaySize}; null where it would have to be enlarged
     * (it would blur) or cannot be read. Its pages carry mipmaps - they are square powers of
     * two, which OpenGL ES 2 wants for them - so drawn smaller it stays smooth.
     */
    private static BitmapFont loadBakedFont(FontSpecs.Spec spec, int displaySize) {
        if (displaySize > spec.bakedSize() * 1.02f) {
            return null;
        }
        com.badlogic.gdx.files.FileHandle metrics =
                Gdx.files.internal(FontSpecs.DIRECTORY + "/" + spec.name + ".bin");
        if (!metrics.exists()) {
            return null;
        }
        com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g2d.TextureRegion> pages =
                new com.badlogic.gdx.utils.Array<>();
        try {
            BakedFontFile.Loaded loaded;
            java.io.InputStream in = metrics.read();
            try {
                loaded = BakedFontFile.read(new java.io.BufferedInputStream(in));
            } finally {
                in.close();
            }
            for (int i = 0; i < loaded.pages; i++) {
                Texture page = new Texture(
                        Gdx.files.internal(FontSpecs.DIRECTORY + "/" + spec.name + "_" + i + ".png"), true);
                page.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                pages.add(new com.badlogic.gdx.graphics.g2d.TextureRegion(page));
            }
            BitmapFont font = new BitmapFont(loaded.data, pages, false);
            font.setOwnsTexture(true);
            font.getData().setScale((float) displaySize / spec.bakedSize());
            return font;
        } catch (Exception unreadable) {
            for (com.badlogic.gdx.graphics.g2d.TextureRegion page : pages) {
                page.getTexture().dispose();
            }
            Gdx.app.error("StyleSingleton", "baked font " + spec.name + " unreadable, generating it", unreadable);
            return null;
        }
    }

    /** The font laid out here with FreeType, larger than shown (see FONT_SUPERSAMPLE). */
    private BitmapFont generateFont(FreeTypeFontGenerator generator, FontSpecs.Spec spec, int displaySize) {
        FreeTypeFontGenerator.FreeTypeFontParameter parameter =
                spec.parameter(Math.round(displaySize * FONT_SUPERSAMPLE), minSize);
        BitmapFont font = generator.generateFont(parameter);
        // Draw glyphs at their intended display size: the atlas is only higher resolution.
        font.getData().setScale((float) displaySize / parameter.size);
        return font;
    }

    public BitmapFont getBitmapFont() {
        return bitmapFont;
    }

    public BitmapFont getBitmapFontMedium() {
        return bitmapFontMedium;
    }

    public BitmapFont getBitmapFontSmall() {
        return bitmapFontSmall;
    }

    /** The small dark font for text on the menus' own white panels; see generateAllFonts. */
    public BitmapFont getBitmapFontVerySmallDark() {
        return bitmapFontVerySmallDark;
    }

    public BitmapFont getBitmapFontVerySmall() {
        return bitmapFontVerySmall;
    }

    public BitmapFont getBitmapFontSmallWhite() {
        return bitmapFontSmallWhite;
    }

    /** White, and smaller than the small font: for panes of figures that should not cover the map. */
    public BitmapFont getBitmapFontVerySmallWhite() {
        return bitmapFontVerySmallWhite;
    }

    public TextButton.TextButtonStyle getTextButtonStyle() {
        if (textButtonStyle == null) {
            synchronized (TextButton.TextButtonStyle.class) {
                if (textButtonStyle == null) {
                    textButtonStyle = new TextButton.TextButtonStyle();
                    Pixmap pixmap = new Pixmap(80, 30, Pixmap.Format.RGBA8888);
                    pixmap.setColor(peakNavGreyColor);
                    pixmap.fillRectangle(0, 0, 80, 30);
                    Texture texture = new Texture(pixmap);
                    pixmap.dispose();
                    NinePatch ninePatch = new NinePatch(texture);
                    // ninePatch.setColor(Color.CYAN);
                    NinePatchDrawable ninePatchDrawable = new NinePatchDrawable(ninePatch);
                    textButtonStyle = new TextButton.TextButtonStyle();
                    textButtonStyle.font = this.getBitmapFont();
                    textButtonStyle.fontColor = Color.WHITE;
                    textButtonStyle.up = ninePatchDrawable;
                    textButtonStyle.down = ninePatchDrawable;
                }
            }
        }
        return textButtonStyle;
    }

    public CheckBox.CheckBoxStyle getCheckBoxStyle() {
        if (checkBoxStyle == null) {
            synchronized (CheckBox.CheckBoxStyle.class) {
                if (checkBoxStyle == null) {
                    checkBoxStyle = new CheckBox.CheckBoxStyle();
                    int w = 64, h = w, dw = 8, dh = dw;
                    Pixmap checkbox = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                    checkbox.setColor(Color.GRAY);
                    checkbox.fillRectangle(0, 0, w, dh);
                    checkbox.fillRectangle(0, 0, dw, h);
                    checkbox.fillRectangle(w-dw, 0, dw, h);
                    checkbox.fillRectangle(0, h-dh, w, dh);
                    Pixmap checkboxChecked = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                    checkboxChecked.drawPixmap(checkbox, 0, 0);
                    checkboxChecked.setColor(Color.RED);
                    checkboxChecked.fillRectangle(2*dw, 2*dh, w-4*dw, h-4*dh);
                    checkBoxStyle.checkboxOn = new TextureRegionDrawable(new Texture(checkboxChecked));
                    checkBoxStyle.checkboxOff = new TextureRegionDrawable(new Texture(checkbox));
                    Pixmap pixmap = new Pixmap(80, 30, Pixmap.Format.RGBA8888);
                    pixmap.setColor(Color.CYAN);
                    pixmap.fillRectangle(0, 0, 80, 30);
                    Texture texture = new Texture(pixmap);
                    checkBoxStyle.checked = new TextureRegionDrawable(texture);
                    checkBoxStyle.font = this.getBitmapFont();
                    checkbox.dispose();
                    checkboxChecked.dispose();
                    pixmap.dispose();
                }
            }
        }
        return checkBoxStyle;
    }

    private volatile TextField.TextFieldStyle textFieldStyle = null;

    public TextField.TextFieldStyle getTextFieldStyle() {
        if (textFieldStyle == null) {
            synchronized (this) {
                if (textFieldStyle == null) {
                    Drawable cursor = getC().widgetTextures.getTransparentDrawable();
                    Drawable selection = getC().widgetTextures.getTransparentDrawable();
                    Drawable background = getC().widgetTextures.getTransparentDrawable();
                    textFieldStyle = new TextField.TextFieldStyle(getBitmapFont(), Color.WHITE, cursor, selection, background);
                }
            }
        }
        return textFieldStyle;
    }

    private volatile Slider.SliderStyle sliderStyle = null;

    public Slider.SliderStyle getSliderStyle() {
        if (sliderStyle == null) {
            synchronized (this) {
                if (sliderStyle == null) {
                    float w = minSize*0.05f;
                    sliderStyle = new Slider.SliderStyle();
                    sliderStyle.knob = getC().widgetTextures.getTextureRegionDrawable("icons/icon_elevation_button.png");
                    sliderStyle.knob.setMinHeight(3*w);
                    sliderStyle.knob.setMinWidth(2*w);
                    sliderStyle.background = getC().widgetTextures.getNinePatchDrawable("icons/slider_nine_patch.png");
                }
            }
        }
        return sliderStyle;
    }

    public Label.LabelStyle getLabelStyle() {
        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = getBitmapFont();
        labelStyle.fontColor = Color.WHITE;
        // labelStyle.background = getTransparentDrawable();
        labelStyle.background = getC().widgetTextures.getUniformDrawable(Color.BLACK);
        return labelStyle;
    }

    public Label.LabelStyle getLabelWatermarkStyle() {
        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = getBitmapFontSmall();
        labelStyle.fontColor = new Color(1f, 1f, 1f, 0.3f);
        // labelStyle.background = getTransparentDrawable();
        // labelStyle.background = getC().widgetTextures.getUniformDrawable(Color.BLACK);
        return labelStyle;
    }

    public Label.LabelStyle getLabelStyleSmall() {
        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = getBitmapFontSmall();
        labelStyle.fontColor = Color.WHITE;
        // labelStyle.background = getTransparentDrawable();
        labelStyle.background = getC().widgetTextures.getUniformDrawable(Color.BLACK);
        return labelStyle;
    }

    public Label.LabelStyle getLabelStyleHyperlink() {
        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = getBitmapFontSmallWhite();
        labelStyle.fontColor = Color.BLUE;
        return labelStyle;
    }

}
