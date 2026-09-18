package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;

/**
 * Pictures of the app, shown while there is nothing else to look at.
 *
 * <p>A first download takes minutes, and the screen waiting for it - the welcome screen, or the
 * map before any tile has arrived - was a logo and a progress ring. These are the app's own
 * renders (assets/intro_slides), one every few seconds, fading into each other, each with a line
 * in the interface's language saying what it shows (Intro_slide_1..N).
 *
 * <p>One texture at a time: the previous is disposed as the next is loaded, so the whole set
 * never sits in graphics memory. {@link #update} drives it and must be called on the render
 * thread, {@link #dispose} frees the one texture in hand.
 */
public final class SlideShow {

    /** How many pictures there are in assets/intro_slides. */
    private static final int SLIDE_COUNT = 6;
    /** Seconds each picture stays, the first of them spent fading in. */
    private static final float SLIDE_SECONDS = 5f;
    private static final float SLIDE_FADE_SECONDS = 0.8f;

    private final Table table = new Table();
    private final Image image = new Image();
    private final Label caption;
    private final float widgetUnitStep;

    private Texture texture;
    /** Which picture is on screen, 0-based; -1 before the first one is loaded. */
    private int index = -1;
    private float elapsed = 0f;

    /**
     * @param widgetUnitStep the interface's unit, a button's width
     * @param captionStyle   the style of the line under the picture
     */
    public SlideShow(float widgetUnitStep, Label.LabelStyle captionStyle) {
        this.widgetUnitStep = widgetUnitStep;
        image.setScaling(Scaling.fit);
        caption = new Label("", captionStyle);
        caption.setAlignment(Align.center);
        caption.setWrap(true);

        // Sized from the screen, not from a fixed number of button widths: the pictures are the
        // point of a screen that is waiting, and on a tablet a button-sized picture is lost in
        // the middle of it. They are 16:9, as wide as the screen allows and never so tall that
        // the caption under them runs off it.
        Value width = new Value() {
            @Override
            public float get(Actor context) {
                float screenWidth = context != null && context.getStage() != null
                        ? context.getStage().getWidth() : 10f * widgetUnitStep;
                float screenHeight = context != null && context.getStage() != null
                        ? context.getStage().getHeight() : 10f * widgetUnitStep;
                float byWidth = 0.86f * screenWidth;
                float byHeight = (screenHeight - 7.5f * widgetUnitStep) / 0.5625f;
                return Math.max(4f * widgetUnitStep, Math.min(byWidth, byHeight));
            }
        };
        Value height = new Value() {
            @Override
            public float get(Actor context) {
                return width.get(context) * 0.5625f;    // the pictures' own 16:9
            }
        };
        Value captionWidth = new Value() {
            @Override
            public float get(Actor context) {
                return width.get(context);
            }
        };
        table.add(image).width(width).height(height).padTop(0.4f * widgetUnitStep).row();
        table.add(caption).width(captionWidth).padTop(0.3f * widgetUnitStep).row();
        table.setVisible(false);
    }

    public Table getTable() {
        return table;
    }

    /**
     * Shows or hides the slideshow and advances it: the next picture after
     * {@link #SLIDE_SECONDS}, fading in over the first moment of its turn. Render thread.
     */
    public void update(float delta, boolean show) {
        table.setVisible(show);
        if (!show) {
            return;
        }
        elapsed += delta;
        if (index < 0 || elapsed >= SLIDE_SECONDS) {
            elapsed = 0f;
            showSlide((index + 1) % SLIDE_COUNT);
        }
        float alpha = Math.min(1f, elapsed / SLIDE_FADE_SECONDS);
        image.getColor().a = alpha;
        caption.getColor().a = alpha;
    }

    private void showSlide(int next) {
        index = next;
        try {
            Texture loaded = new Texture(Gdx.files.internal("intro_slides/slide_" + (next + 1) + ".jpg"));
            loaded.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            if (texture != null) {
                texture.dispose();
            }
            texture = loaded;
            image.setDrawable(new TextureRegionDrawable(new TextureRegion(loaded)));
            caption.setText(s("Intro_slide_" + (next + 1)));
        } catch (RuntimeException missing) {
            // A picture that will not load is not worth a blank space, let alone a crash:
            // the slideshow simply stops where it is.
            table.setVisible(false);
        }
    }

    public void dispose() {
        if (texture != null) {
            texture.dispose();
            texture = null;
            index = -1;
        }
    }
}
