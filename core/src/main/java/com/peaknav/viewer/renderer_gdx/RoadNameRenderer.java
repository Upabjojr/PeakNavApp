package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadGeo;
import com.peaknav.roads.RoadLabelCandidate;
import com.peaknav.roads.RoadLabelGeometry;
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
 * Writes the names of streets, tracks, trails and rivers into the 3D view, each tilted to follow
 * its way as the camera sees it.
 *
 * <p>The names used to be painted into the road texture, where they lay flat on the ground and
 * were foreshortened into illegibility whenever the terrain was seen at a low angle - which in
 * this app is always. Here they are drawn on the screen, upright to the viewer and at a readable
 * size, but turned to the way's average direction on screen, so a name still sits along its
 * road: the stretch of way around the label's spot (see {@code RoadLabelPlanner}) is projected,
 * and the text is laid along the principal axis of those points.
 *
 * <p>Which names are shown is decided a few times a second, like the area labels: in range, in
 * front of the camera, not hidden behind terrain, not seen end-on, and not colliding with a name
 * already placed - roads first, then trails, then tracks, and names already on screen keep their
 * places against newcomers of the same rank, so nothing flickers. Between decisions the chosen
 * names are re-projected every frame, so they move and turn smoothly with the camera.
 */
public class RoadNameRenderer {

    private static final int MAX_LABELS = 24;
    private static final long DECISION_MS = 350;
    /** A new name is not placed steeper than this... */
    private static final float MAX_TILT_DEG = 72f;
    /** ...but one on screen may lean this far round before it flips (see continueFrom). */
    private static final float HOLD_TILT_DEG = 100f;
    /** Names float a little above the ground, clear of the terrain-occlusion test's own surface. */
    private static final float LIFT_METERS = 6f;
    /** Terrain lookups per decision: spreads the first sight of a busy area over a few frames. */
    private static final int WORLD_BUDGET_PER_DECISION = 160;

    private static final Color ROAD_TEXT = new Color(0.10f, 0.10f, 0.11f, 1f);
    private static final Color ROAD_HALO = new Color(1f, 1f, 1f, 0.92f);
    private static final Color TRAIL_TEXT = new Color(0.36f, 0.08f, 0.05f, 1f);
    private static final Color TRACK_TEXT = new Color(0.30f, 0.17f, 0.06f, 1f);
    private static final Color WARM_HALO = new Color(1f, 0.97f, 0.90f, 0.92f);
    private static final Color WATER_TEXT = new Color(0.09f, 0.29f, 0.55f, 1f);

    /** A name placed on screen this frame. */
    private static final class Placed {
        RoadLabelCandidate candidate;
        float x, y, angle, width, height, priority, distance;
        boolean incumbent;
        final RoadLabelGeometry.Box box = new RoadLabelGeometry.Box();
    }

    private final SpriteBatch batch;
    private final float widgetUnitStep;

    private final List<Placed> chosen = new ArrayList<>();
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
    private final Map<String, float[]> sizeCache = new HashMap<>();
    private float sizeCacheScale = -1f;

    public RoadNameRenderer(SpriteBatch batch, float widgetUnitStep) {
        this.batch = batch;
        this.widgetUnitStep = widgetUnitStep;
    }

    /** Names drawn in the last frame. Render thread only. */
    public int drawnLastFrame() {
        return drawnLastFrame;
    }

    /** The names drawn in the last frame, in the order they were placed. Render thread only. */
    public List<String> drawnNames() {
        return new ArrayList<>(drawnNames);
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
        float scale = textScale(font);
        try {
            long now = System.currentTimeMillis();
            boolean held = getC().dataRetrieveThreadManager.isLabelUpdatesHeld();
            if (held || now - lastDecisionMs >= DECISION_MS) {
                decide(cam, viewer.impactPixmap, font, scale);
                lastDecisionMs = now;
            }
            draw(cam, font, scale);
        } finally {
            font.getData().setScale(prevScaleX, prevScaleY);
            font.setColor(Color.WHITE);
        }
    }

    private float textScale(BitmapFont font) {
        font.getData().setScale(1f);
        float base = font.getLineHeight();
        float target = (P.getViewLargeFonts() ? 0.40f : 0.32f) * widgetUnitStep;
        return target / Math.max(1f, base);
    }

