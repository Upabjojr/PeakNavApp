package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.peaknav.gpx.GpxTrack;
import com.peaknav.gpx.GpxTrackStats;
import com.peaknav.gpx.GraphTicks;
import com.peaknav.routing.WayWording;
import com.peaknav.utils.PreferencesManager.UnitSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * A small pane at the left side of the map while a GPX track is loaded: a row of two buttons -
 * one folds the pane down to that single button, the other makes it wide and back - and under
 * them the track's name, how long it is, how much it climbs and drops, the walking time, and its
 * altimetric profile, with a dot on the profile where a running tour has got to and, while it
 * runs, the elevation there and the distance walked so far. The profile is labelled: heights up the side, in the chosen units,
 * and the time since the start along the bottom - as recorded, or else the walking time so far.
 *
 * <p>A track that recorded its own heights has the terrain's drawn over them, with a legend; one
 * that recorded times has a speed graph under the profile. The graphs keep their proportions:
 * maximized, the pane widens and they grow taller with it, and whatever no longer fits on the
 * screen above the scrub bar scrolls.
 *
 * <p>Built from scene2d widgets so it follows the stage's layout and scale; the graphs are drawn
 * into textures once per track (or change of units), not every frame.
 */
public class GpxInfoPane {

    private static final Color PANEL = new Color(0.03f, 0.08f, 0.14f, 0.72f);
    private static final Color PROFILE_FILL = new Color(0.10f, 0.45f, 0.90f, 0.55f);
    private static final Color PROFILE_LINE = new Color(0.62f, 0.83f, 1f, 1f);
    private static final Color TERRAIN_LINE = new Color(1f, 0.62f, 0.20f, 1f);
    private static final Color SPEED_FILL = new Color(0.20f, 0.72f, 0.35f, 0.5f);
    private static final Color SPEED_LINE = new Color(0.62f, 0.95f, 0.66f, 1f);
    /** The small pane's width, in widget units, where the screen has room for it. */
    private static final float PANE_UNITS = 4.6f;
    private static final float PAD_LEFT_UNITS = 1.5f;
    private static final float PAD_TOP_UNITS = 1.2f;
    private static final float PANEL_PAD_UNITS = 0.12f;
    /** Size of the buttons in the open pane's top row. */
    private static final float ROW_BUTTON_UNITS = 0.7f;
    /**
     * Heights of the graphs at the small pane's full width; at any other width they keep these
     * proportions.
     */
    private static final float PROFILE_UNITS = 1.3f;
    private static final float SPEED_UNITS = 1.0f;
    /** Kept clear under the pane: the scrub bar and the coordinates beneath it. */
    private static final float BOTTOM_CLEAR_UNITS = 3.2f;
    private static final Color SCROLL_KNOB = new Color(1f, 1f, 1f, 0.55f);
    /** Behind the way a tour is on, in the list of ways: the profile's blue, the tour dot's. */
    private static final Color CURRENT_WAY = new Color(0.10f, 0.45f, 0.90f, 0.6f);
    private static final Color AXIS_TEXT = new Color(0.82f, 0.88f, 1f, 1f);
    private static final Color GRID = new Color(1f, 1f, 1f, 0.2f);
    /** Most labels an axis gets: the small pane, and the maximized one along its width. */
    private static final int AXIS_TICKS = 3;
    private static final int AXIS_TICKS_WIDE = 6;
    private static final String ICON_FOLD = "icons/icon_pane_fold.png";
    private static final String ICON_OPEN = "icons/icon_gpx_info.png";
    private static final String ICON_MAXIMIZE = "icons/icon_pane_maximize.png";
    private static final String ICON_RESTORE = "icons/icon_pane_restore.png";
    /** Widest a graph's texture is drawn, in pixels. */
    private static final int GRAPH_MAX_PIXELS = 2048;

