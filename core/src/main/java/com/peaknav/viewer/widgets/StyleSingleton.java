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
    private BitmapFont bitmapFontSmallWhite = null;
    private BitmapFont bitmapFontMedium = null;
    private volatile TextButton.TextButtonStyle textButtonStyle = null;

    private volatile CheckBox.CheckBoxStyle checkBoxStyle = null;
    private float minSize;

    public void updateMinSize() {
        minSize = Float.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    public synchronized void generateAllFonts() {
        FreeTypeFontGenerator freeTypeFontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("liberation_fonts/LiberationSans-Regular.ttf"));

        FreeTypeFontGenerator.FreeTypeFontParameter freeTypeFontParameter;


        freeTypeFontParameter = new FreeTypeFontGenerator.FreeTypeFontParameter();

        freeTypeFontParameter.characters = FreeTypeFontGenerator.DEFAULT_CHARS;

        // freeTypeFontParameter.incremental = true;
        freeTypeFontParameter.size = Math.round(minSize*0.08f);
        bitmapFont = freeTypeFontGenerator.generateFont(freeTypeFontParameter);

        freeTypeFontParameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        // freeTypeFontParameter.incremental = true;
        freeTypeFontParameter.size = Math.round(minSize*0.06f);
        // freeTypeFontParameter.borderColor = Color.WHITE;
        freeTypeFontParameter.color = Color.BLACK;
        // freeTypeFontParameter.borderWidth = 2f;
        bitmapFontMedium = freeTypeFontGenerator.generateFont(freeTypeFontParameter);

        freeTypeFontParameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        // freeTypeFontParameter.incremental = true;
        freeTypeFontParameter.size = Math.round(minSize*0.04f);
        // freeTypeFontParameter.borderColor = Color.WHITE;
        freeTypeFontParameter.color = Color.BLACK;
        // freeTypeFontParameter.borderWidth = 2f;
        bitmapFontSmall = freeTypeFontGenerator.generateFont(freeTypeFontParameter);

        freeTypeFontParameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        // freeTypeFontParameter.incremental = true;
        freeTypeFontParameter.size = Math.round(minSize*0.025f);
        freeTypeFontParameter.borderColor = Color.WHITE;
        freeTypeFontParameter.color = Color.BLACK;
        freeTypeFontParameter.borderWidth = 2f;
        bitmapFontVerySmall = freeTypeFontGenerator.generateFont(freeTypeFontParameter);

        freeTypeFontParameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        // freeTypeFontParameter.incremental = true;
        freeTypeFontParameter.size = Math.round(minSize*0.04f);
        freeTypeFontParameter.color = Color.WHITE;
        bitmapFontSmallWhite = freeTypeFontGenerator.generateFont(freeTypeFontParameter);
        // PixmapIO.writePNG(Gdx.files.external("bitmap_data.png"), bitmapFont.getRegion().getTexture().getTextureData().consumePixmap())

        freeTypeFontGenerator.dispose();
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

    public BitmapFont getBitmapFontVerySmall() {
        return bitmapFontVerySmall;
    }

    public BitmapFont getBitmapFontSmallWhite() {
        return bitmapFontSmallWhite;
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