    /** {width, height} of a name at the current scale, measured once. */
    private float[] size(BitmapFont font, float scale, String text) {
        if (scale != sizeCacheScale) {
            sizeCache.clear();
            sizeCacheScale = scale;
        }
        float[] s = sizeCache.get(text);
        if (s == null) {
            font.getData().setScale(scale);
            glyph.setText(font, text);
            s = new float[]{glyph.width, glyph.height};
            sizeCache.put(text, s);
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

    private void decide(PerspectiveCameraExt cam, ImpactPixmap impactPixmap, BitmapFont font, float scale) {
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
                if (c.world == null || c.worldTargetLatitude != targetLat) {
                    if (worldBudget <= 0) {
                        continue;
                    }
                    worldBudget--;
                    if (!computeWorld(c, targetLat)) {
                        continue;
                    }
                }
                boolean incumbent = incumbents.containsKey(c);
                Placed p = obtain();
                if (!place(p, c, cam, font, scale, incumbent)) {
                    poolUsed--;
                    continue;
                }
                if (!visibleThroughTerrain(c, cam, impactPixmap)) {
                    poolUsed--;
                    continue;
                }
                pending.add(p);
            }
        }

        // Rank first, then the names already on screen, then the nearer.
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
        // Forget the angles of names no longer shown, so one coming back starts upright.
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
                return 3200;
            default:
                return 2600;
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
     * Projects the candidate and lays its name along the way: fills {@code p} and returns true,
     * or false when it cannot be shown here - behind the camera, off screen, seen end-on, or
     * too steep to read.
     */
    private boolean place(Placed p, RoadLabelCandidate c, PerspectiveCameraExt cam, BitmapFont font,
                          float scale, boolean incumbent) {
        float[] w = c.world;
        int n = c.size();
        int a = c.anchor;
        if (!inFront(cam, w[3 * a], w[3 * a + 1], w[3 * a + 2])) {
            return false;
        }
        cam.project(tmp.set(w[3 * a], w[3 * a + 1], w[3 * a + 2]));
        float ax = tmp.x, ay = tmp.y;
        int screenW = Gdx.graphics.getWidth(), screenH = Gdx.graphics.getHeight();
        if (ax < -0.05f * screenW || ax > 1.05f * screenW || ay < -0.05f * screenH || ay > 1.05f * screenH) {
            return false;
        }
        float[] size = size(font, scale, c.text);
        float tw = size[0], th = size[1];

        // The stretch of way the text will cover: samples projected, kept while they fall
        // within the name's length of the anchor on screen.
        if (xs.length < n) {
            xs = new float[n];
            ys = new float[n];
        }
        int count = 0;
        float reach = 0.6f * tw;
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
        if (!RoadLabelGeometry.fit(xs, ys, 0, count, fit)) {
            return false;
        }
        // A way seen end-on would carry its name across it rather than along it.
        if (fit.extent < 0.55f * tw) {
            return false;
        }
        Float previous = angles.get(c);
        float angle = RoadLabelGeometry.continueFrom(fit.angleDeg,
                previous == null ? Float.NaN : previous, HOLD_TILT_DEG);
        if (Math.abs(angle) > (incumbent ? HOLD_TILT_DEG : MAX_TILT_DEG)) {
            return false;
        }
        // Beside the way rather than over it, so the line stays visible under its name.
        double r = Math.toRadians(angle);
        float nx = (float) -Math.sin(r), ny = (float) Math.cos(r);
        float lift = 0.5f * th + Math.max(2f, 0.08f * widgetUnitStep);
        p.candidate = c;
        p.x = ax + nx * lift;
        p.y = ay + ny * lift;
        p.angle = angle;
        p.width = tw;
        p.height = th;
        p.priority = c.priority();
        p.incumbent = incumbent;
        float dxw = w[3 * a] - cam.position.x, dyw = w[3 * a + 1] - cam.position.y,
                dzw = w[3 * a + 2] - cam.position.z;
        p.distance = (float) Math.sqrt(dxw * dxw + dyw * dyw + dzw * dzw);
        float pad = Math.max(3f, 0.1f * widgetUnitStep);
        p.box.set(p.x, p.y, 0.5f * tw + pad, 0.5f * th + pad, angle);
        return true;
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

    private void draw(PerspectiveCameraExt cam, BitmapFont font, float scale) {
        if (chosen.isEmpty()) {
            return;
        }
        float halo = Math.max(1f, 0.028f * widgetUnitStep);
        font.getData().setScale(scale);
        batch.begin();
        try {
            for (int k = 0; k < chosen.size(); k++) {
                Placed p = chosen.get(k);
                RoadLabelCandidate c = p.candidate;
                // Fresh from this frame's camera; a name that has just left the view is skipped
                // until the next decision takes it off the list.
                if (!place(p, c, cam, font, scale, true)) {
                    continue;
                }
                font.getData().setScale(scale);
                angles.put(c, p.angle);
                transform.idt().translate(p.x, p.y, 0f).rotate(0f, 0f, 1f, p.angle);
                batch.setTransformMatrix(transform);
                float left = -0.5f * p.width;
                float top = 0.5f * p.height;
                font.setColor(haloColor(c.roadClass));
                for (int i = 0; i < 8; i++) {
                    double t = i * Math.PI / 4.0;
                    font.draw(batch, c.text, left + halo * (float) Math.cos(t), top + halo * (float) Math.sin(t));
                }
                font.setColor(textColor(c.roadClass));
                font.draw(batch, c.text, left, top);
                drawnLastFrame++;
                drawnNames.add(c.text);
            }
        } finally {
            batch.setTransformMatrix(identity);
            batch.end();
        }
    }

    private static Color textColor(RoadClass roadClass) {
        switch (roadClass) {
            case PATH:
                return TRAIL_TEXT;
            case TRACK:
                return TRACK_TEXT;
            case WATER:
                return WATER_TEXT;
            default:
                return ROAD_TEXT;
        }
    }

    private static Color haloColor(RoadClass roadClass) {
        return (roadClass == RoadClass.PATH || roadClass == RoadClass.TRACK) ? WARM_HALO : ROAD_HALO;
    }
}
