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
 * need six translated lines rather than sixty.
 *
 * <p>The order is drawn afresh each time round, and never shows the same caption twice running:
 * the captions are few and the pictures many - half of them are route flights - so a plain
 * shuffle read "Find the paths to the summit" over and over. Each turn takes a picture from
 * whichever caption has the most left, except the one just shown, which spreads the commonest
 * captions evenly and still varies the pictures within them (see {@link #planOrder}).
 *
 * <p>One picture crosses into the next: the arriving one fades up as the leaving one fades
 * away, its caption with it, so nothing ever cuts. Two textures are held over the moment they
 * overlap and no more - the rest of the set never sits in graphics memory. {@link #update}
 * drives it and must be called on the render thread, {@link #dispose} frees what is in hand.
 */
public final class SlideShow {

    /** Where the pictures and their captions are listed. */
    private static final String SLIDE_LIST = "intro_slides/slides.txt";
    /** Seconds each picture stays, the first of them spent fading in. */
    private static final float SLIDE_SECONDS = 5f;
    private static final float SLIDE_FADE_SECONDS = 0.8f;

    private final Table table = new Table();
    /** The arriving picture and the leaving one, one over the other; likewise their captions. */
    private final Image image = new Image();
    private final Image imageLeaving = new Image();
    private final Label caption;
    private final Label captionLeaving;
    private final float widgetUnitStep;

    private Texture texture;
    private Texture textureLeaving;
    /** The pictures, grouped by the caption they carry; each group shuffled as it is drawn from. */
    private final java.util.Map<String, java.util.List<String>> byCaption =
            new java.util.LinkedHashMap<>();
    /** This round's order: file name and caption key, no two neighbours sharing a caption. */
    private final java.util.List<String[]> order = new java.util.ArrayList<>();
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
        imageLeaving.setScaling(Scaling.fit);
        caption = new Label("", captionStyle);
        caption.setAlignment(Align.center);
        caption.setWrap(true);
        captionLeaving = new Label("", captionStyle);
        captionLeaving.setAlignment(Align.center);
        captionLeaving.setWrap(true);

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
        // Stacked, not swapped: during the crossing both are drawn, the new one over the old.
        com.badlogic.gdx.scenes.scene2d.ui.Stack pictures = new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        pictures.add(imageLeaving);
        pictures.add(image);
        com.badlogic.gdx.scenes.scene2d.ui.Stack captions = new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        captions.add(captionLeaving);
        captions.add(caption);
        table.add(pictures).width(width).height(height).padTop(0.4f * widgetUnitStep).row();
        table.add(captions).width(captionWidth).padTop(0.3f * widgetUnitStep).row();
        table.setVisible(false);

        readSlideList();
    }

    /** The pictures listed in {@link #SLIDE_LIST}, by caption; empty when there are none. */
    private void readSlideList() {
        try {
            com.badlogic.gdx.files.FileHandle list = Gdx.files.internal(SLIDE_LIST);
            if (!list.exists()) {
                return;     // no pictures packaged: the slideshow simply never shows
            }
            for (String line : list.readString("UTF-8").split("\n")) {
                String[] parts = line.trim().split("\t");
                if (parts.length == 2 && !parts[0].isEmpty()) {
                    java.util.List<String> group = byCaption.get(parts[1]);
                    if (group == null) {
                        byCaption.put(parts[1], group = new java.util.ArrayList<>());
                    }
                    group.add(parts[0]);
                }
            }
        } catch (RuntimeException unreadable) {
            byCaption.clear();
        }
        planOrder(null);
    }

    /**
     * Draws the order for one pass through every picture: at each turn the caption with the most
     * pictures still to place, other than the one just placed. That is the classic way to spread
     * repeats as far apart as they can go, and it leaves two of the same caption side by side
     * only if one caption holds more than half of all the pictures - with six captions and sixty
     * pictures it does not.
     *
     * @param after the caption the previous pass ended on, so the passes join cleanly; null at start
     */
    private void planOrder(String after) {
        order.clear();
        order.addAll(plan(byCaption, after));
    }

    /**
     * One pass through every picture, no two neighbours sharing a caption where that is possible.
     * Static and free of any graphics, so it can be tested on its own (TestSlideShowOrder).
     *
     * @param byCaption the pictures of each caption
     * @param after     the caption the previous pass ended on, so passes join cleanly; null at start
     */
    static java.util.List<String[]> plan(java.util.Map<String, java.util.List<String>> byCaption,
                                         String after) {
        java.util.List<String[]> order = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<String>> left = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, java.util.List<String>> group : byCaption.entrySet()) {
            java.util.List<String> pictures = new java.util.ArrayList<>(group.getValue());
            java.util.Collections.shuffle(pictures);   // the pictures within a caption vary too
            left.put(group.getKey(), pictures);
        }
        String previous = after;
        java.util.List<String> tied = new java.util.ArrayList<>();
        while (true) {
            // A caption at random, the one just shown excepted, each weighted by how many of
            // its pictures are left: the commonest come round oftener, as they must to fit, but
            // the run never settles into the same few taking turns. (Drawing strictly the
            // largest each time was tidy and utterly predictable: photo, summit, photo, summit.)
            //
            // Except when one caption holds more than half of everything left. From there on it
            // has to be placed every other turn or it cannot be spread at all, so the choice is
            // forced - the price of the free ones taken earlier, and paid before it is too late.
            int remaining = 0;
            int biggest = 0;
            String biggestKey = null;
            for (java.util.Map.Entry<String, java.util.List<String>> group : left.entrySet()) {
                int size = group.getValue().size();
                remaining += size;
                if (size > biggest) {
                    biggest = size;
                    biggestKey = group.getKey();
                }
            }
            String chosen = null;
            if (biggest * 2 > remaining && !biggestKey.equals(previous)) {
                chosen = biggestKey;
            } else {
                int total = 0;
                tied.clear();
                for (java.util.Map.Entry<String, java.util.List<String>> group : left.entrySet()) {
                    if (group.getValue().isEmpty() || group.getKey().equals(previous)) {
                        continue;
                    }
                    tied.add(group.getKey());
                    total += group.getValue().size();
                }
                if (total > 0) {
                    int ticket = com.badlogic.gdx.math.MathUtils.random(total - 1);
                    for (String key : tied) {
                        ticket -= left.get(key).size();
                        if (ticket < 0) {
                            chosen = key;
                            break;
                        }
                    }
                }
            }
            if (chosen == null) {
                // Only the caption just shown is left: its last pictures follow one another, and
                // nothing can be done about that but show them.
                for (java.util.Map.Entry<String, java.util.List<String>> group : left.entrySet()) {
                    if (!group.getValue().isEmpty()) {
                        chosen = group.getKey();
                        break;
                    }
                }
                if (chosen == null) {
                    return order;     // every picture placed
                }
            }
            java.util.List<String> pictures = left.get(chosen);
            order.add(new String[] {pictures.remove(pictures.size() - 1), chosen});
            previous = chosen;
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
        table.setVisible(show && !order.isEmpty());
        if (!show || order.isEmpty()) {
            return;
        }
        elapsed += delta;
        if (index < 0 || elapsed >= SLIDE_SECONDS) {
            elapsed = 0f;
            if (index + 1 >= order.size()) {
                planOrder(order.get(order.size() - 1)[1]);   // a new pass, different pictures
                index = -1;
            }
            showSlide(index + 1);
        }
        // The two overlap for SLIDE_FADE_SECONDS: one comes up as the other goes down, so the
        // brightness of the pair stays even and neither picture ever blinks out.
        float alpha = Math.min(1f, elapsed / SLIDE_FADE_SECONDS);
        image.getColor().a = alpha;
        imageLeaving.getColor().a = 1f - alpha;
        // The captions hand over rather than overlap: two sentences at half strength on top of
        // each other are unreadable, where the pictures blend happily. The old line is gone by
        // the middle of the crossing, the new one arrives after it.
        caption.getColor().a = Math.max(0f, 2f * alpha - 1f);
        captionLeaving.getColor().a = Math.max(0f, 1f - 2f * alpha);
        if (alpha >= 1f && textureLeaving != null) {
            // Fully covered: the picture that left can go.
            textureLeaving.dispose();
            textureLeaving = null;
            imageLeaving.setDrawable(null);
            captionLeaving.setText("");
        }
    }

    private void showSlide(int next) {
        index = next;
        String[] slide = order.get(next);
        try {
            Texture loaded = new Texture(Gdx.files.internal("intro_slides/" + slide[0]));
            loaded.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            // What was on screen steps back to be faded out; whatever was already leaving has
            // had its turn and is freed now.
            if (textureLeaving != null) {
                textureLeaving.dispose();
            }
            textureLeaving = texture;
            imageLeaving.setDrawable(image.getDrawable());
            captionLeaving.setText(caption.getText());
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
        if (textureLeaving != null) {
            textureLeaving.dispose();
            textureLeaving = null;
        }
    }
}
