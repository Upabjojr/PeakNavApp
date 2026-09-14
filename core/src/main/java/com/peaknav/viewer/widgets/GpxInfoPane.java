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
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.gpx.GpxTrack;
import com.peaknav.gpx.GpxTrackStats;
import com.peaknav.utils.PreferencesManager.UnitSystem;
import com.peaknav.utils.Units;

import java.util.List;

/**
 * A collapsible pane over the top of the map while a GPX track is loaded: its name in a header that
 * folds the pane open and shut, and below it where the track starts and ends, how long it is, how
 * much it climbs and drops, the walking time, and its altimetric profile - with a dot on the profile
 * where a running tour has got to.
 *
 * <p>Built from scene2d widgets so it follows the stage's layout and scale; the profile is drawn
 * into a texture once per track (or change of units), not every frame.
 */
public class GpxInfoPane {

    private static final Color PANEL = new Color(0.03f, 0.08f, 0.14f, 0.72f);
    private static final Color PROFILE_FILL = new Color(0.10f, 0.45f, 0.90f, 0.55f);
    private static final Color PROFILE_LINE = new Color(0.62f, 0.83f, 1f, 1f);
    private static final int PROFILE_WIDTH = 512;
    private static final int PROFILE_HEIGHT = 128;

    private final Table root = new Table();
    private final Table panel = new Table();
    private final Table body = new Table();
    private final TextButton header;
    private final Label position;
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
    private GpxTrackStats stats;

    public GpxInfoPane(float widgetUnitStep) {
        this.widgetUnitStep = widgetUnitStep;
        root.setFillParent(true);
        root.top().padTop(0.2f * widgetUnitStep + widgetUnitStep);
        root.setVisible(false);

        panel.setBackground(getC().widgetTextures.getUniformDrawable(PANEL));
        panel.pad(0.18f * widgetUnitStep);

        header = getC().widgetGetter.getTextButton("", false);
        header.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                setOpen(!open);
            }
        });

        // White-baked glyphs and no background of their own: the shared small style's glyphs are
        // baked black on a black background, which on this dark panel is invisible.
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = getC().styleSingleton.getBitmapFontSmallWhite();
        style.fontColor = Color.WHITE;
        position = label(style);
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

        float width = 7f * widgetUnitStep;
        body.defaults().left().width(width);
        body.add(position).row();
        body.add(distance).row();
        body.add(time).row();
        body.add(climb).row();
        body.add(heights).padBottom(0.1f * widgetUnitStep).row();
        body.add(profileGroup).height(1.6f * widgetUnitStep).row();

        panel.add(header).width(width).height(0.8f * widgetUnitStep).row();
        panel.add(body).width(width);
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

    public void setOpen(boolean value) {
        open = value;
        body.setVisible(value);
        panel.getCell(body).height(value ? -1 : 0);
        body.clearChildren();
        if (value) {
            float width = 7f * widgetUnitStep;
            body.add(position).row();
            body.add(distance).row();
            body.add(time).row();
            body.add(climb).row();
            body.add(heights).padBottom(0.1f * widgetUnitStep).row();
            body.add(profileGroup).width(width).height(1.6f * widgetUnitStep).row();
        }
        updateHeader();
        panel.invalidateHierarchy();
    }

    /** The stats shown, or null when no track is. */
    public GpxTrackStats getStats() {
        return stats;
    }

    /** The texts shown, for tests and scripts: header first. */
    public String[] getTexts() {
        return new String[]{header.getText().toString(), position.getText().toString(),
                distance.getText().toString(), time.getText().toString(), climb.getText().toString(),
                heights.getText().toString()};
    }

    /**
     * Brings the pane in line with the loaded tracks - shown for the longest of them, hidden when
     * there is none - and moves the profile's dot to {@code tourFraction} (0..1 along the track),
     * or hides it when negative. Render thread, every frame; cheap unless something changed.
     */
    public void update(int gpxVersion, List<GpxTrack> tracks, float tourFraction) {
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
            float size = 0.36f * widgetUnitStep;
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
            Float latits = ElevationUtils.getElevationLatitsFromMaxCoords(lon, lat, false);
            return latits == null ? null : Units.convertLatitsToMeters(latits);
        });
        if (profileTexture != null) {
            profileTexture.dispose();
            profileTexture = null;
        }
        if (stats == null) {
            return;
        }
        position.setText(s("Gpx_info_start") + ": " + GpxTrackStats.formatPosition(stats.startLat, stats.startLon)
                + "\n" + s("Gpx_info_end") + ": " + GpxTrackStats.formatPosition(stats.endLat, stats.endLon));
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
        updateHeader();
    }

    private void updateHeader() {
        if (stats == null) {
            return;
        }
        String name = stats.name == null || stats.name.trim().isEmpty() ? s("Gpx_info_title") : stats.name.trim();
        // Arrows the font bakes (see FontCharacters): up to fold the pane, down to open it.
        header.setText((open ? "↑ " : "↓ ") + name);
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
