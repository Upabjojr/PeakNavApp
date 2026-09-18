package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
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

        captionBox.clearChildren();
        captionBox.add(title).growX().left().row();
        captionBox.add(detail).growX().left().row();
        Table progressRow = new Table();
        progressRow.add(progress).height(shortSide / 220f).growX();
        progressRow.add(counter).padLeft(widgetUnitStep * 0.4f).right();
        captionBox.add(progressRow).growX().padTop(widgetUnitStep * 0.25f).row();

        Table content = new Table();
        float pad = widgetUnitStep * 0.4f;
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

        // Big arrows down either edge, over everything else: the picture is the slideshow, and
        // a tap anywhere on it moves too, but the arrows say so.
        Table arrows = new Table();
        arrows.setFillParent(true);
        float arrowWidth = Math.max(widgetUnitStep * 1.2f, stageWidth * 0.11f);
        arrows.add(edgeArrow(false, arrowWidth, stageHeight)).left().expandX().fillY();
        arrows.add(edgeArrow(true, arrowWidth, stageHeight)).right().expandX().fillY();
        root.addActor(arrows);

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
        closeRow.add(close).size(widgetUnitStep).pad(widgetUnitStep * 0.35f);
        root.addActor(closeRow);
        showSlide();
    }

    /** One edge arrow: a tall half-transparent strip with a chevron, tapped to move a slide. */
    private Table edgeArrow(final boolean forward, float width, float height) {
        Label chevron = new Label(forward ? ">" : "<", new Label.LabelStyle(font, Color.WHITE));
        chevron.setFontScale(Math.min(width, height) / 3f / font.getLineHeight() * font.getScaleY());
        Table arrow = new Table();
        arrow.setBackground(getC().widgetTextures.getUniformDrawable(new Color(1f, 1f, 1f, 0.12f)));
        arrow.add(chevron);
        arrow.setTouchable(Touchable.enabled);
        arrow.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                event.stop();   // the root's own tap would move as well, and twice is once too many
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

    /** A ring, drawn once: a circle a few pixels thick that the slides stretch over a widget. */
    private TextureRegion ring() {
        if (ringTexture == null) {
            int size = 128;
            Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);
            pixmap.setBlending(Pixmap.Blending.None);
            pixmap.setColor(0, 0, 0, 0);
            pixmap.fill();
            pixmap.setColor(0.18f, 0.8f, 0.44f, 1f);
            for (int r = size / 2 - 5; r < size / 2 - 1; r++) {
                pixmap.drawCircle(size / 2, size / 2, r);
            }
            ringTexture = new Texture(pixmap);
            ringTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap.dispose();
        }
        return new TextureRegion(ringTexture);
    }

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
        private float[] marker;

        SlideView() {
            picture.setScaling(Scaling.fit);
            ring.setDrawable(new TextureRegionDrawable(ring()));
            ring.setVisible(false);
            ring.setTouchable(Touchable.disabled);
            picture.setTouchable(Touchable.disabled);
            addActor(picture);
            addActor(ring);
        }

        void set(TextureRegion region, float[] marker) {
            this.marker = marker;
            picture.setDrawable(region == null ? null : new TextureRegionDrawable(region));
            ring.setVisible(marker != null);
            ring.clearActions();
            if (marker != null) {
                // A gentle pulse, as the page's ring had.
                ring.addAction(Actions.forever(Actions.sequence(
                        Actions.scaleTo(0.86f, 0.86f, 0.6f), Actions.scaleTo(1f, 1f, 0.6f))));
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
}