    private final Table root = new Table();
    private final Table panel = new Table();
    private final Table buttons = new Table();
    private final Table body = new Table();
    private final Table legend = new Table();
    private final ScrollPane scroll;
    private final Drawable panelBackground;
    private final Button foldButton;
    private final Button sizeButton;
    private final Label name;
    private final Label distance;
    private final Label time;
    private final Label climb;
    private final Label heights;
    private final Label speed;
    private final Label current;
    private final Label walked;
    /** Where a running tour is: the time there, and the way it is on and what kind of way. */
    private final Label timeNow;
    private final Label wayNow;
    private final Label wayKindNow;
    /** Every way the track follows, as a computed route records them; see RouteGpx. */
    private final Label waysTitle;
    /** The list's header, tapped to fold the list away or open it: its title and a chevron. */
    private final Table waysHeader = new Table();
    private final Image waysChevron;
    private final List<Label> wayRows = new ArrayList<>();
    private final Table waysList = new Table();
    /** The row of each way, a table so the one the tour is on can be lit behind its text. */
    private final List<Table> wayCells = new ArrayList<>();
    private final Drawable currentWayBackground;
    private boolean waysOpen = true;
    /** The row lit as the way the tour is on; -1 for none. */
    private int litWay = -1;
    private final Label.LabelStyle axisStyle;
    private final List<Label> yLabels = new ArrayList<>();
    private final List<Label> xLabels = new ArrayList<>();
    private final WidgetGroup speedGroup = new WidgetGroup();
    private final Image profile = new Image();
    private final Image dot;
    private final WidgetGroup profileGroup = new WidgetGroup();
    private final Image speedGraph = new Image();
    private final float widgetUnitStep;

    private Texture profileTexture;
    private Texture speedTexture;
    private int shownVersion = -1;
    private UnitSystem shownUnits;
    private boolean open = true;
    private boolean maximized = false;
    private GpxTrackStats stats;
    /** The heights the profile fills, the terrain's drawn over them, and the range of both. */
    private float[] plotted, plottedTerrain;
    private float plotLow, plotHigh;
    /** Pixel sizes the graphs' textures were drawn at: redrawn when the shown size changes. */
    private int drawnProfileWidth, drawnProfileHeight, drawnSpeedWidth, drawnSpeedHeight;
    /** The body's width, and the most of its height shown before it scrolls, as last laid out. */
    private float width, visibleHeight;
    /** The axes' ticks: heights in metres, and where along the graph each time label goes. */
    private double[] yTicks = new double[0];
    private float[] xTickFractions = new float[0];
    /** Room left of the graphs for the height labels, stage units. */
    private float gutter;
    private boolean currentShown;
    private String currentText = "";
    private String walkedText = "";
    private String timeNowText = "";
    private String wayNowText = "";
    private String wayKindNowText = "";
    /** The shown track's stretches, and how far along it each starts, as a share of its length. */
    private List<GpxTrack.Stretch> stretches = new ArrayList<>();
    private float[] stretchStarts = new float[0];

