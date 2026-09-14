package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.peaknav.gpx.GpxTrack;
import com.peaknav.gpx.GpxTrackStats;
import com.peaknav.utils.PreferencesManager.UnitSystem;

import java.util.List;

/**
 * A small pane at the left side of the map while a GPX track is loaded: a row of two buttons -
 * one folds the pane down to that single button, the other makes it large and back - and under
 * them the track's name, how long it is, how much it climbs and drops, the walking time, and its
 * altimetric profile, with a dot on the profile where a running tour has got to.
 *
 * <p>Built from scene2d widgets so it follows the stage's layout and scale; the profile is drawn
 * into a texture once per track (or change of units), not every frame.
 */
public class GpxInfoPane {

    private static final Color PANEL = new Color(0.03f, 0.08f, 0.14f, 0.72f);
    private static final Color PROFILE_FILL = new Color(0.10f, 0.45f, 0.90f, 0.55f);
    private static final Color PROFILE_LINE = new Color(0.62f, 0.83f, 1f, 1f);
    /** The small pane's width, in widget units, where the screen has room for it. */
    private static final float PANE_UNITS = 4.6f;
    private static final float PAD_LEFT_UNITS = 1.5f;
    private static final float PAD_TOP_UNITS = 1.2f;
    private static final float PANEL_PAD_UNITS = 0.12f;
    /** Size of the buttons in the open pane's top row. */
    private static final float ROW_BUTTON_UNITS = 0.7f;
    private static final String ICON_FOLD = "icons/icon_pane_fold.png";
    private static final String ICON_OPEN = "icons/icon_gpx_info.png";
    private static final String ICON_MAXIMIZE = "icons/icon_pane_maximize.png";
    private static final String ICON_RESTORE = "icons/icon_pane_restore.png";
    private static final int PROFILE_WIDTH = 512;
    private static final int PROFILE_HEIGHT = 128;

    private final Table root = new Table();
    private final Table panel = new Table();
    private final Table buttons = new Table();
    private final Table body = new Table();
    private final Drawable panelBackground;
    private final Button foldButton;
    private final Button sizeButton;
    private final Label name;
    private final Label distance;
    private final Label time;
    private final Label climb;
    private final Label heights;
    private final Image profile = new Image();
    private final Image dot;
    private final WidgetGroup profileGroup = new WidgetGroup();
    private final float widgetUnitStep;

    private Texture profileTexture;
    private int shownVersion = -1;
    private UnitSystem shownUnits;
    private boolean open = true;
    private boolean maximized = false;
    private GpxTrackStats stats;
    /** The body's width and the profile's height as last laid out, stage units. */
    private float width, profileHeight;

