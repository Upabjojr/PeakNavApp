package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.Scaling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "?" tutorial: a slideshow of pictures of the app, each with a ring on the button it is
 * about and a caption under or beside it.
 *
 * <p>Drawn by the app itself, like every other panel. It used to be an HTML page in a web view,
 * which meant three ways of handing the pictures to three web views and a different failure on
 * each platform - the pictures built into the page cost Android a kill for memory, and on iOS the
 * page was loaded before its view was on screen, where WebKit holds back the scripts that build
 * the whole slideshow. None of that exists here.
 *
 * <p>The slides come from {@code assets/info/tutorial_slides.json}, written by
 * {@code tools/tutorial_slides.py} from screenshots of the running app and the places the app
 * itself reports its widgets to be, so a ring follows its button. The captions are the app's own
 * translations ({@link com.peaknav.viewer.TutorialStrings}).
 */
public class TutorialOverlay implements Disposable {

    /** Caption text height as a fraction of the stage's short side. */
    private static final float TITLE_FRACTION = 1f / 22f;
    private static final float DETAIL_FRACTION = 1f / 32f;
    /** Pictures kept decoded: the one on screen and its neighbours. */
    private static final int TEXTURES_KEPT = 3;

    /** One slide: the picture, the caption's key, and where the ring goes (null for none). */
    private static final class Slide {
        final String image;
        final String key;
        final float[] marker;   // cx, cy, rw, rh as fractions of the picture, or null

        Slide(String image, String key, float[] marker) {
            this.image = image;
            this.key = key;
            this.marker = marker;
        }
    }

    private final List<Slide> slides = new ArrayList<>();
    private final Map<String, TextureRegion> pictures = new LinkedHashMap<>();
    private final Table root;
    private final Table captionBox;
    private final SlideView slideView;
    private final Label title;
    private final Label detail;
    private final Label counter;
    private final ProgressBar progress;
    private final BitmapFont font;
    private final float widgetUnitStep;
    private Texture ringTexture;
    private Texture spotlightTexture;
    /** The two edge buttons, kept so the one with nowhere to go can be hidden. */
    private Table backButton, forwardButton;
    /** The close button in the corner; see {@link #onOwnButton}. */
    private Actor closeButton;
    private int index;
    private boolean landscapeLayout;

