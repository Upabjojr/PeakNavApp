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
import com.peaknav.contours.ContourLabelCandidate;
import com.peaknav.contours.ContourLabelPlanner;
import com.peaknav.contours.ContourLabels;
import com.peaknav.elevation.ElevationImageAbstract;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.geo.BoundingBox;
import com.peaknav.roads.RoadGeo;
import com.peaknav.roads.RoadLabelGeometry;
import com.peaknav.utils.PreferencesManager;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;
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
 * Writes the elevation of the contour lines along them, when the contour lines are on and the
 * menu asks for some labels.
 *
 * <p>The spots come from each loaded tile's own grid of elevations ({@link ContourLabelPlanner}),
 * planned once per tile and kept while the tile is. A spot is labelled only where the shader
 * draws its line in bold ({@link ContourLabels#labelled}): how many metres of height a pixel
 * spans there is worked out the way the shader's derivatives see it - the line's direction on
 * screen and a step uphill, projected - so as the camera moves, a label goes with the bold line
 * it names, and no label sits on a line drawn thin or not at all.
 *
 * <p>Each label lies on its line, turned along it, in the lines' brown on a light halo - the
 * line runs into the text from either side, as on a printed map. Which labels are shown is
 * decided a few times a second, like the road names': in range, in front, not behind terrain,
 * and clear of the labels already placed by a margin that grows as the amount asked for
 * shrinks; labels on screen keep their places against newcomers, then the nearer come first.
 * Between decisions the chosen labels are re-projected every frame.
 */
public class ContourLabelRenderer {

    private static final long DECISION_MS = 350;
    /** Tiles planned per decision: the first sight of a wide view is spread over a few. */
    private static final int TILES_PER_DECISION = 6;
    /** Terrain-to-world conversions per decision. */
    private static final int WORLD_BUDGET_PER_DECISION = 400;
    /** The farthest a label is written unzoomed; zoomed in, as much farther as the view is magnified. */
    private static final double RANGE_METERS = 8000;
    /** Labels float a little above their line, clear of the terrain-occlusion test's own surface. */
    private static final float LIFT_METERS = 4f;
    /** How far along the line and uphill the points are that tell its direction and steepness. */
    private static final float PROBE_METERS = 25f;
    private static final float MAX_TILT_DEG = 72f;
    private static final float HOLD_TILT_DEG = 100f;
    private static final float FULL_SIZE_METERS = 1500f;
    private static final float MIN_DISTANT_SCALE = 0.62f;
    private static final double BEARING_MARGIN_DEG = 12;
    private static final double NEAR_BEARING_METERS = 800;
    /** Line height of the text, in widget units; and with large fonts on. */
    private static final float TEXT_UNITS = 0.36f;
    private static final float TEXT_UNITS_LARGE = 0.44f;

    /** The shader's CONTOUR_COLOR, as it draws the bold lines (times 0.8), a shade darker to read. */
    private static final Color TEXT = new Color(0.36f, 0.21f, 0.08f, 1f);
    private static final Color HALO = new Color(1f, 0.98f, 0.94f, 0.9f);

    private static final class Placed {
        ContourLabelCandidate candidate;
        String text;
        float x, y, angle, width, height, scale, factor, distance;
        boolean incumbent;
        final RoadLabelGeometry.Box box = new RoadLabelGeometry.Box();
    }

    /** A tile's spots, and the elevation grid they were planned on. */
    private static final class Planned {
        ElevationImageAbstract image;
        List<ContourLabelCandidate> candidates;
    }

    private final SpriteBatch batch;
    private final float widgetUnitStep;

    private final Map<MapTile, Planned> planned = new IdentityHashMap<>();
    private final List<Placed> chosen = new ArrayList<>();
    private final List<Placed> visible = new ArrayList<>();
    private final List<Placed> pool = new ArrayList<>();
    private int poolUsed = 0;
    private final Map<ContourLabelCandidate, Float> angles = new IdentityHashMap<>();
    private long lastDecisionMs = 0L;
    private final List<String> drawnTexts = new ArrayList<>();

    private final Vector3 tmp = new Vector3();
    private final GlyphLayout glyph = new GlyphLayout();
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 identity = new Matrix4();
    private final Map<String, float[]> sizeCache = new HashMap<>();
    private float sizeCacheScale = Float.NaN;
    private float textScale;
    private float zoom = 1f;
    /** Filled by place(): the screen direction of the line and the metres of height per pixel. */
    private float lineAngle, metersPerPixel;

    public ContourLabelRenderer(SpriteBatch batch, float widgetUnitStep) {
        this.batch = batch;
        this.widgetUnitStep = widgetUnitStep;
    }

    /** The elevations written in the last frame, in the order they were placed. Render thread only. */
    public List<String> drawnTexts() {
        return new ArrayList<>(drawnTexts);
    }

    public void render() {
        drawnTexts.clear();
        MapViewerScreen viewer = MapViewerSingleton.getViewerInstance();
        int amount = P.getContourLabels();
        if (viewer == null || viewer.cam == null || getC().mapTileStorage == null
                || !P.isContourLines() || amount == ContourLabels.AMOUNT_OFF || !P.isLabelsVisible()) {
            chosen.clear();
            return;
        }
        PerspectiveCameraExt cam = viewer.cam;
        zoom = RoadLabelGeometry.zoom(cam.fieldOfView, viewer.getBaseFieldOfView());
        BitmapFont font = getC().styleSingleton.getBitmapFontSmallWhite();
        float prevScaleX = font.getScaleX();
        float prevScaleY = font.getScaleY();
        font.getData().setScale(1f);
        float base = Math.max(1f, font.getLineHeight());
        textScale = (P.getViewLargeFonts() ? TEXT_UNITS_LARGE : TEXT_UNITS) * widgetUnitStep / base;
        try {
            long now = System.currentTimeMillis();
            boolean held = getC().dataRetrieveThreadManager != null
                    && getC().dataRetrieveThreadManager.isLabelUpdatesHeld();
            if (held || now - lastDecisionMs >= DECISION_MS) {
                decide(cam, viewer.impactPixmap, font, amount);
                lastDecisionMs = now;
            }
            draw(cam, font);
        } finally {
            font.getData().setScale(prevScaleX, prevScaleY);
            font.setColor(Color.WHITE);
        }
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

    private void decide(PerspectiveCameraExt cam, ImpactPixmap impactPixmap, BitmapFont font, int amount) {
        float targetLat = getC().L.getTargetLatitude();
        double camLat = cam.position.y;
        double camLon = Units.convertLatitsToLonits(cam.position.x, targetLat);
        double cosLat = Math.cos(Math.toRadians(camLat));
        int stride = ContourLabels.stride(amount);
        int maxLabels = ContourLabels.maxLabels(amount);
        float spacing = ContourLabels.spacing(amount);

        IdentityHashMap<ContourLabelCandidate, Boolean> incumbents = new IdentityHashMap<>();
        for (Placed p : chosen) {
            incumbents.put(p.candidate, Boolean.TRUE);
        }
        chosen.clear();
        poolUsed = 0;
        List<Placed> pending = new ArrayList<>();

        float hx = cam.direction.x, hy = cam.direction.y;
        float level = (float) Math.sqrt(hx * hx + hy * hy);
        double aspect = Gdx.graphics.getWidth() / (double) Math.max(1, Gdx.graphics.getHeight());
        double halfDiagonal = Math.atan(Math.tan(Math.toRadians(cam.fieldOfView) * 0.5)
                * Math.sqrt(1.0 + aspect * aspect));
        double cosInView = level < 0.4f ? -2.0
                : Math.cos(Math.min(Math.PI, halfDiagonal + Math.toRadians(BEARING_MARGIN_DEG)));
        double range = RANGE_METERS * zoom;

        int tileBudget = TILES_PER_DECISION;
        int worldBudget = WORLD_BUDGET_PER_DECISION;
        List<MapTile> tiles = tilesNearestFirst(camLat, camLon, cosLat, range);
        // Forget the tiles the viewer has let go of.
        IdentityHashMap<MapTile, Boolean> held = new IdentityHashMap<>();
        for (MapTile t : getC().mapTileStorage.getMapTiles()) {
            held.put(t, Boolean.TRUE);
        }
        planned.keySet().retainAll(held.keySet());
        for (MapTile tile : tiles) {
            Planned plan = planned.get(tile);
            ElevationImageAbstract image = tile.elevationImage;
            if (plan == null || plan.image != image) {
                if (tileBudget <= 0) {
                    continue;
                }
                tileBudget--;
                plan = planTile(tile, image);
                if (plan == null) {
                    continue;
                }
                planned.put(tile, plan);
            }
            for (int i = 0; i < plan.candidates.size(); i++) {
                ContourLabelCandidate c = plan.candidates.get(i);
                if (!c.kept(stride)) {
                    continue;
                }
                double dy = (c.lat - camLat) * RoadGeo.METERS_PER_DEGREE;
                double dx = (c.lon - camLon) * RoadGeo.METERS_PER_DEGREE * cosLat;
                double d2 = dx * dx + dy * dy;
                if (d2 > range * range) {
                    continue;
                }
                if (d2 > NEAR_BEARING_METERS * NEAR_BEARING_METERS
                        && dx * hx + dy * hy < cosInView * Math.sqrt(d2) * level) {
                    continue;
                }
                if (c.world == null || c.worldTargetLatitude != targetLat) {
                    if (worldBudget <= 0) {
                        continue;
                    }
                    worldBudget--;
                    computeWorld(c, targetLat);
                }
                boolean incumbent = incumbents.containsKey(c);
                Placed p = obtain();
                if (!place(p, c, cam, font, incumbent, spacing)
                        || !visibleThroughTerrain(c, cam, impactPixmap)) {
                    poolUsed--;
                    continue;
                }
                pending.add(p);
            }
        }

        Collections.sort(pending, (a, b) -> {
            if (a.incumbent != b.incumbent) {
                return a.incumbent ? -1 : 1;
            }
            return Float.compare(a.distance, b.distance);
        });
        for (int i = 0; i < pending.size() && chosen.size() < maxLabels; i++) {
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
        IdentityHashMap<ContourLabelCandidate, Boolean> kept = new IdentityHashMap<>();
        for (Placed p : chosen) {
            kept.put(p.candidate, Boolean.TRUE);
        }
        angles.keySet().retainAll(kept.keySet());
    }

    /** The tiles with decoded elevations that may hold a label in range, nearest first. */
    private static List<MapTile> tilesNearestFirst(double camLat, double camLon, double cosLat,
                                                   double maxRangeMeters) {
        final List<MapTile> tiles = new ArrayList<>();
        final Map<MapTile, Double> meters = new IdentityHashMap<>();
        for (MapTile tile : getC().mapTileStorage.getMapTiles()) {
            if (tile.isDisposed() || tile.elevationImage == null) {
                continue;
            }
            MapTile.MapTileState state = tile.getMapTileState();
            if (state != MapTile.MapTileState.CAN_DRAW && state != MapTile.MapTileState.IS_DRAWN) {
                continue;
            }
            BoundingBox bb = tile.tile.getBoundingBox();
            double dy = ((bb.maxLatitude + bb.minLatitude) * 0.5 - camLat) * RoadGeo.METERS_PER_DEGREE;
            double dx = ((bb.maxLongitude + bb.minLongitude) * 0.5 - camLon)
                    * RoadGeo.METERS_PER_DEGREE * cosLat;
            double halfH = (bb.maxLatitude - bb.minLatitude) * 0.5 * RoadGeo.METERS_PER_DEGREE;
            double halfW = (bb.maxLongitude - bb.minLongitude) * 0.5 * RoadGeo.METERS_PER_DEGREE * cosLat;
            double nearest = Math.sqrt(dx * dx + dy * dy) - Math.sqrt(halfH * halfH + halfW * halfW);
            if (nearest > maxRangeMeters) {
                continue;
            }
            tiles.add(tile);
            meters.put(tile, nearest);
        }
        Collections.sort(tiles, (a, b) -> Double.compare(meters.get(a), meters.get(b)));
        return tiles;
    }

    /** Plans a tile's spots on its elevation grid; null when there is no grid to plan on. */
    private static Planned planTile(MapTile tile, ElevationImageAbstract image) {
        if (image == null) {
            return null;
        }
        int size = image.getEdgeLength();
        if (size < 3) {
            return null;
        }
        float[] elevations = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                elevations[y * size + x] = Units.convertLatitsToMeters(image.getTileMatrixElevationLatits(x, y));
            }
        }
        // The grid's vertex x sits x / size of the way across the tile (see computeMeshVertices):
        // its last column falls one step short of the east edge, its last row of the north.
        BoundingBox bb = image.boundingBox;
        double dLat = bb.maxLatitude - bb.minLatitude;
        double dLon = bb.maxLongitude - bb.minLongitude;
        Planned plan = new Planned();
        plan.image = image;
        plan.candidates = ContourLabelPlanner.plan(elevations, size,
                bb.minLatitude, bb.minLatitude + dLat * (size - 1) / size,
                bb.minLongitude, bb.minLongitude + dLon * (size - 1) / size);
        return plan;
    }

    /** World positions of the spot, of a point along its line and of one uphill. */
    private static void computeWorld(ContourLabelCandidate c, float targetLat) {
        float slope = c.slope();
        float ux = c.gradientEast / slope, uy = c.gradientNorth / slope;
        float[] w = new float[9];
        // Along the line: across the slope, at the line's own height.
        worldPoint(w, 0, c.lat, c.lon, c.level, targetLat);
        worldPoint(w, 3, c.lat + (ux * PROBE_METERS) / RoadGeo.METERS_PER_DEGREE,
                c.lon - (uy * PROBE_METERS) / (RoadGeo.METERS_PER_DEGREE * Math.cos(Math.toRadians(c.lat))),
                c.level, targetLat);
        worldPoint(w, 6, c.lat + (uy * PROBE_METERS) / RoadGeo.METERS_PER_DEGREE,
                c.lon + (ux * PROBE_METERS) / (RoadGeo.METERS_PER_DEGREE * Math.cos(Math.toRadians(c.lat))),
                c.level + slope * PROBE_METERS, targetLat);
        c.world = w;
        c.worldTargetLatitude = targetLat;
    }

    private static void worldPoint(float[] w, int at, double lat, double lon, float meters, float targetLat) {
        w[at] = (float) Units.convertLonitsToLatits(lon, targetLat);
        w[at + 1] = (float) lat;
        w[at + 2] = Units.convertMetersToLatits(meters + LIFT_METERS)
                - ElevationUtils.getElevationCorrectionForRoundEarth((float) lat, (float) lon);
    }

    private String text(ContourLabelCandidate c) {
        if (P.getUnitSystem() == PreferencesManager.UnitSystem.IMPERIAL) {
            return Math.round(c.level * 3.28084f) + " ft";
        }
        return Integer.toString(c.level);
    }

    private float[] size(BitmapFont font, String text) {
        if (sizeCacheScale != textScale) {
            sizeCache.clear();
            sizeCacheScale = textScale;
        }
        float[] s = sizeCache.get(text);
        if (s == null) {
            font.getData().setScale(textScale);
            glyph.setText(font, text);
            s = new float[]{glyph.width, glyph.height};
            sizeCache.put(text, s);
        }
        return s;
    }

    /**
     * Projects the spot and lays its label along the line: fills {@code p} and returns true, or
     * false when it cannot be shown - behind the camera, off screen, seen end-on, too steep to
     * read, or on a line the shader is not drawing in bold here.
     */
    private boolean place(Placed p, ContourLabelCandidate c, PerspectiveCameraExt cam, BitmapFont font,
                          boolean incumbent, float spacing) {
        if (!project(c, cam)) {
            return false;
        }
        if (!ContourLabels.labelled(c.level, metersPerPixel)) {
            return false;
        }
        float[] w = c.world;
        float ex = w[0] - cam.position.x, ey = w[1] - cam.position.y, ez = w[2] - cam.position.z;
        float meters = Units.convertLatitsToMeters((float) Math.sqrt(ex * ex + ey * ey + ez * ez));
        float factor = RoadLabelGeometry.distanceScale(meters / zoom, FULL_SIZE_METERS, MIN_DISTANT_SCALE);
        String text = text(c);
        float[] size = size(font, text);
        Float previous = incumbent ? angles.get(c) : null;
        float angle = RoadLabelGeometry.continueFrom(lineAngle,
                previous == null ? Float.NaN : previous, HOLD_TILT_DEG);
        if (Math.abs(angle) > (incumbent ? HOLD_TILT_DEG : MAX_TILT_DEG)) {
            return false;
        }
        p.candidate = c;
        p.text = text;
        p.x = tmp.x;
        p.y = tmp.y;
        p.angle = angle;
        p.width = size[0] * factor;
        p.height = size[1] * factor;
        p.scale = textScale * factor;
        p.factor = factor;
        p.distance = meters;
        p.incumbent = incumbent;
        float pad = spacing * p.height;
        p.box.set(p.x, p.y, 0.5f * p.width + pad, 0.5f * p.height + pad, angle);
        return true;
    }

    /**
     * Projects the spot into {@link #tmp}, and sets {@link #lineAngle} and
     * {@link #metersPerPixel}: the height a pixel spans is the rise of the step uphill over how
     * far its projection reaches across the line's, as the elevation's screen gradient would be
     * on a plane. False when the spot is behind the camera, off screen or its line seen end-on.
     */
    private boolean project(ContourLabelCandidate c, PerspectiveCameraExt cam) {
        float[] w = c.world;
        for (int i = 0; i < 9; i += 3) {
            if (!inFront(cam, w[i], w[i + 1], w[i + 2])) {
                return false;
            }
        }
        cam.project(tmp.set(w[3], w[4], w[5]));
        float lx = tmp.x, ly = tmp.y;
        cam.project(tmp.set(w[6], w[7], w[8]));
        float gx = tmp.x, gy = tmp.y;
        cam.project(tmp.set(w[0], w[1], w[2]));
        int screenW = Gdx.graphics.getWidth(), screenH = Gdx.graphics.getHeight();
        if (tmp.x < 0f || tmp.x > screenW || tmp.y < 0f || tmp.y > screenH) {
            return false;
        }
        float tx = lx - tmp.x, ty = ly - tmp.y;
        float tLen = (float) Math.sqrt(tx * tx + ty * ty);
        if (tLen < 1f) {
            return false;
        }
        tx /= tLen;
        ty /= tLen;
        float ux = gx - tmp.x, uy = gy - tmp.y;
        float across = Math.abs(ux * ty - uy * tx);
        if (across < 1e-3f) {
            return false;
        }
        metersPerPixel = c.slope() * PROBE_METERS / across;
        lineAngle = (float) Math.toDegrees(Math.atan2(ty, tx));
        return true;
    }

    private static boolean inFront(PerspectiveCameraExt cam, float x, float y, float z) {
        return (x - cam.position.x) * cam.direction.x + (y - cam.position.y) * cam.direction.y
                + (z - cam.position.z) * cam.direction.z > 0f;
    }

    private boolean visibleThroughTerrain(ContourLabelCandidate c, PerspectiveCameraExt cam,
                                          ImpactPixmap impactPixmap) {
        if (impactPixmap == null || !impactPixmap.isReady()) {
            return true;
        }
        float[] w = c.world;
        float dx = w[0] - cam.position.x, dy = w[1] - cam.position.y, dz = w[2] - cam.position.z;
        float meters = Units.convertLatitsToMeters((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        return impactPixmap.checkIfDistanceIsVisible(meters, tmp.set(w[0], w[1], w[2]));
    }

    private void draw(PerspectiveCameraExt cam, BitmapFont font) {
        visible.clear();
        float spacing = ContourLabels.spacing(P.getContourLabels());
        for (int k = 0; k < chosen.size(); k++) {
            Placed p = chosen.get(k);
            if (place(p, p.candidate, cam, font, true, spacing)) {
                angles.put(p.candidate, p.angle);
                visible.add(p);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        batch.begin();
        try {
            for (int k = 0; k < visible.size(); k++) {
                Placed p = visible.get(k);
                float halo = Math.max(1f, 0.028f * widgetUnitStep * p.factor);
                font.getData().setScale(p.scale);
                transform.idt().translate(p.x, p.y, 0f).rotate(0f, 0f, 1f, p.angle);
                batch.setTransformMatrix(transform);
                float left = -0.5f * p.width;
                float top = 0.5f * p.height;
                font.setColor(HALO);
                for (int i = 0; i < 8; i++) {
                    double t = i * Math.PI / 4.0;
                    font.draw(batch, p.text, left + halo * (float) Math.cos(t),
                            top + halo * (float) Math.sin(t));
                }
                font.setColor(TEXT);
                font.draw(batch, p.text, left, top);
                drawnTexts.add(p.text);
            }
        } finally {
            batch.setTransformMatrix(identity);
            batch.end();
        }
    }
}