    public GpxInfoPane(float widgetUnitStep) {
        this.widgetUnitStep = widgetUnitStep;
        root.setFillParent(true);
        // On the left, beside the column of zoom and gallery buttons, so the middle of the map stays clear.
        root.top().left().padTop(PAD_TOP_UNITS * widgetUnitStep).padLeft(PAD_LEFT_UNITS * widgetUnitStep);
        root.setVisible(false);

        panelBackground = getC().widgetTextures.getUniformDrawable(PANEL);

        foldButton = getC().widgetTextures.getButtonWithIcon(ICON_FOLD, null);
        foldButton.setName("gpx_info_fold");
        foldButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                setOpen(!open);
            }
        });
        sizeButton = getC().widgetTextures.getButtonWithIcon(ICON_MAXIMIZE, null);
        sizeButton.setName("gpx_info_size");
        sizeButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                setMaximized(!maximized);
            }
        });

        // White-baked glyphs, smaller than the buttons' text, so the figures leave the map in view.
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = getC().styleSingleton.getBitmapFontVerySmallWhite();
        style.fontColor = Color.WHITE;
        name = label(style);
        distance = label(style);
        time = label(style);
        climb = label(style);
        heights = label(style);

        Pixmap dotPixmap = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
        dotPixmap.setColor(0.03f, 0.12f, 0.28f, 1f);
        dotPixmap.fillCircle(16, 16, 15);
        dotPixmap.setColor(Color.WHITE);
        dotPixmap.fillCircle(16, 16, 12);
        dotPixmap.setColor(0.10f, 0.45f, 0.90f, 1f);
        dotPixmap.fillCircle(16, 16, 8);
        dot = new Image(new TextureRegionDrawable(new Texture(dotPixmap)));
        dotPixmap.dispose();
        dot.setVisible(false);
        profileGroup.addActor(profile);
        profileGroup.addActor(dot);

        width = PANE_UNITS * widgetUnitStep;
        profileHeight = 1.3f * widgetUnitStep;
        layoutPanel();
        root.add(panel);
    }

    private static Label label(Label.LabelStyle style) {
        Label label = new Label("", style);
        label.setAlignment(Align.left);
        label.setWrap(true);
        return label;
    }

    public Table getTable() {
        return root;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isMaximized() {
        return maximized;
    }

    public void setOpen(boolean value) {
        open = value;
        layoutPanel();
    }

    /** Large (most of the screen, a tall profile) or back to the small pane. Opens a folded pane. */
    public void setMaximized(boolean value) {
        maximized = value;
        open = true;
        width = targetWidth();
        profileHeight = targetProfileHeight();
        layoutPanel();
    }

    /**
     * Open: the two buttons over the body, on the panel's background. Folded: the fold button
     * alone, the size of the map's other buttons, with no panel around it. The rows are laid out
     * afresh, with all their settings, every time - squeezing a folded cell instead left the text
     * misplaced on reopening.
     */
    private void layoutPanel() {
        float u = widgetUnitStep;
        setIcon(foldButton, open ? ICON_FOLD : ICON_OPEN);
        setIcon(sizeButton, maximized ? ICON_RESTORE : ICON_MAXIMIZE);
        panel.clearChildren();
        buttons.clearChildren();
        body.clearChildren();
        if (!open) {
            panel.setBackground((Drawable) null);
            panel.pad(0);
            panel.add(foldButton).size(u);
            panel.invalidateHierarchy();
            return;
        }
        panel.setBackground(panelBackground);
        panel.pad(PANEL_PAD_UNITS * u);
        buttons.add(foldButton).size(ROW_BUTTON_UNITS * u).left();
        buttons.add().expandX();
        buttons.add(sizeButton).size(ROW_BUTTON_UNITS * u).right();

        body.defaults().left().width(width);
        body.add(name).padTop(0.06f * u).row();
        body.add(distance).row();
        body.add(time).row();
        body.add(climb).row();
        body.add(heights).padBottom(0.08f * u).row();
        body.add(profileGroup).height(profileHeight).row();

        panel.add(buttons).width(width).row();
        panel.add(body).width(width);
        panel.invalidateHierarchy();
    }

    private static void setIcon(Button button, String icon) {
        button.getStyle().up = getC().widgetTextures.getTextureRegionDrawable(icon);
    }

    /**
     * Small: short of the middle of the screen, where a GPX tour centres the track while it
     * circles the end. Large: across to the column of buttons on the right.
     */
    private float targetWidth() {
        float u = widgetUnitStep;
        if (root.getStage() == null) {
            return PANE_UNITS * u;
        }
        float stageWidth = root.getStage().getWidth();
        float panelPads = 2 * PANEL_PAD_UNITS * u;
        if (maximized) {
            return Math.max(3f * u, stageWidth - PAD_LEFT_UNITS * u - panelPads - 1.5f * u);
        }
        float room = stageWidth / 2 - PAD_LEFT_UNITS * u - panelPads - 0.4f * u; // clear of the tour dot
        return Math.max(3f * u, Math.min(PANE_UNITS * u, room));
    }

    /** Small: a strip. Large: the height the screen has left under the text, above the scrub bar. */
    private float targetProfileHeight() {
        float u = widgetUnitStep;
        float small = 1.3f * u;
        if (!maximized || root.getStage() == null) {
            return small;
        }
        float text = 5 * name.getStyle().font.getLineHeight() + 0.2f * u;
        float room = root.getStage().getHeight() - PAD_TOP_UNITS * u - ROW_BUTTON_UNITS * u
                - 2 * PANEL_PAD_UNITS * u - text
                - 3.2f * u; // the scrub bar and the coordinates under it
        return Math.max(small, room);
    }

    /** The panel's x, y, width and height and the stage's width and height, stage units, for tests. */
    public float[] boundsOnStage() {
        if (panel.getStage() == null) {
            return null;
        }
        com.badlogic.gdx.math.Vector2 v = panel.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        return new float[]{v.x, v.y, panel.getWidth(), panel.getHeight(),
                panel.getStage().getWidth(), panel.getStage().getHeight()};
    }

    /** Stage position of the first body label, for tests: null when folded or not laid out yet. */
    public float[] bodyPositionOnStage() {
        if (!open || name.getStage() == null) {
            return null;
        }
        com.badlogic.gdx.math.Vector2 v = name.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        return new float[]{v.x, v.y};
    }

    /** The stats shown, or null when no track is. */
    public GpxTrackStats getStats() {
        return stats;
    }

    /** The texts shown, for tests and scripts: the track's name first. */
    public String[] getTexts() {
        return new String[]{name.getText().toString(), distance.getText().toString(),
                time.getText().toString(), climb.getText().toString(), heights.getText().toString()};
    }

    /**
     * Brings the pane in line with the loaded tracks - shown for the longest of them, hidden when
     * there is none - and moves the profile's dot to {@code tourFraction} (0..1 along the track),
     * or hides it when negative. Render thread, every frame; cheap unless something changed.
     */
    public void update(int gpxVersion, List<GpxTrack> tracks, float tourFraction) {
        float targetWidth = targetWidth();
        float targetProfile = targetProfileHeight();
        if (Math.abs(targetWidth - width) > 0.5f || Math.abs(targetProfile - profileHeight) > 0.5f) {
            width = targetWidth;
            profileHeight = targetProfile;
            layoutPanel();
        }
        UnitSystem units = P.getUnitSystem();
        if (gpxVersion != shownVersion || units != shownUnits) {
            shownVersion = gpxVersion;
            shownUnits = units;
            rebuild(tracks, units);
        }
        root.setVisible(stats != null);
        if (stats == null) {
            return;
        }
        boolean showDot = open && tourFraction >= 0f && stats.profileMetres != null;
        dot.setVisible(showDot);
        float w = profileGroup.getWidth(), h = profileGroup.getHeight();
        profile.setBounds(0, 0, w, h);
        if (showDot && w > 0) {
            float[] p = stats.profileMetres;
            float f = Math.max(0f, Math.min(1f, tourFraction));
            int index = Math.round(f * (p.length - 1));
            float y = profileY(p[index], h);
            float size = 0.3f * widgetUnitStep;
            dot.setBounds(f * w - size / 2, y - size / 2, size, size);
        }
    }

    private void rebuild(List<GpxTrack> tracks, UnitSystem units) {
        GpxTrack longest = null;
        for (GpxTrack t : tracks) {
            if (longest == null || t.size() > longest.size()) {
                longest = t;
            }
        }
        stats = longest == null ? null : GpxTrackStats.of(longest, (lat, lon) -> {
            // ElevationUtils' own lookup never finds a tile; the loaded terrain does.
            float metres = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
            return Float.isNaN(metres) ? null : metres;
        });
        if (profileTexture != null) {
            profileTexture.dispose();
            profileTexture = null;
        }
        if (stats == null) {
            return;
        }
        name.setText(stats.name == null || stats.name.trim().isEmpty() ? s("Gpx_info_title") : stats.name.trim());
        distance.setText(s("Gpx_info_distance") + ": " + GpxTrackStats.formatDistance(stats.distanceMetres, units));
        time.setText(s("Gpx_info_time") + ": " + GpxTrackStats.formatDuration(stats.walkingMinutes));
        climb.setText(s("Gpx_info_ascent") + ": " + GpxTrackStats.formatHeight(stats.ascentMetres, units)
                + "   " + s("Gpx_info_descent") + ": " + GpxTrackStats.formatHeight(stats.descentMetres, units));
        heights.setText(s("Gpx_info_highest") + ": " + GpxTrackStats.formatHeight(stats.highestMetres, units)
                + "   " + s("Gpx_info_lowest") + ": " + GpxTrackStats.formatHeight(stats.lowestMetres, units));
        if (stats.profileMetres != null) {
            profileTexture = drawProfile(stats.profileMetres);
            profile.setDrawable(new TextureRegionDrawable(profileTexture));
        } else {
            profile.setDrawable(null);
        }
    }

    /** Height of a profile value on the drawn profile, bottom-up, in the group's height. */
    private float profileY(float metres, float height) {
        float range = (float) Math.max(1, stats.highestMetres - stats.lowestMetres);
        float margin = 0.08f;
        return height * (margin + (1 - 2 * margin) * (float) ((metres - stats.lowestMetres) / range));
    }

    /** The profile as a filled area under a light line, lowest point near the bottom. */
    private Texture drawProfile(float[] p) {
        Pixmap pixmap = new Pixmap(PROFILE_WIDTH, PROFILE_HEIGHT, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        pixmap.setColor(0, 0, 0, 0);
        pixmap.fill();
        int previousY = -1;
        for (int x = 0; x < PROFILE_WIDTH; x++) {
            float f = x / (float) (PROFILE_WIDTH - 1) * (p.length - 1);
            int i = Math.min(p.length - 2, (int) f);
            float metres = p[i] + (f - i) * (p[i + 1] - p[i]);
            int y = PROFILE_HEIGHT - 1 - Math.round(profileY(metres, PROFILE_HEIGHT - 1));
            pixmap.setColor(PROFILE_FILL);
            pixmap.drawLine(x, y, x, PROFILE_HEIGHT - 1);
            pixmap.setColor(PROFILE_LINE);
            pixmap.drawLine(x, previousY < 0 ? y : previousY, x, y);
            pixmap.drawPixel(x, Math.max(0, y - 1));
            previousY = y;
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }
}