    public TutorialOverlay(float widgetUnitStep) {
        this.widgetUnitStep = widgetUnitStep;
        font = getC().styleSingleton.getBitmapFont();
        readSlides();

        title = new Label("", new Label.LabelStyle(font, Color.WHITE));
        title.setWrap(true);
        detail = new Label("", new Label.LabelStyle(font, new Color(0.78f, 0.82f, 0.88f, 1f)));
        detail.setWrap(true);
        counter = new Label("", new Label.LabelStyle(font, new Color(0.7f, 0.74f, 0.8f, 1f)));

        progress = new ProgressBar();

        slideView = new SlideView();

        captionBox = new Table();
        captionBox.defaults().padBottom(widgetUnitStep * 0.15f);

        root = new Table();
        root.setFillParent(true);
        root.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0.06f, 0.08f, 0.1f, 0.96f)));
        root.setTouchable(Touchable.enabled);
        root.setVisible(false);

        // A tap on the right half goes on, on the left half back - the way a phone's photo
        // viewer moves - and the close button is on top of it.
        root.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (onOwnButton(event.getTarget())) {
                    return;   // the button has moved the slideshow itself, or closed it
                }
                goTo(index + (x > root.getWidth() / 2 ? 1 : -1));
            }
        });
        root.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.LEFT) {
                    goTo(index - 1);
                } else if (keycode == Input.Keys.RIGHT || keycode == Input.Keys.SPACE) {
                    goTo(index + 1);
                } else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    hide();
                } else {
                    return false;
                }
                return true;
            }
        });
    }

    /** The slides as the tool wrote them; an empty list when the file is missing or broken. */
    private void readSlides() {
        try {
            FileHandle file = Gdx.files.internal("info/tutorial_slides.json");
            if (!file.exists()) {
                Gdx.app.error("PeakNav", "no tutorial slides");
                return;
            }
            for (JsonValue entry = new JsonReader().parse(file).child; entry != null; entry = entry.next) {
                JsonValue marker = entry.get("marker");
                slides.add(new Slide(entry.getString("image"), entry.getString("key"),
                        marker == null || marker.isNull() ? null : new float[]{
                                marker.getFloat("cx"), marker.getFloat("cy"),
                                marker.getFloat("rw"), marker.getFloat("rh")}));
            }
        } catch (RuntimeException broken) {
            Gdx.app.error("PeakNav", "tutorial slides: " + broken);
        }
    }

    public Table getRoot() {
        return root;
    }

    public boolean isVisible() {
        return root.isVisible();
    }

    /** Opens the tutorial at its first slide. Does nothing when there are no slides to show. */
    public void show() {
        if (slides.isEmpty()) {
            return;
        }
        index = 0;
        root.setVisible(true);
        root.toFront();
        if (root.getStage() != null) {
            root.getStage().setKeyboardFocus(root);
        }
        layout();
        showSlide();
    }

    public void hide() {
        root.setVisible(false);
        if (root.getStage() != null && root.getStage().getKeyboardFocus() == root) {
            root.getStage().setKeyboardFocus(null);
        }
        // The pictures are worth a few megabytes each: keep none once the tutorial is shut.
        disposePictures();
    }

    private void goTo(int wanted) {
        int next = Math.max(0, Math.min(slides.size() - 1, wanted));
        if (next != index) {
            index = next;
            showSlide();
        }
    }

    /** Rebuilds the panel for the stage's shape; called on show and on every resize. */
    public void layout() {
        if (root.getStage() == null || slides.isEmpty()) {
            return;
        }
        float stageWidth = root.getStage().getWidth();
        float stageHeight = root.getStage().getHeight();
        boolean landscape = stageWidth > stageHeight;
        float shortSide = Math.min(stageWidth, stageHeight);
        float base = font.getScaleY();   // the shared font's own scale; see KeyboardHelpOverlay
        title.setFontScale(shortSide * TITLE_FRACTION / font.getLineHeight() * base);
        detail.setFontScale(shortSide * DETAIL_FRACTION / font.getLineHeight() * base);
        counter.setFontScale(shortSide * DETAIL_FRACTION * 0.85f / font.getLineHeight() * base);

        // The caption's two rows are as tall as the tallest of any slide's, measured at the width
        // they will have: sized to each slide's own text instead, the caption grew and shrank from
        // one slide to the next and the picture above it wobbled with it. Measured here, in the
        // language the app speaks, so every language gets the room its longest caption needs.
        float pad = widgetUnitStep * 0.4f;
        float captionWidth = landscape
                ? Math.min(stageWidth * 0.38f, shortSide * 1.1f)
                : stageWidth - widgetUnitStep;
        float titleHeight = 0, detailHeight = 0;
        for (Slide slide : slides) {
            titleHeight = Math.max(titleHeight, wrappedHeight(title, s(slide.key), captionWidth));
            detailHeight = Math.max(detailHeight, wrappedHeight(detail, s(slide.key + "_detail"), captionWidth));
        }
        title.setAlignment(Align.topLeft);
        detail.setAlignment(Align.topLeft);

        captionBox.clearChildren();
        captionBox.add(title).growX().left().height(titleHeight).row();
        captionBox.add(detail).growX().left().height(detailHeight).row();
        Table progressRow = new Table();
        progressRow.add(progress).height(shortSide / 220f).growX();
        progressRow.add(counter).padLeft(widgetUnitStep * 0.4f).right();
        captionBox.add(progressRow).growX().padTop(widgetUnitStep * 0.25f).row();

        Table content = new Table();
        if (landscape) {
            // Wide window: the picture keeps the height, the caption sits beside it.
            content.add(slideView).expand().fill().pad(pad);
            content.add(captionBox).width(Math.min(stageWidth * 0.38f, shortSide * 1.1f))
                    .padRight(widgetUnitStep * 0.8f).center();
        } else {
            content.add(slideView).expand().fill().pad(pad).row();
            content.add(captionBox).growX().pad(0, widgetUnitStep * 0.5f, widgetUnitStep * 0.5f,
                    widgetUnitStep * 0.5f);
        }
        landscapeLayout = landscape;

        // clearChildren, not clear: Actor.clear also drops the listeners, which is how an
        // earlier version lost every tap and key press the moment it was laid out.
        root.clearChildren();
        root.add(content).grow();

        // A square button at either edge, over everything else: the picture is the slideshow, and
        // a tap anywhere on it moves too, but the buttons say so.
        Table arrows = new Table();
        arrows.setFillParent(true);
        // A thumb's width, whatever the screen: a sixth of the short side, and never less than two
        // widget units, so it is as easy to hit on a tablet as on a phone.
        float arrowWidth = Math.max(widgetUnitStep * 2f, shortSide * 0.17f);
        // Square, and a third of the way up the screen: under the thumb whether the picture is
        // tall or wide, and clear of the caption at the foot.
        float arrowBottom = stageHeight / 3f - arrowWidth / 2f;
        arrows.bottom();
        backButton = edgeArrow(false, arrowWidth);
        forwardButton = edgeArrow(true, arrowWidth);
        arrows.add(backButton).left().size(arrowWidth)
                .padBottom(arrowBottom).expandX().left();
        arrows.add(forwardButton).right().size(arrowWidth)
                .padBottom(arrowBottom).expandX().right();
        root.addActor(arrows);
        arrows.toFront();   // over the picture and the caption, or they cannot be tapped

        // The close button, over the corner of everything else.
        Table closeRow = new Table();
        closeRow.setFillParent(true);
        closeRow.top().right();
        com.badlogic.gdx.scenes.scene2d.ui.Button close =
                getC().widgetTextures.getButtonWithIcon("icons/icon_x.png");
        close.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                event.stop();
                hide();
            }
        });
        closeButton = close;
        closeRow.add(close).size(Math.max(widgetUnitStep, shortSide * 0.09f)).pad(widgetUnitStep * 0.35f);
        root.addActor(closeRow);
        closeRow.toFront();   // and the close button over the arrows
        showSlide();
    }

    /**
     * Whether a tap landed on one of the buttons laid over the slideshow, which answer it
     * themselves.
     *
     * <p>Without this every tap on an edge arrow moved two slides and the tutorial showed only
     * every other one. The arrows' {@code event.stop()} was meant to keep the tap from this
     * listener too, and does not: a stage hands a touch-up to each listener that took the
     * touch-down, one after another (Stage.touchUp walks its touch focuses), and stopping the
     * event only ends its bubbling, not that walk. The forward arrow sits on the right half of
     * the screen, so this listener moved forward as well; the back arrow, on the left, back.
     */
    private boolean onOwnButton(Actor target) {
        return target != null
                && ((backButton != null && target.isDescendantOf(backButton))
                        || (forwardButton != null && target.isDescendantOf(forwardButton))
                        || (closeButton != null && target.isDescendantOf(closeButton)));
    }

    /** How tall {@code text} is in {@code like}'s style and scale, wrapped at {@code width}. */
    private static float wrappedHeight(Label like, String text, float width) {
        Label probe = new Label(text, like.getStyle());
        probe.setFontScale(like.getFontScaleX(), like.getFontScaleY());
        probe.setWrap(true);
        probe.setWidth(width);
        return probe.getPrefHeight();
    }

    /** One edge arrow: a square half-transparent button with a chevron, tapped to move a slide. */
    private Table edgeArrow(final boolean forward, float width) {
        Label chevron = new Label(forward ? ">" : "<", new Label.LabelStyle(font, Color.WHITE));
        chevron.setFontScale(width / 1.6f / font.getLineHeight() * font.getScaleY());
        Table arrow = new Table();
        // Dark, not white: a white panel at any alpha the picture can be read through
        // disappears into a sunlit photograph, which is most of these pictures.
        arrow.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0.05f, 0.07f, 0.09f, 0.55f)));
        arrow.add(chevron);   // the button is square, so the chevron sits in the middle of it
        arrow.setTouchable(Touchable.enabled);
        arrow.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                // The root's own tap listener gets this tap too, whatever stop() says; it
                // leaves it alone (onOwnButton).
                goTo(index + (forward ? 1 : -1));
            }
        });
        return arrow;
    }

    private void showSlide() {
        if (slides.isEmpty()) {
            return;
        }
        Slide slide = slides.get(index);
        slideView.set(picture(slide.image), slide.marker);
        title.setText(s(slide.key));
        detail.setText(s(slide.key + "_detail"));
        counter.setText((index + 1) + " / " + slides.size());
        progress.setFraction((index + 1) / (float) slides.size());
        // Nothing before the first slide and nothing after the last: hide the button
        // rather than leave one that does nothing when it is pressed.
        if (backButton != null) {
            backButton.setVisible(index > 0);
        }
        if (forwardButton != null) {
            forwardButton.setVisible(index < slides.size() - 1);
        }
        forgetDistantPictures();
    }

    /**
     * The picture, decoded on first use.
     *
     * <p>No mipmaps: a screenshot's sides are not powers of two, and GL ES 2 does not sample such
     * a texture with them. Clamped and linear, which it does.
     */
    private TextureRegion picture(String name) {
        TextureRegion region = pictures.get(name);
        if (region == null) {
            FileHandle file = Gdx.files.internal("info/" + name);
            if (!file.exists()) {
                Gdx.app.error("PeakNav", "no tutorial picture " + name);
                return null;
            }
            Texture texture = new Texture(file);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            region = new TextureRegion(texture);
            pictures.put(name, region);
        }
        return region;
    }

    /** Keeps only the pictures around the slide on screen: each is several megabytes decoded. */
    private void forgetDistantPictures() {
        if (pictures.size() <= TEXTURES_KEPT) {
            return;
        }
        List<String> wanted = new ArrayList<>();
        for (int i = Math.max(0, index - 1); i <= Math.min(slides.size() - 1, index + 1); i++) {
            wanted.add(slides.get(i).image);
        }
        for (String name : new ArrayList<>(pictures.keySet())) {
            if (!wanted.contains(name)) {
                pictures.remove(name).getTexture().dispose();
            }
        }
    }

    /**
     * A ring, drawn once: a thick bright circle the slides stretch over a widget. Thick and
     * white-cored on purpose - a thin line was lost against a photograph of rock and snow.
     */
    private TextureRegion ring() {
        if (ringTexture == null) {
            int size = 256;
            int centre = size / 2;
            Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);
            pixmap.setBlending(Pixmap.Blending.None);
            pixmap.setColor(0, 0, 0, 0);
            pixmap.fill();
            // A dark edge either side, so the ring shows on snow as well as on rock.
            pixmap.setColor(0f, 0f, 0f, 0.55f);
            for (int r = centre - 22; r < centre - 2; r++) {
                pixmap.drawCircle(centre, centre, r);
            }
            pixmap.setColor(0.24f, 1f, 0.55f, 1f);
            for (int r = centre - 19; r < centre - 5; r++) {
                pixmap.drawCircle(centre, centre, r);
            }
            pixmap.setColor(1f, 1f, 1f, 0.95f);
            for (int r = centre - 14; r < centre - 10; r++) {
                pixmap.drawCircle(centre, centre, r);
            }
            ringTexture = new Texture(pixmap);
            ringTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap.dispose();
        }
        return new TextureRegion(ringTexture);
    }

    /**
     * The dimming around a ring: clear in the middle, darkening to solid towards the edges and
     * corners, which are solid so the same texture also fills the rest of the picture.
     */
    private Texture spotlight() {
        if (spotlightTexture == null) {
            int size = 128;
            float centre = size / 2f;
            Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);
            pixmap.setBlending(Pixmap.Blending.None);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float d = (float) Math.hypot(x + 0.5f - centre, y + 0.5f - centre) / centre;
                    float t = Math.max(0f, Math.min(1f, (d - SPOTLIGHT_CLEAR) / (1f - SPOTLIGHT_CLEAR)));
                    pixmap.setColor(0f, 0f, 0f, t * t * (3 - 2 * t));
                    pixmap.drawPixel(x, y);
                }
            }
            spotlightTexture = new Texture(pixmap);
            spotlightTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap.dispose();
        }
        return spotlightTexture;
    }

    /** The clear part of the spotlight, as a fraction of its radius. */
    private static final float SPOTLIGHT_CLEAR = 0.5f;
    /** The spotlight's size against the ring's: its clear part a little wider than the ring. */
    private static final float SPOTLIGHT_SCALE = 2.6f;
    /** How dark the picture gets away from the ring. */
    private static final float SPOTLIGHT_DIM = 0.55f;

    private void disposePictures() {
        for (TextureRegion region : pictures.values()) {
            region.getTexture().dispose();
        }
        pictures.clear();
    }

    @Override
    public void dispose() {
        disposePictures();
        if (ringTexture != null) {
            ringTexture.dispose();
            ringTexture = null;
        }
        if (spotlightTexture != null) {
            spotlightTexture.dispose();
            spotlightTexture = null;
        }
    }

    /** How far through the slides: a track with the part read so far filled in. */
    private final class ProgressBar extends com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup {

        private final Image track = new Image(getC().widgetTextures.getUniformDrawable(
                new Color(0.28f, 0.32f, 0.38f, 1f)));
        private final Image fill = new Image(getC().widgetTextures.getUniformDrawable(
                new Color(0.18f, 0.8f, 0.44f, 1f)));
        private float fraction;

        ProgressBar() {
            setTouchable(Touchable.disabled);
            addActor(track);
            addActor(fill);
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        public void layout() {
            track.setBounds(0, 0, getWidth(), getHeight());
            fill.setBounds(0, 0, getWidth() * fraction, getHeight());
        }

        @Override
        public float getPrefWidth() {
            return 0;
        }

        @Override
        public float getPrefHeight() {
            return 0;
        }
    }

    /**
     * The picture with its ring: the picture is fitted into the space given, and the ring is
     * placed in fractions of the picture as drawn, so it lands on its widget at any size.
     */
    private final class SlideView extends com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup {

        private final Image picture = new Image();
        private final Image ring = new Image();
        /** Rings that swell and fade out of it one after the other, so the eye is caught by movement. */
        private final Image[] ripples = {new Image(), new Image()};
        /** The rest of the picture dimmed, so the ringed spot is the bright one. */
        private final Spotlight spotlight = new Spotlight();
        private float[] marker;

        SlideView() {
            picture.setScaling(Scaling.fit);
            picture.setTouchable(Touchable.disabled);
            addActor(picture);
            addActor(spotlight);
            for (Image ripple : ripples) {
                ripple.setDrawable(new TextureRegionDrawable(ring()));
                ripple.setVisible(false);
                ripple.setTouchable(Touchable.disabled);
                addActor(ripple);
            }
            ring.setDrawable(new TextureRegionDrawable(ring()));
            ring.setVisible(false);
            ring.setTouchable(Touchable.disabled);
            addActor(ring);
        }

        void set(TextureRegion region, float[] marker) {
            this.marker = marker;
            picture.setDrawable(region == null ? null : new TextureRegionDrawable(region));
            ring.setVisible(marker != null);
            spotlight.setVisible(marker != null);
            ring.clearActions();
            spotlight.clearActions();
            for (Image ripple : ripples) {
                ripple.setVisible(marker != null);
                ripple.clearActions();
                ripple.getColor().a = 0f;
            }
            if (marker != null) {
                // The picture dims around the spot...
                spotlight.getColor().a = 0f;
                spotlight.addAction(Actions.alpha(1f, 0.5f));
                // ...the ring swoops in onto it from large, then breathes and brightens...
                ring.getColor().a = 0f;
                ring.setScale(3f);
                ring.addAction(Actions.sequence(
                        Actions.parallel(Actions.scaleTo(1f, 1f, 0.55f, Interpolation.swingOut),
                                Actions.fadeIn(0.3f)),
                        Actions.forever(Actions.parallel(
                                Actions.sequence(Actions.scaleTo(0.85f, 0.85f, 0.45f, Interpolation.sine),
                                        Actions.scaleTo(1.08f, 1.08f, 0.45f, Interpolation.sine)),
                                Actions.sequence(Actions.alpha(0.6f, 0.45f), Actions.alpha(1f, 0.45f))))));
                // ...and rings swell out of it and fade, one after the other.
                for (int i = 0; i < ripples.length; i++) {
                    ripples[i].addAction(Actions.sequence(
                            Actions.delay(0.55f + 0.7f * i),
                            Actions.forever(Actions.sequence(
                                    Actions.parallel(Actions.scaleTo(1f, 1f), Actions.alpha(0.9f)),
                                    Actions.parallel(Actions.scaleTo(2.2f, 2.2f, 1.4f, Interpolation.pow2Out),
                                            Actions.alpha(0f, 1.4f))))));
                }
            }
            invalidate();
        }

        @Override
        public void layout() {
            picture.setBounds(0, 0, getWidth(), getHeight());
            if (marker == null || picture.getDrawable() == null) {
                return;
            }
            // Where the picture actually lands inside this box, once fitted.
            float pictureWidth = picture.getDrawable().getMinWidth();
            float pictureHeight = picture.getDrawable().getMinHeight();
            float scale = Math.min(getWidth() / pictureWidth, getHeight() / pictureHeight);
            float drawnWidth = pictureWidth * scale;
            float drawnHeight = pictureHeight * scale;
            float left = (getWidth() - drawnWidth) / 2;
            float bottom = (getHeight() - drawnHeight) / 2;
            // The marker's y runs down the picture, scene2d's up.
            float centreX = left + marker[0] * drawnWidth;
            float centreY = bottom + (1 - marker[1]) * drawnHeight;
            float width = 2 * marker[2] * drawnWidth;
            float height = 2 * marker[3] * drawnHeight;
            ring.setBounds(centreX - width / 2, centreY - height / 2, width, height);
            ring.setOrigin(Align.center);
            for (Image ripple : ripples) {
                ripple.setBounds(ring.getX(), ring.getY(), width, height);
                ripple.setOrigin(Align.center);
            }
            spotlight.setBounds(left, bottom, drawnWidth, drawnHeight);
            spotlight.hole(centreX - left, centreY - bottom,
                    SPOTLIGHT_SCALE * width, SPOTLIGHT_SCALE * height);
        }

        @Override
        public float getPrefWidth() {
            return 0;   // takes whatever the table gives it
        }

        @Override
        public float getPrefHeight() {
            return 0;
        }
    }

    /**
     * The picture darkened everywhere but around the ring: the spotlight texture over the ring,
     * and its solid corner stretched over the rest, all clipped to the picture.
     */
    private final class Spotlight extends Actor {
        private final TextureRegion hole = new TextureRegion();
        private final TextureRegion solid = new TextureRegion();
        private float holeX, holeY, holeWidth, holeHeight;

        Spotlight() {
            setTouchable(Touchable.disabled);
            setVisible(false);
        }

        /** The spotlight's centre and full size, in this actor's own coordinates. */
        void hole(float centreX, float centreY, float width, float height) {
            holeX = centreX - width / 2;
            holeY = centreY - height / 2;
            holeWidth = width;
            holeHeight = height;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            Texture texture = spotlight();
            hole.setRegion(texture);
            solid.setTexture(texture);
            solid.setRegion(0, 0, 1, 1);
            // In the batch's coordinates, which are the parent's while it draws its children.
            if (!clipBegin(getX(), getY(), getWidth(), getHeight())) {
                return;
            }
            Color was = batch.getColor().cpy();
            batch.setColor(1f, 1f, 1f, SPOTLIGHT_DIM * getColor().a * parentAlpha);
            float x = getX(), y = getY(), w = getWidth(), h = getHeight();
            float top = holeY + holeHeight, right = holeX + holeWidth;
            batch.draw(hole, x + holeX, y + holeY, holeWidth, holeHeight);
            if (top < h) {
                batch.draw(solid, x, y + top, w, h - top);
            }
            if (holeY > 0) {
                batch.draw(solid, x, y, w, holeY);
            }
            float from = Math.max(holeY, 0), to = Math.min(top, h);
            if (to > from) {
                if (holeX > 0) {
                    batch.draw(solid, x, y + from, holeX, to - from);
                }
                if (right < w) {
                    batch.draw(solid, x + right, y + from, w - right, to - from);
                }
            }
            batch.flush();
            clipEnd();
            batch.setColor(was);
        }
    }
}
