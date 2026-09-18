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
 * in the interface's language saying what it shows.
 *
 * <p>Which pictures there are, and what each one shows, is the list in
 * {@code assets/intro_slides/slides.txt}: one line per picture, its file name and the caption it
 * carries ({@code slide_07.jpg<TAB>gpx} -> {@code Intro_slide_gpx}). A list rather than a count,
 * so the pictures can be chosen and reordered without touching this class, and so sixty of them
 * need six translated lines rather than sixty. The order is shuffled at start-up: the list groups
 * pictures of a kind together, and nobody waiting wants four Aconcaguas in a row.
 *
 * <p>One texture at a time: the previous is disposed as the next is loaded, so the whole set
 * never sits in graphics memory. {@link #update} drives it and must be called on the render
 * thread, {@link #dispose} frees the one texture in hand.
 */
public final class SlideShow {

    /** Where the pictures and their captions are listed. */
    private static final String SLIDE_LIST = "intro_slides/slides.txt";
    /** Seconds each picture stays, the first of them spent fading in. */
    private static final float SLIDE_SECONDS = 5f;
    private static final float SLIDE_FADE_SECONDS = 0.8f;

    private final Table table = new Table();
    private final Image image = new Image();
    private final Label caption;
    private final float widgetUnitStep;

    private Texture texture;
    /** The pictures to show, in the order they will be shown: file name and caption key. */
    private final java.util.List<String[]> slides = new java.util.ArrayList<>();
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
                // What is left once the screen's own furniture has its room: the title and the
                // logo above, the caption under the picture, and - on the welcome screen - the
                // terms and the download button along the bottom.
                float byHeight = (screenHeight - 14f * widgetUnitStep) / 0.5625f;
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

        readSlideList();
    }

    /** The pictures listed in {@link #SLIDE_LIST}, shuffled; empty when there are none. */
    private void readSlideList() {
        try {
            com.badlogic.gdx.files.FileHandle list = Gdx.files.internal(SLIDE_LIST);
            if (!list.exists()) {
                return;     // no pictures packaged: the slideshow simply never shows
            }
            for (String line : list.readString("UTF-8").split("\n")) {
                String[] parts = line.trim().split("\t");
                if (parts.length == 2 && !parts[0].isEmpty()) {
                    slides.add(new String[] {parts[0], parts[1]});
                }
            }
            java.util.Collections.shuffle(slides);
        } catch (RuntimeException unreadable) {
            slides.clear();
        }
    }

    public Table getTable() {
        return table;
    }

    /**
     * Shows or hides the slideshow and advances it: the next picture after
     * {@link #SLIDE_SECONDS}, fading in over the first moment of its turn. Render thread.
     */
    public void update(float delta, boolean show) {
        table.setVisible(show && !slides.isEmpty());
        if (!show || slides.isEmpty()) {
            return;
        }
        elapsed += delta;
        if (index < 0 || elapsed >= SLIDE_SECONDS) {
            elapsed = 0f;
            showSlide((index + 1) % slides.size());
        }
        float alpha = Math.min(1f, elapsed / SLIDE_FADE_SECONDS);
        image.getColor().a = alpha;
        caption.getColor().a = alpha;
    }

    private void showSlide(int next) {
        index = next;
        String[] slide = slides.get(next);
        try {
            Texture loaded = new Texture(Gdx.files.internal("intro_slides/" + slide[0]));
            loaded.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            if (texture != null) {
                texture.dispose();
            }
            texture = loaded;
            image.setDrawable(new TextureRegionDrawable(new TextureRegion(loaded)));
            caption.setText(s("Intro_slide_" + slide[1]));
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