    public GpxInfoPane(float widgetUnitStep) {
        this.widgetUnitStep = widgetUnitStep;
        root.setFillParent(true);
        // On the left, beside the column of zoom and gallery buttons, so the middle of the map stays clear.
        root.top().left().padTop(PAD_TOP_UNITS * widgetUnitStep).padLeft(PAD_LEFT_UNITS * widgetUnitStep);
        root.setVisible(false);

        panelBackground = getC().widgetTextures.getUniformDrawable(PANEL);
        panel.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        panel.addListener(FeatureInfoPane.swallowingListener());

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
        speed = label(style);
        current = label(style);
        walked = label(style);
        timeNow = label(style);
        wayNow = label(style);
        wayKindNow = label(style);
        waysTitle = label(style);
        currentWayBackground = getC().widgetTextures.getUniformDrawable(CURRENT_WAY);
        // The pane's fold chevron: up to fold the list away, as it folds the pane; turned over,
        // down, to open it again.
        waysChevron = new Image(getC().widgetTextures.getTextureRegionDrawable(ICON_FOLD)) {
            @Override
            public void layout() {
                super.layout();
                setOrigin(Align.center);
            }
        };
        waysHeader.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        waysHeader.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override
            public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                setWaysOpen(!waysOpen);
            }
        });
        axisStyle = new Label.LabelStyle(style);
        axisStyle.fontColor = AXIS_TEXT;
        speedGroup.addActor(speedGraph);

        // The legend's words in the colours of their lines: the glyphs are baked white, so they tint.
        Label.LabelStyle recordedStyle = new Label.LabelStyle(style);
        recordedStyle.fontColor = PROFILE_LINE;
        Label.LabelStyle terrainStyle = new Label.LabelStyle(style);
        terrainStyle.fontColor = TERRAIN_LINE;
        legend.add(new Label("— " + s("Gpx_info_profile_gpx"), recordedStyle)).left().padRight(0.3f * widgetUnitStep);
        legend.add(new Label("— " + s("Gpx_info_profile_terrain"), terrainStyle)).left();
        legend.add().expandX();

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

        ScrollPane.ScrollPaneStyle scrollStyle = new ScrollPane.ScrollPaneStyle();
        TextureRegionDrawable knob = new TextureRegionDrawable(getC().widgetTextures.getUniformDrawable(SCROLL_KNOB));
        knob.setMinWidth(0.08f * widgetUnitStep);
        knob.setMinHeight(0.5f * widgetUnitStep);
        scrollStyle.vScrollKnob = knob;
        scroll = new ScrollPane(body, scrollStyle);
        scroll.setScrollingDisabled(true, false);
        scroll.setOverscroll(false, false);
        scroll.setFadeScrollBars(false);
        scroll.setScrollbarsOnTop(true);

        width = PANE_UNITS * widgetUnitStep;
        visibleHeight = visibleHeight();
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
        float kept = scrollShare();
        open = value;
        layoutPanel();
        restoreScroll(kept);
    }

    /** Wide (across to the buttons on the right) or back to the small pane. Opens a folded pane. */
    public void setMaximized(boolean value) {
        float kept = scrollShare();
        maximized = value;
        open = true;
        width = targetWidth();
        visibleHeight = visibleHeight();
        buildTimeAxis(); // more labels along a wider graph
        layoutPanel();
        restoreScroll(kept);
    }

    /**
     * How far down the body the pane is scrolled, 0 at the top and 1 at the bottom - kept across a
     * resize or a fold, which lay the pane out afresh and used to leave it at the top. A share,
     * not a distance: maximized, the graphs grow, and the same distance is another place.
     */
    private float scrollShare() {
        if (!open) {
            return foldedScrollShare;
        }
        float max = scroll.getMaxY();
        return max > 0 ? MathUtils.clamp(scroll.getScrollY() / max, 0f, 1f) : foldedScrollShare;
    }

    /** Where the body was scrolled when the pane was folded; see {@link #scrollShare}. */
    private float foldedScrollShare = 0f;

    private void restoreScroll(float share) {
        if (!open) {
            foldedScrollShare = share;
            return;
        }
        root.validate();
        scroll.layout();
        scroll.setScrollPercentY(share);
        scroll.updateVisualScroll();
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
        body.add(heights).row();
        if (currentShown) {
            body.add(current).row();
            body.add(walked).row();
            body.add(timeNow).row();
            if (!stretches.isEmpty()) {
                body.add(wayNow).row();
                body.add(wayKindNow).row();
            }
        }
        if (stats != null && stats.hasTwoProfiles()) {
            body.add(legend).padTop(0.04f * u).row();
        }
        // The graphs' proportions are those of the area they draw in, beside the height labels.
        float graphWidth = Math.max(u, width - gutter);
        body.add(profileGroup).padTop(0.08f * u).height(graphWidth * PROFILE_UNITS / PANE_UNITS + axisBand()).row();
        if (stats != null && stats.speedKmh != null) {
            body.add(speed).padTop(0.08f * u).row();
            body.add(speedGroup).height(graphWidth * SPEED_UNITS / PANE_UNITS).row();
        }
        if (!stretches.isEmpty()) {
            float chevron = 0.45f * u;
            waysTitle.setText(s("Gpx_info_ways") + " (" + stretches.size() + ")");
            waysHeader.clearChildren();
            waysHeader.add(waysTitle).left().width(width - chevron - 0.1f * u);
            waysHeader.add(waysChevron).size(chevron).right().padLeft(0.1f * u);
            waysChevron.setRotation(waysOpen ? 0f : 180f);
            body.add(waysHeader).padTop(0.12f * u).row();
            if (waysOpen) {
                // No scroll pane of its own: a long list scrolls with the rest of the pane.
                waysList.clearChildren();
                float inset = 0.08f * u;
                for (int i = 0; i < wayCells.size(); i++) {
                    Table cell = wayCells.get(i);
                    cell.clearChildren();
                    cell.add(wayRows.get(i)).left().width(width - 2 * inset).pad(0.02f * u, inset, 0.02f * u, inset);
                    waysList.add(cell).left().width(width).padBottom(0.02f * u).row();
                }
                body.add(waysList).row();
            }
        }

        panel.add(buttons).width(width).row();
        panel.add(scroll).width(width).maxHeight(visibleHeight);
        panel.invalidateHierarchy();
    }

    private static void setIcon(Button button, String icon) {
        button.getStyle().up = getC().widgetTextures.getTextureRegionDrawable(icon);
    }

    /**
     * Small: short of the middle of the screen, where a GPX tour centres the track while it
     * circles the end. Maximized: across to the column of buttons on the right.
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

    /** The body's height the screen has room for above the scrub bar; more than that scrolls. */
    private float visibleHeight() {
        float u = widgetUnitStep;
        if (root.getStage() == null) {
            return 100f * u;
        }
        float room = root.getStage().getHeight() - PAD_TOP_UNITS * u - ROW_BUTTON_UNITS * u
                - 2 * PANEL_PAD_UNITS * u - BOTTOM_CLEAR_UNITS * u;
        return Math.max(2f * u, room);
    }

    /** Height of the row of time labels under the profile. */
    private float axisBand() {
        return axisStyle.font.getLineHeight();
    }

    /** The profile's and the speed graph's drawing areas, width and height, stage units, for tests. */
    public float[] graphSizes() {
        return new float[]{profile.getWidth(), profile.getHeight(), speedGraph.getWidth(), speedGraph.getHeight()};
    }

    /** The axes' labels, for tests: "y:" and the height, then "x:" and the time. */
    public String[] axisLabels() {
        List<String> out = new ArrayList<>();
        for (Label l : yLabels) {
            out.add("y:" + l.getText());
        }
        for (Label l : xLabels) {
            out.add("x:" + l.getText());
        }
        return out.toArray(new String[0]);
    }

    /** How far the body can scroll and how far it has, stage units, for tests. */
    public float[] scrollState() {
        return new float[]{scroll.getMaxY(), scroll.getScrollY()};
    }

    /** Scrolls the body, 0 at the top and 1 at the bottom, as a drag would; for tests. */
    public void scrollTo(float fraction) {
        scroll.layout();
        scroll.setScrollPercentY(fraction);
        scroll.updateVisualScroll();
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

    /** Which graphs are shown, for tests: recorded and terrain heights side by side, and speed. */
    public boolean[] graphs() {
        return new boolean[]{stats != null && stats.hasTwoProfiles(), stats != null && stats.speedKmh != null};
    }

    /**
     * The texts shown, for tests and scripts: the track's name, distance, time, climb, heights,
     * speed ("" without), and the elevation where a running tour is and the distance it has
     * walked ("" without).
     */
    /**
     * The ways, for tests and scripts: where a tour is, the way there and what kind ("" without a
     * tour or ways) and the time there ("" without a tour), then a row for every way of the track.
     */
    /** Whether the list of ways is open, and which row is lit (-1: none); for tests. */
    public int[] waysState() {
        return new int[]{waysOpen ? 1 : 0, litWay};
    }

    /**
     * Scrolls the row of way {@code k} into view and says where its middle is on the stage, for
     * tests to tap it as a finger would; null when the list is folded or has no such row.
     */
    public float[] wayRowOnStage(int k) {
        if (!waysOpen || k < 0 || k >= wayCells.size() || wayCells.get(k).getStage() == null) {
            return null;
        }
        Table cell = wayCells.get(k);
        root.validate();
        com.badlogic.gdx.math.Vector2 inBody = cell.localToAscendantCoordinates(body, new com.badlogic.gdx.math.Vector2());
        scroll.scrollTo(inBody.x, inBody.y, cell.getWidth(), cell.getHeight(), false, true);
        scroll.updateVisualScroll();
        root.validate();
        com.badlogic.gdx.math.Vector2 v = cell.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(cell.getWidth() / 2, cell.getHeight() / 2));
        return new float[]{v.x, v.y};
    }

    /** Opens the list of ways or folds it away, as its header does. */
    public void setWaysOpen(boolean value) {
        waysOpen = value;
        layoutPanel();
    }

    public String[] getWayTexts() {
        List<String> out = new ArrayList<>();
        out.add(currentShown && !stretches.isEmpty() ? wayNowText : "");
        out.add(currentShown && !stretches.isEmpty() ? wayKindNowText : "");
        out.add(currentShown ? timeNowText : "");
        for (Label row : wayRows) {
            out.add(row.getText().toString());
        }
        return out.toArray(new String[0]);
    }

    public String[] getTexts() {
        return new String[]{name.getText().toString(), distance.getText().toString(),
                time.getText().toString(), climb.getText().toString(), heights.getText().toString(),
                stats != null && stats.speedKmh != null ? speed.getText().toString() : "",
                currentShown ? currentText : "", currentShown ? walkedText : ""};
    }

    /**
     * Brings the pane in line with the loaded tracks - shown for the longest of them, hidden when
     * there is none - and moves the profile's dot to {@code tourFraction} (0..1 along the track),
     * or hides it when negative. Render thread, every frame; cheap unless something changed.
     */
    public void update(int gpxVersion, List<GpxTrack> tracks, float tourFraction) {
        float targetWidth = targetWidth();
        float targetVisible = visibleHeight();
        if (Math.abs(targetWidth - width) > 0.5f || Math.abs(targetVisible - visibleHeight) > 0.5f) {
            width = targetWidth;
            visibleHeight = targetVisible;
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
        float f = Math.max(0f, Math.min(1f, tourFraction));
        boolean showCurrent = tourFraction >= 0f && plotted != null;
        if (showCurrent) {
            String text = s("Gpx_info_elevation_now") + ": " + GpxTrackStats.formatHeight(valueAt(plotted, f), units);
            if (!text.equals(currentText)) {
                currentText = text;
                current.setText(text);
            }
            // The tour's frames are evenly spaced along the track, so the fraction is of its distance.
            String walkedNow = s("Gpx_info_walked") + ": " + GpxTrackStats.formatDistance(f * stats.distanceMetres, units);
            if (!walkedNow.equals(walkedText)) {
                walkedText = walkedNow;
                walked.setText(walkedNow);
            }
            float[] elapsed = stats.elapsedMinutes;
            String timeText = s("Gpx_info_time_now") + ": " + GpxTrackStats.formatDuration(valueAt(elapsed, f))
                    + " / " + GpxTrackStats.formatDuration(elapsed[elapsed.length - 1]);
            if (!timeText.equals(timeNowText)) {
                timeNowText = timeText;
                timeNow.setText(timeText);
            }
            if (!stretches.isEmpty()) {
                com.peaknav.routing.WayInfo way = stretches.get(stretchAt(f)).way;
                String wayText = s("Gpx_info_way_now") + ": " + new WayWording(units).label(way);
                if (!wayText.equals(wayNowText)) {
                    wayNowText = wayText;
                    wayNow.setText(wayText);
                }
                String kindText = WayWording.kind(way);
                if (!kindText.equals(wayKindNowText)) {
                    wayKindNowText = kindText;
                    wayKindNow.setText(kindText);
                }
            }
        }
        if (showCurrent != currentShown) {
            currentShown = showCurrent;
            layoutPanel();
        }
        lightWay(showCurrent && !stretches.isEmpty() ? stretchAt(f) : -1);
        placeAxes();
        redrawGraphs();
        boolean showDot = open && showCurrent;
        dot.setVisible(showDot);
        if (showDot && profile.getWidth() > 0) {
            float y = profile.getY() + graphY(valueAt(plotted, f), plotLow, plotHigh, profile.getHeight());
            float size = 0.3f * widgetUnitStep;
            dot.setBounds(profile.getX() + f * profile.getWidth() - size / 2, y - size / 2, size, size);
        }
    }

    /** Lights the row of the way the tour is on, and puts out the one lit before. */
    private void lightWay(int index) {
        if (index == litWay) {
            return;
        }
        if (litWay >= 0 && litWay < wayCells.size()) {
            wayCells.get(litWay).setBackground((Drawable) null);
        }
        if (index >= 0 && index < wayCells.size()) {
            wayCells.get(index).setBackground(currentWayBackground);
        }
        litWay = index;
    }

    /** The stretch a fraction 0..1 of the way along is on: the last to start at or before it. */
    private int stretchAt(float fraction) {
        int at = 0;
        for (int i = 1; i < stretchStarts.length; i++) {
            if (stretchStarts[i] <= fraction) {
                at = i;
            }
        }
        return at;
    }

    /**
     * The track's stretches, and a row for each: what the way is called, what kind it is, and how
     * far and how long it goes - the time read off the same walking-time series as the profile's
     * axis, so the rows add up to the track's time.
     */
    private void buildWays(GpxTrack track, UnitSystem units) {
        stretches = new ArrayList<>();
        stretchStarts = new float[0];
        wayRows.clear();
        wayCells.clear();
        litWay = -1;
        if (track == null || track.getStretches().isEmpty() || stats == null) {
            return;
        }
        List<GpxTrack.Point> points = track.getPoints();
        double[] along = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            GpxTrack.Point a = points.get(i - 1), b = points.get(i);
            along[i] = along[i - 1] + com.peaknav.routing.WalkingRouter.metres(a.lat, a.lon, b.lat, b.lon);
        }
        double total = along[along.length - 1];
        if (total <= 0) {
            return;
        }
        stretches = new ArrayList<>(track.getStretches());
        stretchStarts = new float[stretches.size()];
        WayWording wording = new WayWording(units);
        float[] elapsed = stats.elapsedMinutes;
        for (int k = 0; k < stretches.size(); k++) {
            GpxTrack.Stretch stretch = stretches.get(k);
            int first = Math.min(stretch.firstPoint, along.length - 1);
            int last = k + 1 < stretches.size() ? Math.min(stretches.get(k + 1).firstPoint, along.length - 1) : along.length - 1;
            float from = (float) (along[first] / total), to = (float) (along[last] / total);
            stretchStarts[k] = from;
            String text = wording.line(stretch.way, GpxTrackStats.formatDistance(along[last] - along[first], units)
                    + ", " + GpxTrackStats.formatDuration(valueAt(elapsed, to) - valueAt(elapsed, from)));
            Label row = label(new Label.LabelStyle(name.getStyle()));
            row.setText(text);
            wayRows.add(row);
            // Tapped, the tour goes to where the way starts: paused there if it was not playing.
            Table cell = new Table();
            cell.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
            final float start = from;
            cell.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                @Override
                public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                    getC().getMapViewerScreen().seekGpxTourAlongTrack(start);
                }
            });
            wayCells.add(cell);
        }
    }

    /** A series at a fraction 0..1 of the way along, between its samples. */
    private static float valueAt(float[] series, float fraction) {
        float position = fraction * (series.length - 1);
        int i = Math.min(series.length - 2, (int) position);
        return series[i] + (position - i) * (series[i + 1] - series[i]);
    }

    /**
     * The profile's drawing area right of the height labels and above the time labels, and each
     * label beside the height or under the time it names; the speed graph under the same span.
     */
    private void placeAxes() {
        float u = widgetUnitStep;
        float w = profileGroup.getWidth(), h = profileGroup.getHeight();
        float band = axisBand();
        float graphWidth = Math.max(1f, w - gutter), graphHeight = Math.max(1f, h - band);
        profile.setBounds(gutter, band, graphWidth, graphHeight);
        for (int i = 0; i < yLabels.size(); i++) {
            Label l = yLabels.get(i);
            float lw = l.getPrefWidth(), lh = l.getPrefHeight();
            float y = band + graphY((float) yTicks[i], plotLow, plotHigh, graphHeight) - lh / 2;
            l.setBounds(gutter - 0.08f * u - lw, Math.max(0f, Math.min(h - lh, y)), lw, lh);
        }
        for (int i = 0; i < xLabels.size(); i++) {
            Label l = xLabels.get(i);
            float lw = l.getPrefWidth();
            float x = gutter + xTickFractions[i] * graphWidth - lw / 2;
            l.setBounds(Math.max(0f, Math.min(w - lw, x)), 0f, lw, band);
        }
        speedGraph.setBounds(gutter, 0f, Math.max(1f, speedGroup.getWidth() - gutter), speedGroup.getHeight());
    }

    /** The height labels up the profile's side, and the room they take. */
    private void buildHeightAxis(UnitSystem units) {
        for (Label l : yLabels) {
            l.remove();
        }
        yLabels.clear();
        yTicks = plotted == null ? new double[0] : GraphTicks.heightTicksMetres(plotLow, plotHigh, units, AXIS_TICKS);
        float widest = 0f;
        for (double v : yTicks) {
            Label l = new Label(GpxTrackStats.formatHeight(v, units), axisStyle);
            profileGroup.addActor(l);
            yLabels.add(l);
            widest = Math.max(widest, l.getPrefWidth());
        }
        gutter = yLabels.isEmpty() ? 0f : widest + 0.16f * widgetUnitStep;
    }

    /** The time labels under the profile: round times since the start, where the track got to then. */
    private void buildTimeAxis() {
        for (Label l : xLabels) {
            l.remove();
        }
        xLabels.clear();
        xTickFractions = new float[0];
        drawnProfileWidth = drawnProfileHeight = 0; // the grid follows the ticks
        if (stats == null || plotted == null) {
            return;
        }
        float[] elapsed = stats.elapsedMinutes;
        double[] times = GraphTicks.timeTicksMinutes(elapsed[elapsed.length - 1], maximized ? AXIS_TICKS_WIDE : AXIS_TICKS);
        xTickFractions = new float[times.length];
        for (int i = 0; i < times.length; i++) {
            xTickFractions[i] = GraphTicks.fractionAt(elapsed, times[i]);
            Label l = new Label(GraphTicks.formatClock(times[i]), axisStyle);
            profileGroup.addActor(l);
            xLabels.add(l);
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
        disposeGraphs();
        plotted = null;
        buildWays(longest, units);
        if (stats == null) {
            buildHeightAxis(units);
            buildTimeAxis();
            layoutPanel();
            return;
        }
        name.setText(stats.name == null || stats.name.trim().isEmpty() ? s("Gpx_info_title") : stats.name.trim());
        distance.setText(s("Gpx_info_distance") + ": " + GpxTrackStats.formatDistance(stats.distanceMetres, units));
        time.setText(s("Gpx_info_time") + ": " + GpxTrackStats.formatDuration(stats.walkingMinutes));
        climb.setText(s("Gpx_info_ascent") + ": " + GpxTrackStats.formatHeight(stats.ascentMetres, units)
                + "   " + s("Gpx_info_descent") + ": " + GpxTrackStats.formatHeight(stats.descentMetres, units));
        heights.setText(s("Gpx_info_highest") + ": " + GpxTrackStats.formatHeight(stats.highestMetres, units)
                + "   " + s("Gpx_info_lowest") + ": " + GpxTrackStats.formatHeight(stats.lowestMetres, units));

        plottedTerrain = stats.hasTwoProfiles() ? stats.terrainProfileMetres : null;
        plotted = plottedTerrain != null ? stats.recordedProfileMetres : stats.profileMetres;
        if (plotted != null) {
            plotLow = Float.MAX_VALUE;
            plotHigh = -Float.MAX_VALUE;
            for (float[] series : new float[][]{plotted, plottedTerrain}) {
                if (series == null) {
                    continue;
                }
                for (float v : series) {
                    plotLow = Math.min(plotLow, v);
                    plotHigh = Math.max(plotHigh, v);
                }
            }
        }
        buildHeightAxis(units);
        buildTimeAxis();
        currentText = ""; // in the new units, next frame
        walkedText = "";
        timeNowText = "";
        wayNowText = "";
        wayKindNowText = "";
        if (stats.speedKmh != null) {
            speed.setText(s("Gpx_info_speed") + ": " + s("Gpx_info_speed_average") + " "
                    + GpxTrackStats.formatSpeed(stats.averageSpeedKmh, units) + "   "
                    + s("Gpx_info_speed_max") + " " + GpxTrackStats.formatSpeed(stats.maxSpeedKmh, units));
        }
        layoutPanel();
    }

    private void disposeGraphs() {
        if (profileTexture != null) {
            profileTexture.dispose();
            profileTexture = null;
        }
        if (speedTexture != null) {
            speedTexture.dispose();
            speedTexture = null;
        }
        profile.setDrawable(null);
        speedGraph.setDrawable(null);
        drawnProfileWidth = drawnProfileHeight = drawnSpeedWidth = drawnSpeedHeight = 0;
    }

    /**
     * Draws the graphs' textures at the size they are shown, once laid out and again whenever that
     * size changes - a texture drawn once and stretched turned its lines into bands when the pane
     * was maximized.
     */
    private void redrawGraphs() {
        float pixelsPerUnit = root.getStage() == null ? 1f
                : com.badlogic.gdx.Gdx.graphics.getHeight() / root.getStage().getHeight();
        int lineHalf = Math.max(1, Math.round(0.025f * widgetUnitStep * pixelsPerUnit));
        if (plotted != null) {
            int w = Math.min(GRAPH_MAX_PIXELS, Math.round(profile.getWidth() * pixelsPerUnit));
            int h = Math.min(GRAPH_MAX_PIXELS, Math.round(profile.getHeight() * pixelsPerUnit));
            if (w > 2 && h > 2 && (Math.abs(w - drawnProfileWidth) > 2 || Math.abs(h - drawnProfileHeight) > 2)) {
                if (profileTexture != null) {
                    profileTexture.dispose();
                }
                float[] gridValues = new float[yTicks.length];
                for (int i = 0; i < yTicks.length; i++) {
                    gridValues[i] = (float) yTicks[i];
                }
                profileTexture = drawGraph(plotted, plottedTerrain, plotLow, plotHigh,
                        PROFILE_FILL, PROFILE_LINE, TERRAIN_LINE, w, h, lineHalf, gridValues, xTickFractions);
                profile.setDrawable(new TextureRegionDrawable(profileTexture));
                drawnProfileWidth = w;
                drawnProfileHeight = h;
            }
        }
        if (stats != null && stats.speedKmh != null) {
            int w = Math.min(GRAPH_MAX_PIXELS, Math.round(speedGraph.getWidth() * pixelsPerUnit));
            int h = Math.min(GRAPH_MAX_PIXELS, Math.round(speedGraph.getHeight() * pixelsPerUnit));
            if (w > 2 && h > 2 && (Math.abs(w - drawnSpeedWidth) > 2 || Math.abs(h - drawnSpeedHeight) > 2)) {
                if (speedTexture != null) {
                    speedTexture.dispose();
                }
                speedTexture = drawGraph(stats.speedKmh, null, 0f, (float) stats.maxSpeedKmh,
                        SPEED_FILL, SPEED_LINE, null, w, h, lineHalf, null, null);
                speedGraph.setDrawable(new TextureRegionDrawable(speedTexture));
                drawnSpeedWidth = w;
                drawnSpeedHeight = h;
            }
        }
    }

    /** Height of a value on a graph of the given range, bottom-up, in {@code height}. */
    private static float graphY(float value, float low, float high, float height) {
        float range = Math.max(1f, high - low);
        float margin = 0.08f;
        return height * (margin + (1 - 2 * margin) * (value - low) / range);
    }

    /**
     * A series as a filled area under a light line, and optionally a second series as a line of
     * its own colour over it, both in the same range, drawn at {@code width} by {@code height}
     * pixels with lines {@code 2 * lineHalf + 1} pixels thick; faint grid lines at the given
     * values across and fractions along, when there are any.
     */
    private static Texture drawGraph(float[] filled, float[] second, float low, float high,
                                     Color fill, Color line, Color secondLine, int width, int height, int lineHalf,
                                     float[] gridValues, float[] gridFractions) {
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        pixmap.setColor(0, 0, 0, 0);
        pixmap.fill();
        pixmap.setColor(fill);
        for (int x = 0; x < width; x++) {
            pixmap.drawLine(x, pixelY(filled, x, low, high, width, height), x, height - 1);
        }
        if (gridValues != null || gridFractions != null) {
            pixmap.setBlending(Pixmap.Blending.SourceOver);
            pixmap.setColor(GRID);
            if (gridValues != null) {
                for (float v : gridValues) {
                    int y = height - 1 - Math.round(graphY(v, low, high, height - 1));
                    pixmap.drawLine(0, y, width - 1, y);
                }
            }
            if (gridFractions != null) {
                for (float f : gridFractions) {
                    int x = Math.round(f * (width - 1));
                    pixmap.drawLine(x, 0, x, height - 1);
                }
            }
            pixmap.setBlending(Pixmap.Blending.None);
        }
        pixmap.setColor(line);
        int previousY = -1;
        for (int x = 0; x < width; x++) {
            int y = pixelY(filled, x, low, high, width, height);
            thickSegment(pixmap, x, previousY < 0 ? y : previousY, y, height, lineHalf);
            previousY = y;
        }
        if (second != null) {
            // Drawn over the first, so where the two agree the terrain's line is the one seen.
            pixmap.setColor(secondLine);
            int previousSecondY = -1;
            for (int x = 0; x < width; x++) {
                int y = pixelY(second, x, low, high, width, height);
                thickSegment(pixmap, x, previousSecondY < 0 ? y : previousSecondY, y, height, lineHalf);
                previousSecondY = y;
            }
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    /** One column of a line, from one row to the next and {@code lineHalf} pixels beyond each. */
    private static void thickSegment(Pixmap pixmap, int x, int fromY, int toY, int height, int lineHalf) {
        int top = Math.max(0, Math.min(fromY, toY) - lineHalf);
        int bottom = Math.min(height - 1, Math.max(fromY, toY) + lineHalf);
        pixmap.drawLine(x, top, x, bottom);
    }

    /** Row of the graph's pixel column {@code x} for a series, top-down. */
    private static int pixelY(float[] series, int x, float low, float high, int width, int height) {
        float f = x / (float) (width - 1) * (series.length - 1);
        int i = Math.min(series.length - 2, (int) f);
        float value = series[i] + (f - i) * (series[i + 1] - series[i]);
        return height - 1 - Math.round(graphY(value, low, high, height - 1));
    }
}
