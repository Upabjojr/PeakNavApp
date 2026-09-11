package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadGeo;
import com.peaknav.roads.RoadLabelCandidate;
import com.peaknav.roads.RoadLabelGeometry;
import com.peaknav.roads.RoadStyle;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.PhotoSkylineAligner;
import com.peaknav.viewer.render_tiles.ImpactPixmap;
import com.peaknav.viewer.screens.MapViewerScreen;
import com.peaknav.viewer.tiles.MapTile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the names of streets, tracks, trails and rivers, and the numbers of trails, into the 3D
 * view, each tilted to follow its way as the camera sees it.
 *
 * <p>The names used to be painted into the road texture, where they lay flat on the ground and
 * were foreshortened into illegibility whenever the terrain was seen at a low angle - which in
 * this app is always. Here they are drawn on the screen, upright to the viewer and at a readable
 * size, but turned to the way's average direction on screen, so a name still sits along its
 * road: the stretch of way around the label's spot (see {@code RoadLabelPlanner}) is projected,
 * and the text is laid along the principal axis of those points.
 *
 * <p>Trail and track labels are a little larger and sit on a translucent plate of the trail's
 * own colour - yellow, red or blue by difficulty, ochre for a track, whatever the user has set -
 * so a number reads as belonging to its trail at a glance, the way a waymark does. The text on
 * a plate is dark or white, whichever the plate's colour needs. Road and river names keep a
 * halo instead: a plate would compete with the road it names.
 *
 * <p>Which labels are shown is decided a few times a second, like the area labels: in range,
 * in front of the camera, not hidden behind terrain, not seen end-on, and not colliding with a
 * label already placed - roads first, then trails (numbers before names), then tracks, and labels
 * already on screen keep their places against newcomers of the same rank, so nothing flickers.
 * Between decisions the chosen labels are re-projected every frame, so they move and turn
 * smoothly with the camera.
 */
public class RoadNameRenderer {

    private static final int MAX_LABELS = 40;
    private static final long DECISION_MS = 350;
    /** A new label is not placed steeper than this... */
    private static final float MAX_TILT_DEG = 72f;
    /** ...but one on screen may lean this far round before it flips (see continueFrom). */
    private static final float HOLD_TILT_DEG = 100f;
    /** Labels float a little above the ground, clear of the terrain-occlusion test's own surface. */
    private static final float LIFT_METERS = 6f;
    /** Terrain lookups per decision: spreads the first sight of a busy area over a few frames. */
    private static final int WORLD_BUDGET_PER_DECISION = 320;

    /** Line height of road and river names, in widget units; and with large fonts on. */
    private static final float ROAD_TEXT_UNITS = 0.32f;
    private static final float ROAD_TEXT_UNITS_LARGE = 0.40f;
    /** Trail and track names and numbers: a little larger, since they sit on a plate. */
    private static final float TRAIL_TEXT_UNITS = 0.38f;
    private static final float TRAIL_TEXT_UNITS_LARGE = 0.46f;
    /** How opaque a trail's plate is: its colour clearly, the ground still showing through. */
    private static final float PLATE_ALPHA = 0.74f;

    private static final Color ROAD_TEXT = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color ROAD_HALO = new Color(1f, 1f, 1f, 0.92f);
    private static final Color WATER_TEXT = new Color(0.06f, 0.27f, 0.56f, 1f);
    private static final Color DARK_ON_PLATE = new Color(0.07f, 0.07f, 0.08f, 1f);
    private static final Color LIGHT_ON_PLATE = new Color(1f, 1f, 1f, 1f);
    /** Under white text on a plate: a soft drop shadow, so its edges stay crisp over the colour. */
    private static final Color PLATE_SHADOW = new Color(0f, 0f, 0f, 0.45f);

    /** A label placed on screen this frame. */
    private static final class Placed {
        RoadLabelCandidate candidate;
        /** What is written: the candidate's label, or its number alone when that is all that fits. */
        String text;
        float x, y, angle, width, height, scale, priority, distance;
        boolean incumbent;
        /** Trails and tracks: drawn on a plate of {@link #plate}, text dark or light to suit. */
        boolean plated;
        boolean darkText;
        float plateHalfWidth, plateHalfHeight;
        final Color plate = new Color();
        final RoadLabelGeometry.Box box = new RoadLabelGeometry.Box();
    }

    private final SpriteBatch batch;
    private final ShapeRenderer shapes;
    private final float widgetUnitStep;

    private final List<Placed> chosen = new ArrayList<>();
    private final List<Placed> visible = new ArrayList<>();
    private final List<Placed> pool = new ArrayList<>();
    private int poolUsed = 0;
    private final Map<RoadLabelCandidate, Float> angles = new IdentityHashMap<>();
    private long lastDecisionMs = 0L;
    private int drawnLastFrame = 0;
    private final List<String> drawnNames = new ArrayList<>();

    private float[] xs = new float[64];
    private float[] ys = new float[64];
    private final Vector3 tmp = new Vector3();
    private final RoadLabelGeometry.Fit fit = new RoadLabelGeometry.Fit();
    private final GlyphLayout glyph = new GlyphLayout();
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 identity = new Matrix4();
    /** {width, height} of a text at a scale, measured once; keyed by scale, then text. */
    private final Map<Float, Map<String, float[]>> sizeCache = new HashMap<>();
    /** Font scales for road and trail labels, set each frame from the font and preferences. */
    private float roadScale;
    private float trailScale;

    /**
     * What became of the candidates in range at the last decision, by reason - so "why is this
     * trail not labelled?" can be answered by asking rather than guessing.
     */
    public static final String[] STAT_NAMES = {
            "inRange", "noTerrainYet", "offScreen", "endOn", "tooSteep", "hiddenByTerrain",
            "crowdedOut", "shown"};
    private static final int IN_RANGE = 0, NO_TERRAIN = 1, OFF_SCREEN = 2, END_ON = 3,
            TOO_STEEP = 4, HIDDEN = 5, CROWDED = 6, SHOWN = 7;
    private final int[] stats = new int[STAT_NAMES.length];
    /** Why the last call to place() said no. */
    private int lastReject = OFF_SCREEN;

    public RoadNameRenderer(SpriteBatch batch, ShapeRenderer shapes, float widgetUnitStep) {
        this.batch = batch;
        this.shapes = shapes;
        this.widgetUnitStep = widgetUnitStep;
    }

    /** Labels drawn in the last frame. Render thread only. */
    public int drawnLastFrame() {
        return drawnLastFrame;
    }

    /** The names and numbers drawn in the last frame, in the order they were placed. */
    public List<String> drawnNames() {
        return new ArrayList<>(drawnNames);
    }

    /** The last decision's tally, as "inRange=… noTerrainYet=… … shown=…". Render thread only. */
    public String lastDecisionStats() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STAT_NAMES.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(STAT_NAMES[i]).append('=').append(stats[i]);
        }
        return sb.toString();
    }

    public void render() {
        drawnLastFrame = 0;
        drawnNames.clear();
        MapViewerScreen viewer = MapViewerSingleton.getViewerInstance();
        if (viewer == null || viewer.cam == null || getC().dataRetrieveThreadManager == null) {
            return;
        }
        if (!P.isViewerLayerVisibleBaseRoads() || !P.getRoadStyle().isRoadNames()) {
            chosen.clear();
            return;
        }
        // Over a photograph the view is about the peaks in the picture; street names would
        // only write over it.
        if (viewer.backgroundPicManager != null && viewer.backgroundPicManager.getBackgroundPixmap() != null) {
            return;
        }
        PerspectiveCameraExt cam = viewer.cam;
        BitmapFont font = getC().styleSingleton.getBitmapFontSmallWhite();
        float prevScaleX = font.getScaleX();
        float prevScaleY = font.getScaleY();
        setScales(font);
        try {
            long now = System.currentTimeMillis();
            boolean held = getC().dataRetrieveThreadManager.isLabelUpdatesHeld();
            if (held || now - lastDecisionMs >= DECISION_MS) {
                decide(cam, viewer.impactPixmap, font);
                lastDecisionMs = now;
            }
            draw(cam, font);
        } finally {
            font.getData().setScale(prevScaleX, prevScaleY);
            font.setColor(Color.WHITE);
        }
    }

    private void setScales(BitmapFont font) {
        font.getData().setScale(1f);
        float base = Math.max(1f, font.getLineHeight());
        boolean large = P.getViewLargeFonts();
        roadScale = (large ? ROAD_TEXT_UNITS_LARGE : ROAD_TEXT_UNITS) * widgetUnitStep / base;
        trailScale = (large ? TRAIL_TEXT_UNITS_LARGE : TRAIL_TEXT_UNITS) * widgetUnitStep / base;
    }

    /** {width, height} of a text at a scale, measured once. */
    private float[] size(BitmapFont font, float scale, String text) {
        Map<String, float[]> byText = sizeCache.get(scale);
        if (byText == null) {
            if (sizeCache.size() > 4) {
                sizeCache.clear(); // the scales changed (large fonts, a resize): start over
            }
            byText = new HashMap<>();
            sizeCache.put(scale, byText);
        }
        float[] s = byText.get(text);
        if (s == null) {
            font.getData().setScale(scale);
            glyph.setText(font, text);
            s = new float[]{glyph.width, glyph.height};
            byText.put(text, s);
        }
        return s;
    }

    private Placed obtain() {
        Placed p;
        if (poolUsed < pool.size()) {
            p = pool.get(poolUsed);
        } else {
            p = new Placed();
            pool.add(p);
        }
        poolUsed++;
        return p;
    }

    private void decide(PerspectiveCameraExt cam, ImpactPixmap impactPixmap, BitmapFont font) {
        float targetLat = getC().L.getTargetLatitude();
        double camLat = cam.position.y;
        double camLon = Units.convertLatitsToLonits(cam.position.x, targetLat);
        double cosLat = Math.cos(Math.toRadians(camLat));

        IdentityHashMap<RoadLabelCandidate, Boolean> incumbents = new IdentityHashMap<>();
        for (Placed p : chosen) {
            incumbents.put(p.candidate, Boolean.TRUE);
        }
        List<Placed> pending = new ArrayList<>();
        // The previous winners are copied out before the pool is reused.
        chosen.clear();
        poolUsed = 0;

        java.util.Arrays.fill(stats, 0);
        int worldBudget = WORLD_BUDGET_PER_DECISION;
        for (MapTile tile : getC().mapTileStorage.getMapTiles()) {
            if (tile.isDisposed()) {
                continue;
            }
            List<RoadLabelCandidate> candidates = tile.roadLabels;
            for (int i = 0; i < candidates.size(); i++) {
                RoadLabelCandidate c = candidates.get(i);
                double dy = (c.anchorLatitude() - camLat) * RoadGeo.METERS_PER_DEGREE;
                double dx = (c.anchorLongitude() - camLon) * RoadGeo.METERS_PER_DEGREE * cosLat;
                double range = rangeMeters(c.roadClass);
                if (dx * dx + dy * dy > range * range) {
                    continue;
                }
                stats[IN_RANGE]++;
                if (c.world == null || c.worldTargetLatitude != targetLat) {
                    if (worldBudget <= 0) {
                        stats[NO_TERRAIN]++;
                        continue;
                    }
                    worldBudget--;
                    if (!computeWorld(c, targetLat)) {
                        stats[NO_TERRAIN]++;
                        continue;
                    }
                }
                boolean incumbent = incumbents.containsKey(c);
                Placed p = obtain();
                if (!place(p, c, cam, font, incumbent)) {
                    stats[lastReject]++;
                    poolUsed--;
                    continue;
                }
                if (!visibleThroughTerrain(c, cam, impactPixmap)) {
                    stats[HIDDEN]++;
                    poolUsed--;
                    continue;
                }
                pending.add(p);
            }
        }

        // Rank first, then the labels already on screen, then the nearer.
        Collections.sort(pending, (a, b) -> {
            int ta = (int) a.priority, tb = (int) b.priority;
            if (ta != tb) {
                return Integer.compare(tb, ta);
            }
            if (a.incumbent != b.incumbent) {
                return a.incumbent ? -1 : 1;
            }
            if (a.priority != b.priority) {
                return Float.compare(b.priority, a.priority);
            }
            return Float.compare(a.distance, b.distance);
        });
        for (int i = 0; i < pending.size() && chosen.size() < MAX_LABELS; i++) {
            Placed p = pending.get(i);
            boolean blocked = false;
            for (int j = 0; j < chosen.size(); j++) {
                if (RoadLabelGeometry.overlaps(p.box, chosen.get(j).box)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                chosen.add(p);
            }
        }
        stats[SHOWN] = chosen.size();
        stats[CROWDED] = pending.size() - chosen.size();
        // Forget the angles of labels no longer shown, so one coming back starts upright.
        IdentityHashMap<RoadLabelCandidate, Boolean> kept = new IdentityHashMap<>();
        for (Placed p : chosen) {
            kept.put(p.candidate, Boolean.TRUE);
        }
        angles.keySet().retainAll(kept.keySet());
    }

    private static double rangeMeters(RoadClass roadClass) {
        switch (roadClass) {
            case ROAD:
                return 4500;
            case WATER:
                return 6000;
            case PATH:
                return 4500;
            default:
                return 3500;
        }
    }

    /**
     * World positions of the candidate's samples, on the terrain as it is loaded, lifted a few
     * metres. False while the terrain under any of them has not loaded yet.
     */
    private static boolean computeWorld(RoadLabelCandidate c, float targetLat) {
        float[] world = new float[c.size() * 3];
        for (int i = 0; i < c.size(); i++) {
            float ele = PhotoSkylineAligner.loadedTerrain().elevationMeters(c.lat[i], c.lon[i]);
            if (Float.isNaN(ele)) {
                return false;
            }
            float lat = (float) c.lat[i];
            float lon = (float) c.lon[i];
            world[3 * i] = (float) Units.convertLonitsToLatits(c.lon[i], targetLat);
            world[3 * i + 1] = lat;
            world[3 * i + 2] = Units.convertMetersToLatits(ele + LIFT_METERS)
                    - ElevationUtils.getElevationCorrectionForRoundEarth(lat, lon);
        }
        c.world = world;
        c.worldTargetLatitude = targetLat;
        return true;
    }

    /**
     * Projects the candidate and lays its label along the way: fills {@code p} and returns
     * true, or false when it cannot be shown here - behind the camera, off screen, seen end-on,
     * or too steep to read.
     *
     * <p>A label carrying a trail's number and its name that turns out too long for the stretch
     * of trail the camera sees is tried again as the number alone: the number is what a hiker
     * follows, and short enough to fit almost anywhere the trail is visible at all.
     */
    private boolean place(Placed p, RoadLabelCandidate c, PerspectiveCameraExt cam, BitmapFont font,
                          boolean incumbent) {
        float[] w = c.world;
        int a = c.anchor;
        lastReject = OFF_SCREEN;
        if (!inFront(cam, w[3 * a], w[3 * a + 1], w[3 * a + 2])) {
            return false;
        }
        cam.project(tmp.set(w[3 * a], w[3 * a + 1], w[3 * a + 2]));
        float ax = tmp.x, ay = tmp.y;
        int screenW = Gdx.graphics.getWidth(), screenH = Gdx.graphics.getHeight();
        if (ax < -0.05f * screenW || ax > 1.05f * screenW || ay < -0.05f * screenH || ay > 1.05f * screenH) {
            return false;
        }
        lastReject = END_ON;
        boolean plated = c.isTrail();
        float scale = plated ? trailScale : roadScale;
        String text = null;
        float tw = 0f, th = 0f, padX = 0f, padY = 0f;
        for (int attempt = 0; attempt < 2 && text == null; attempt++) {
            String candidateText = attempt == 0 ? c.text : c.shortText;
            if (candidateText == null) {
                break;
            }
            float[] size = size(font, scale, candidateText);
            tw = size[0];
            th = size[1];
            padX = plated ? 0.5f * th : 0f;
            padY = plated ? 0.32f * th : 0f;
            int count = projectStretch(c, cam, ax, ay, 0.6f * (tw + 2f * padX));
            if (!RoadLabelGeometry.fit(xs, ys, 0, count, fit)) {
                continue;
            }
            // A way seen end-on would carry its label across it rather than along it. A number
            // is short enough to be let off more lightly.
            boolean number = c.numberOnly || attempt == 1;
            if (fit.extent >= (number ? 0.35f : 0.55f) * tw) {
                text = candidateText;
            }
        }
        if (text == null) {
            return false;
        }
        Float previous = angles.get(c);
        float angle = RoadLabelGeometry.continueFrom(fit.angleDeg,
                previous == null ? Float.NaN : previous, HOLD_TILT_DEG);
        if (Math.abs(angle) > (incumbent ? HOLD_TILT_DEG : MAX_TILT_DEG)) {
            lastReject = TOO_STEEP;
            return false;
        }
        // Beside the way rather than over it, so the line stays visible under its label.
        double r = Math.toRadians(angle);
        float nx = (float) -Math.sin(r), ny = (float) Math.cos(r);
        float lift = 0.5f * th + padY + Math.max(2f, 0.06f * widgetUnitStep);
        p.candidate = c;
        p.text = text;
        p.x = ax + nx * lift;
        p.y = ay + ny * lift;
        p.angle = angle;
        p.width = tw;
        p.height = th;
        p.scale = scale;
        p.priority = c.priority();
        p.incumbent = incumbent;
        p.plated = plated;
        p.plateHalfWidth = 0.5f * tw + padX;
        p.plateHalfHeight = 0.5f * th + padY;
        if (plated) {
            RoadStyle style = P.getRoadStyle();
            int rgba = c.roadClass == RoadClass.TRACK
                    ? style.color(RoadStyle.Swatch.TRACKS) : style.trailColor(c.attribute);
            p.plate.set(rgba);
            p.plate.a = PLATE_ALPHA;
            p.darkText = RoadStyle.prefersDarkText(rgba);
        }
        float dxw = w[3 * a] - cam.position.x, dyw = w[3 * a + 1] - cam.position.y,
                dzw = w[3 * a + 2] - cam.position.z;
        p.distance = (float) Math.sqrt(dxw * dxw + dyw * dyw + dzw * dzw);
        // A plate is its own margin; a haloed name keeps a little air around it.
        float pad = plated ? Math.max(1.5f, 0.03f * widgetUnitStep) : Math.max(3f, 0.08f * widgetUnitStep);
        p.box.set(p.x, p.y, p.plateHalfWidth + pad, p.plateHalfHeight + pad, angle);
        return true;
    }

    /**
     * Projects the candidate's samples into {@link #xs}/{@link #ys}, keeping those that fall
     * within {@code reach} pixels of the anchor's projection (and the anchor itself): the
     * stretch of way the text will lie along. Returns how many were kept.
     */
    private int projectStretch(RoadLabelCandidate c, PerspectiveCameraExt cam, float ax, float ay,
                               float reach) {
        float[] w = c.world;
        int n = c.size();
        int a = c.anchor;
        if (xs.length < n) {
            xs = new float[n];
            ys = new float[n];
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (!inFront(cam, w[3 * i], w[3 * i + 1], w[3 * i + 2])) {
                continue;
            }
            cam.project(tmp.set(w[3 * i], w[3 * i + 1], w[3 * i + 2]));
            float ddx = tmp.x - ax, ddy = tmp.y - ay;
            if (i != a && ddx * ddx + ddy * ddy > reach * reach) {
                continue;
            }
            xs[count] = tmp.x;
            ys[count] = tmp.y;
            count++;
        }
        return count;
    }

    private static boolean inFront(PerspectiveCameraExt cam, float x, float y, float z) {
        return (x - cam.position.x) * cam.direction.x + (y - cam.position.y) * cam.direction.y
                + (z - cam.position.z) * cam.direction.z > 0f;
    }

    private boolean visibleThroughTerrain(RoadLabelCandidate c, PerspectiveCameraExt cam,
                                          ImpactPixmap impactPixmap) {
        if (impactPixmap == null || !impactPixmap.isReady()) {
            return true;
        }
        int a = c.anchor;
        float[] w = c.world;
        float dx = w[3 * a] - cam.position.x, dy = w[3 * a + 1] - cam.position.y,
                dz = w[3 * a + 2] - cam.position.z;
        float meters = Units.convertLatitsToMeters((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        return impactPixmap.checkIfDistanceIsVisible(meters, tmp.set(w[3 * a], w[3 * a + 1], w[3 * a + 2]));
    }

    private void draw(PerspectiveCameraExt cam, BitmapFont font) {
        // Fresh from this frame's camera; a label that has just left the view is skipped until
        // the next decision takes it off the list.
        visible.clear();
        for (int k = 0; k < chosen.size(); k++) {
            Placed p = chosen.get(k);
            if (place(p, p.candidate, cam, font, true)) {
                angles.put(p.candidate, p.angle);
                visible.add(p);
            }
        }
        if (visible.isEmpty()) {
            return;
        }

        // The plates first, all in one pass; the labels do not overlap, so none covers another's text.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        try {
            for (int k = 0; k < visible.size(); k++) {
                Placed p = visible.get(k);
                if (p.plated) {
                    shapes.setColor(p.plate);
                    fillPlate(p);
                }
            }
        } finally {
            shapes.end();
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);

        float halo = Math.max(1f, 0.028f * widgetUnitStep);
        float shadow = Math.max(1f, 0.02f * widgetUnitStep);
        batch.begin();
        try {
            for (int k = 0; k < visible.size(); k++) {
                Placed p = visible.get(k);
                RoadLabelCandidate c = p.candidate;
                font.getData().setScale(p.scale);
                transform.idt().translate(p.x, p.y, 0f).rotate(0f, 0f, 1f, p.angle);
                batch.setTransformMatrix(transform);
                float left = -0.5f * p.width;
                float top = 0.5f * p.height;
                if (p.plated) {
                    if (!p.darkText) {
                        font.setColor(PLATE_SHADOW);
                        font.draw(batch, p.text, left + shadow, top - shadow);
                    }
                    font.setColor(p.darkText ? DARK_ON_PLATE : LIGHT_ON_PLATE);
                    font.draw(batch, p.text, left, top);
                } else {
                    font.setColor(ROAD_HALO);
                    for (int i = 0; i < 8; i++) {
                        double t = i * Math.PI / 4.0;
                        font.draw(batch, p.text, left + halo * (float) Math.cos(t),
                                top + halo * (float) Math.sin(t));
                    }
                    font.setColor(c.roadClass == RoadClass.WATER ? WATER_TEXT : ROAD_TEXT);
                    font.draw(batch, p.text, left, top);
                }
                drawnLastFrame++;
                drawnNames.add(p.text);
            }
        } finally {
            batch.setTransformMatrix(identity);
            batch.end();
        }
    }

    /**
     * A stadium turned to the label's angle, as non-overlapping pieces - two triangles for the
     * middle, a half disc at each end - so the translucent fill blends evenly instead of
     * darkening where pieces would overlap.
     */
    private void fillPlate(Placed p) {
        float r = p.plateHalfHeight;
        float half = Math.max(0f, p.plateHalfWidth - r);
        double a = Math.toRadians(p.angle);
        float ux = (float) Math.cos(a), uy = (float) Math.sin(a);
        float vx = -uy, vy = ux;
        float x0 = p.x - ux * half, y0 = p.y - uy * half;
        float x1 = p.x + ux * half, y1 = p.y + uy * half;
        shapes.triangle(x0 - vx * r, y0 - vy * r, x1 - vx * r, y1 - vy * r, x1 + vx * r, y1 + vy * r);
        shapes.triangle(x0 - vx * r, y0 - vy * r, x1 + vx * r, y1 + vy * r, x0 + vx * r, y0 + vy * r);
        shapes.arc(x1, y1, r, p.angle - 90f, 180f, 20);
        shapes.arc(x0, y0, r, p.angle + 90f, 180f, 20);
    }
}
