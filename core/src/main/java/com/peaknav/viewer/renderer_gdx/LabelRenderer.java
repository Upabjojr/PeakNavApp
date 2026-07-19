package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLoadFactory;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.utils.Units.deg2rad;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.areas.MapArea;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.render_tiles.ImpactPixmap;
import com.peaknav.viewer.labels.DrawLabel;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.screens.BackgroundPicManager;

import java.util.List;

public class LabelRenderer {

    private final SpriteBatch spriteBatch;
    private final ShapeRenderer shapeRenderer;
    private final Texture compassTexture;
    private final float widgetUnitStep;

    private float x, y;
    private final float w, h;
    private float backgroundAlpha = 0.6f;
    private float angle = 0;
    private float timeElapsed = 0;
    private static final float TILT_LIMIT = 0.9995f;

    public LabelRenderer(
            SpriteBatch spriteBatch, ShapeRenderer shapeRenderer, Texture compassTexture,
            float widgetUnitStep) {
        this.spriteBatch = spriteBatch;
        this.shapeRenderer = shapeRenderer;
        this.compassTexture = compassTexture;

        // TODO: these values are affected by window resizing!
        this.widgetUnitStep = widgetUnitStep;
        w = 1.3f * widgetUnitStep;
        h = w;
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private void drawWayLabels(int currentAngle, SpriteBatch spriteBatch) {
        //  TODO: in MapViewerScreen there should be only one for-loop over peak data:
        // Bucketed by angle, so every POI here already matches currentAngle.
        getC().O.iterateOverDisplayablePoisForAngle(currentAngle, poiObject -> {
            DrawLabel drawLabel = poiObject.drawLabel;
            if (!drawLabel.isVisible())
                return;
            drawLabel.drawOnSpriteBatch(spriteBatch);
        });
    }

    private void drawDisplayablePoiVerticalLines(int currentAngle) {
        getC().O.iterateOverDisplayablePoisForAngle(currentAngle, poiObject -> {
            DrawLabel drawLabel = poiObject.drawLabel;
            if (!drawLabel.isVisible())
                return;
            float upperPos = drawLabel.getScreenLabelY();
            float screenPoiX = drawLabel.getScreenPoiX();
            float screenPoiY = drawLabel.getScreenPoiY();
            shapeRenderer.setColor(drawLabel.drawLabelCategory.getBackgroundColor());
            shapeRenderer.rect(screenPoiX-1, screenPoiY, 3, upperPos-screenPoiY);
        });
    }

    private void drawDisplayablePoiRectangles(int angle) {
        getC().O.iterateOverDisplayablePoisForAngle(angle, poiObject -> {
            if (!poiObject.drawLabel.isVisible())
                return;
            if (poiObject.drawLabel.lock.tryLock()) {
                try {
                    poiObject.drawLabel.drawRectangle(shapeRenderer);
                } finally {
                    poiObject.drawLabel.lock.unlock();
                }
            }
        });
    }

    public void render(float deltaTime) {
        // renderBackgroundPixmap();
        renderAreas();
        renderLabelLines();
        renderLabelTexts();
        renderHorizonCompass();
        if (getAppState().isLoadingMapData()) {
            renderLoading(deltaTime);
        } else {
            angle = 0;
        }
        renderCompass();
        renderSkyClock();
    }

    private final GlyphLayout clockGlyph = new GlyphLayout();

    // The clock text is rebuilt only when the minute changes: constructing a SimpleDateFormat is
    // expensive (pattern compile + Calendar + locale data) and doing it every frame at 60 fps was
    // significant steady-state garbage while a custom sky time was set.
    private final java.text.SimpleDateFormat clockFormat =
            new java.text.SimpleDateFormat("yyyy-MM-dd  HH:mm", java.util.Locale.getDefault());
    private final java.util.Date clockDate = new java.util.Date();
    private long clockCachedMinute = Long.MIN_VALUE;
    private String clockText = "";

    /**
     * When the sky is frozen at a user-chosen time (via "..." → Set time), shows that date and time
     * on a small pill near the top of the screen, so it is clear the sky is not the live one. Nothing
     * is drawn while the sky follows the device clock.
     */
    private void renderSkyClock() {
        com.peaknav.sky.SkyModel sky = getC().skyModel;
        if (sky == null || !sky.hasCustomTime())
            return;
        long millis = sky.currentTimeMillis();
        long minute = millis / 60000L;
        if (minute != clockCachedMinute) {
            clockCachedMinute = minute;
            clockDate.setTime(millis);
            clockText = clockFormat.format(clockDate);
        }
        String text = clockText;
        // A white font (the small font is baked black, so tinting it can't lighten it).
        BitmapFont font = getC().styleSingleton.getBitmapFontSmallWhite();
        clockGlyph.setText(font, text);
        float tw = clockGlyph.width;
        float th = clockGlyph.height;
        float padX = 0.4f * widgetUnitStep;
        float padY = 0.18f * widgetUnitStep;
        float pw = tw + 2f * padX;
        float ph = th + 2f * padY;
        float cx = Gdx.graphics.getWidth() * 0.5f;
        float px = cx - pw * 0.5f;
        // Sit below the top button row (camera/gallery live top-left) so nothing covers it, in both
        // portrait and landscape.
        float py = Gdx.graphics.getHeight() - ph - 1.6f * widgetUnitStep;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        try {
            // Stadium via fillPill (non-overlapping pieces) — no double-blended "knobs" at the ends.
            shapeRenderer.setColor(0.05f, 0.06f, 0.13f, 0.78f);
            fillPill(px, py, pw, ph, ph * 0.5f);
        } finally {
            shapeRenderer.end();
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);

        spriteBatch.setTransformMatrix(identityMat);
        spriteBatch.begin();
        try {
            font.setColor(Color.WHITE);
            font.draw(spriteBatch, text, cx - tw * 0.5f, py + ph * 0.5f + th * 0.5f);
        } finally {
            spriteBatch.end();
        }
    }

    private static final int D = 2;

    private final Vector3 wcoords = new Vector3();

    public void renderLevelingLine() {
        // TODO: this could be more efficient if computed only when camera moves!
        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        wcoords.set(cam.up);
        wcoords.crs(cam.direction);
        wcoords.nor();
        float x = (float) Math.sqrt(wcoords.x*wcoords.x + wcoords.y*wcoords.y);

        if (x < TILT_LIMIT && x > -TILT_LIMIT) {
            float z = wcoords.z;

            int w = Gdx.graphics.getWidth();
            int h = Gdx.graphics.getHeight();
            float w2 = w/2f;
            float h2 = h/2f;

            float len = 0.25f*w;
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
            shapeRenderer.setColor(Color.RED);

            for (int d = -D; d <= D; d++) {
                shapeRenderer.line(w2-len*x, h2+d-len*z, w2+len*x, h2+d+len*z);
            }

            shapeRenderer.end();
        }
    }

    private void renderLoading(float deltaTime) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        final float radius = 2*widgetUnitStep;
        final float smallRadius = 0.4f*widgetUnitStep;

        float width = Gdx.graphics.getWidth();
        float height = Gdx.graphics.getHeight();

        float x = width/2;
        float y = height/2;

        timeElapsed += deltaTime;
        timeElapsed %= 2.f;
        float outerRotAngle = -90f* Interpolation.circle.apply(
                (timeElapsed > 1.f)? (2.f - timeElapsed) : timeElapsed);
        outerRotAngle %= 360f;

        shapeRenderer.setColor(Color.BLUE);
        final int N = 8;
        for (int i = 0; i < N; i++) {
            float angle = deg2rad * (outerRotAngle + 360f*i/N);
            shapeRenderer.circle(
                    x+(radius) * (float) Math.cos(angle),
                    y+(radius) * (float) Math.sin(angle),
                    smallRadius);
        }
        shapeRenderer.end();

    }

    public void renderBackgroundPixmap() {
        BackgroundPicManager backgroundPicManager = MapViewerSingleton.getViewerInstance().backgroundPicManager;
        Texture background = backgroundPicManager.getBackgroundTexture();
        if (background == null) {
            Pixmap bg = backgroundPicManager.getBackgroundPixmap();
            if (bg == null)
                return;
            background = new Texture(bg);
            backgroundPicManager.setBackgroundTexture(background);
        }
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        int iw = backgroundPicManager.getWidth(), ih = backgroundPicManager.getHeight();

        spriteBatch.begin();
        spriteBatch.setColor(1, 1, 1, 1);  // getBackgroundAlpha();
        spriteBatch.draw(
                new TextureRegion(background),
                (sw - iw)/2f, (sh - ih)/2f,
                0, 0,
                iw, ih,
                1, 1, 0
        );
        spriteBatch.end();
        spriteBatch.setColor(1, 1, 1, 1);
    }

    private final TextureRegion compassTextureRegion = new TextureRegion();
    // Reused identity transform for the compass; never mutated, so a single instance is safe
    // (setTransformMatrix copies the values into the batch). Avoids a per-frame allocation.
    private final Matrix4 identityMat = new Matrix4();

    private void renderCompass() {

        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        float angle2 = cam.getAngleForCompass2();
        float deltaAngle = cam.getAngleForCompassDelta();

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        try {
            shapeRenderer.setColor(Color.RED);
            shapeRenderer.arc(x, y, w/2f, angle2, deltaAngle);
        } catch (Throwable throwable) {
            System.err.println("error!");
        } finally {
            shapeRenderer.end();
        }
        spriteBatch.setTransformMatrix(identityMat);
        spriteBatch.begin();
        try {
            compassTextureRegion.setRegion(compassTexture);
            spriteBatch.draw(
                    compassTextureRegion,
                    x - w/2, y - h/2,
                    w/2, h/2,
                    h, h,
                    1, 1,
                    0
            );
        } catch (Throwable throwable) {
            // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "spriteBatch-compass");
            // crashLogger.logToFile();
        } finally {
            spriteBatch.end();
        }
    }

    // ---- Named-area overlay --------------------------------------------------------------------
    // Areas (from areas.json) — islands, mountain groups, regions, … — are labelled with a rounded
    // pill floating above the area. The configured ellipse is used only to locate the area and size
    // its label; it is never drawn. Each area's `type` selects the pill colour, so different kinds
    // of area read as visually distinct. The peak/town labels alone never say which island or range
    // you are looking at.
    private static final int AREA_SEGMENTS = 48;
    private static final float KM_PER_DEG_LAT = 111.32f;
    private final Vector3 areaTmp = new Vector3();
    private final GlyphLayout areaGlyph = new GlyphLayout();
    private static final Color AREA_TEXT = new Color(0.99f, 0.99f, 1.0f, 1f);
    private static final Color AREA_TEXT_SHADOW = new Color(0.0f, 0.04f, 0.09f, 0.95f);

    /** A type's label colour: one uniform translucent fill, dark enough for the white name. */
    private static final class AreaPalette {
        final Color fill;
        AreaPalette(Color fill) {
            this.fill = fill;
        }
    }

    // Per-type colours. Add an entry here for each new `type` used in areas.json; unknown types get
    // AREA_PALETTE_DEFAULT.
    private static final java.util.Map<String, AreaPalette> AREA_PALETTES = new java.util.HashMap<>();
    static {
        AREA_PALETTES.put("island", new AreaPalette(new Color(0.06f, 0.20f, 0.34f, 0.72f)));         // deep teal-blue
        AREA_PALETTES.put("mountain_range", new AreaPalette(new Color(0.30f, 0.14f, 0.04f, 0.76f))); // earthy brown
        AREA_PALETTES.put("mountain_group", new AreaPalette(new Color(0.28f, 0.17f, 0.05f, 0.74f))); // warm amber-brown
        AREA_PALETTES.put("city", new AreaPalette(new Color(0.24f, 0.07f, 0.30f, 0.76f)));           // violet
        AREA_PALETTES.put("region", new AreaPalette(new Color(0.09f, 0.25f, 0.12f, 0.72f)));         // deep green
        AREA_PALETTES.put("lake", new AreaPalette(new Color(0.05f, 0.16f, 0.40f, 0.74f)));           // water blue
    }
    private static final AreaPalette AREA_PALETTE_DEFAULT =
            new AreaPalette(new Color(0.10f, 0.13f, 0.18f, 0.72f));

    private AreaPalette paletteFor(String type) {
        if (type == null)
            return AREA_PALETTE_DEFAULT;
        AreaPalette p = AREA_PALETTES.get(type.toLowerCase(java.util.Locale.ROOT));
        return (p != null) ? p : AREA_PALETTE_DEFAULT;
    }

    /** Whether this area type's labels are currently enabled in the label-visibility submenu. */
    private boolean isTypeVisible(String type) {
        if (type == null)
            return true;
        String t = type.toLowerCase(java.util.Locale.ROOT);
        if (t.equals("city"))
            return P.isVisibleCities();
        if (t.equals("mountain_range") || t.equals("mountain_group"))
            return P.isVisibleMountainRanges();
        if (t.equals("island"))
            return P.isVisibleIslands();
        if (t.equals("lake"))
            return P.isVisibleLakes();
        return true; // region/unknown types are always shown
    }

    /**
     * Labels each nearby area whose highest point is above the horizon, is not hidden by terrain,
     * and is within its own relevance range. Areas are pulled tile by tile from {@link AreaRegistry}
     * around the current target, so only nearby ones are ever loaded. The ellipse only
     * locates/sizes the label — it is not drawn — and the name floats above the area on a
     * type-coloured pill.
     */
    /** A measured area label awaiting the de-overlap pass. */
    private static final class PendingArea {
        String name;
        AreaPalette palette;
        float rx, ry, rw, rh;      // plate rectangle on screen (what is drawn)
        float crx, cry, crw, crh;  // tighter text rectangle used for collisions
        float scale, textW, textH; // for drawing the name at the same size it was measured
        int priority;              // higher wins a collision (islands/ranges over towns)
        float importance;          // tie-break within a priority (visible range)
    }

    private final java.util.List<PendingArea> areaPool = new java.util.ArrayList<>();
    private final java.util.List<PendingArea> areaPending = new java.util.ArrayList<>();
    private final java.util.List<PendingArea> areaAccepted = new java.util.ArrayList<>();
    private int areaPoolUsed = 0;

    private PendingArea obtainPending() {
        PendingArea p;
        if (areaPoolUsed < areaPool.size()) {
            p = areaPool.get(areaPoolUsed);
        } else {
            p = new PendingArea();
            areaPool.add(p);
        }
        areaPoolUsed++;
        return p;
    }

    /** Collision priority: islands and mountain ranges prevail over towns. */
    private static int areaPriority(String type) {
        if ("island".equals(type) || "mountain_range".equals(type)) return 3;
        if ("mountain_group".equals(type) || "region".equals(type) || "lake".equals(type)) return 2;
        return 1; // city / default
    }

    private static boolean areaLabelsOverlap(PendingArea a, PendingArea b) {
        // Compared on the tight text rectangles, not the wide plates, so a big area's pill does not
        // suppress neighbours whose names sit well clear of it.
        return a.crx < b.crx + b.crw && a.crx + a.crw > b.crx
                && a.cry < b.cry + b.crh && a.cry + a.crh > b.cry;
    }

    private void renderAreas() {
        float targetLat = getC().L.getTargetLatitude();
        float targetLon = (float) getC().L.getTargetLongitude();
        List<MapArea> areas = getC().areaRegistry.getAreasNear(targetLat, targetLon);
        if (areas.isEmpty())
            return;
        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        if (cam == null)
            return;

        float cosTargetLat = (float) Math.cos(Math.toRadians(targetLat));
        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();

        // Elevation opens up the view: the higher the camera, the farther the relevance ranges reach,
        // so lifting your viewpoint progressively reveals more distant area labels. At ground level
        // the factor is ~1; it roughly doubles a few km up.
        float camHeightMeters = Math.max(0f, Units.convertLatitsToMeters(cam.position.z));
        float altitudeRangeFactor = Math.min(8f, 1f + camHeightMeters / 3500f);

        // Pass 1: measure every label that survives the culls; the actual drawing happens after the
        // de-overlap pass so a label hidden behind a higher-priority one is dropped, not stacked.
        areaPending.clear();
        areaPoolUsed = 0;

        for (MapArea area : areas) {
            // Type toggle: skip whole categories the user has switched off.
            if (!isTypeVisible(area.type))
                continue;
            if (area.name == null || area.name.isEmpty())
                continue;

            // Relevance cull: each area carries its own visible range (small islet: only near; big
            // range: from far), stretched by the camera's elevation so climbing reveals more.
            // Δlon is wrapped across the antimeridian, so an island at lon −179.9° is 22 km from a
            // viewer at 179.9°, not 40 000 km (the registry already loads wrapped tiles there).
            float dLonDeg = area.lon - targetLon;
            if (dLonDeg > 180f) dLonDeg -= 360f;
            else if (dLonDeg < -180f) dLonDeg += 360f;
            // The area's longitude unwrapped next to the viewer: keeps the distance maths AND the
            // world-X projection continuous across the date line.
            float areaLon = targetLon + dLonDeg;
            float dLatKm = (area.lat - targetLat) * KM_PER_DEG_LAT;
            float dLonKm = dLonDeg * KM_PER_DEG_LAT * cosTargetLat;
            // Capped at the registry's tile-loading reach: past it the area's tile would not even
            // be loaded, so a larger effective range only promises labels that cannot appear.
            float effRangeKm = Math.min(area.visibleRangeKm * altitudeRangeFactor,
                    MapArea.MAX_RANGE_KM);
            if (dLatKm * dLatKm + dLonKm * dLonKm > effRangeKm * effRangeKm)
                continue;

            // Centre at sea level (elevation 0, round-earth corrected).
            float centreCorr = ElevationUtils.getElevationCorrectionForRoundEarth(area.lat, areaLon);
            float centreX = (float) Units.convertLonitsToLatits(areaLon, targetLat);
            float centreY = area.lat;
            float centreZ = -centreCorr;
            float toX = centreX - cam.position.x;
            float toY = centreY - cam.position.y;

            // Below-horizon cull: drop the area once its highest point is hidden by the earth's
            // curvature. A point of height h stays above the sea horizon while within sqrt(2 R h)
            // of the viewer, so the area is visible while the camera-to-area distance is under the
            // camera's horizon reach plus the peak's.
            float distMeters = Units.convertLatitsToMeters(
                    (float) Math.sqrt(toX * toX + toY * toY));
            // Islands are visible landmasses; when the data lacks a peak height, assume a modest one
            // so a low island is still spotted from a boat well out to sea, not only within the
            // sea-level horizon of its (missing) elevation.
            float effPeakMeters = "island".equals(area.type)
                    ? Math.max(area.peakMeters, 200f) : area.peakMeters;
            float horizonReach = (float) (Math.sqrt(2.0 * Units.radiusOfEarth * camHeightMeters)
                    + Math.sqrt(2.0 * Units.radiusOfEarth * effPeakMeters));
            if (distMeters > horizonReach)
                continue;

            // Ellipse boundary (used only to locate and size the area on screen — never drawn).
            // Local (east, north) km, major axis rotated CCW from East. The cosine is clamped so
            // an area at the poles cannot divide by ~zero into NaN longitudes.
            float kmPerDegLon = KM_PER_DEG_LAT
                    * Math.max(1e-3f, (float) Math.cos(Math.toRadians(area.lat)));
            float rot = (float) Math.toRadians(area.rotationDeg);
            float cosR = (float) Math.cos(rot);
            float sinR = (float) Math.sin(rot);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            int inFront = 0;
            for (int k = 0; k < AREA_SEGMENTS; k++) {
                float t = (float) (2.0 * Math.PI * k / AREA_SEGMENTS);
                float localE = area.semiMajorKm * (float) Math.cos(t);
                float localN = area.semiMinorKm * (float) Math.sin(t);
                float eastKm = localE * cosR - localN * sinR;
                float northKm = localE * sinR + localN * cosR;
                float ptLat = area.lat + northKm / KM_PER_DEG_LAT;
                float ptLon = areaLon + eastKm / kmPerDegLon;
                float corr = ElevationUtils.getElevationCorrectionForRoundEarth(ptLat, ptLon);
                float wx = (float) Units.convertLonitsToLatits(ptLon, targetLat);
                // Skip boundary points behind the camera: projecting them divides by a negative w
                // and mirrors the screen coordinates, which used to blow the silhouette box up
                // (huge or misplaced pills when standing on/inside a large area).
                float rx = wx - cam.position.x;
                float ry = ptLat - cam.position.y;
                float rz = -corr - cam.position.z;
                if (rx * cam.direction.x + ry * cam.direction.y + rz * cam.direction.z <= 0f)
                    continue;
                inFront++;
                areaTmp.set(wx, ptLat, -corr);
                cam.project(areaTmp);
                if (areaTmp.x < minX) minX = areaTmp.x;
                if (areaTmp.x > maxX) maxX = areaTmp.x;
                if (areaTmp.y < minY) minY = areaTmp.y;
                if (areaTmp.y > maxY) maxY = areaTmp.y;
            }
            // Off-screen cull (with a margin so a partially visible area still labels). With no
            // boundary point in front (camera inside/right on top of the area) the box is empty,
            // so the cull is skipped and the label is placed from the summit alone below.
            if (inFront > 0
                    && (maxX < -0.1f * screenW || minX > 1.1f * screenW
                    || maxY < -0.1f * screenH || minY > 1.1f * screenH))
                continue;

            // Place the label just above the area's on-screen silhouette, so it never covers the
            // terrain — at any distance or camera pitch (a fixed world-height lift collapses to a
            // few pixels when the area is far or seen from straight above). The silhouette top is
            // the higher (on screen) of the sea-level footprint's top edge and the projected summit:
            // from the side the summit wins, from straight above the footprint does. Gate on the
            // summit being inside the frustum, so no ghost pill shows when you face away.
            float summitZ = centreZ + Units.convertMetersToLatits(area.peakMeters);
            if (!cam.frustum.pointInFrustum(centreX, centreY, summitZ))
                continue;
            // Hidden-by-terrain cull: like the peak/place labels, drop the area when it is entirely
            // occluded by nearer mountains.
            if (!areaHasVisiblePoint(area, areaLon, targetLat, centreX, centreY, centreZ, cam))
                continue;
            areaTmp.set(centreX, centreY, summitZ);
            cam.project(areaTmp);
            float summitX = areaTmp.x;
            float summitY = areaTmp.y;

            // top of the area on screen (y-up); summit-only when the footprint is behind the camera
            float silhouetteTop = (inFront > 0) ? Math.max(maxY, summitY) : summitY;
            float plateBottom = silhouetteTop + 0.30f * widgetUnitStep; // fixed pixel clearance
            float spanW = (inFront > 0)
                    ? Math.min(Math.max(0f, maxX - minX), 1.5f * screenW) : 0f;

            PendingArea p = obtainPending();
            measureAreaLabel(p, area.name, summitX, plateBottom, spanW);
            p.palette = paletteFor(area.type);
            p.priority = areaPriority(area.type);
            p.importance = area.visibleRangeKm;
            areaPending.add(p);
        }

        // Pass 2: draw in priority order (islands/ranges before towns, larger range first within a
        // tier) and skip any label whose plate overlaps one already drawn — so nothing is hidden
        // behind another label.
        areaPending.sort((a, b) -> a.priority != b.priority
                ? Integer.compare(b.priority, a.priority)
                : Float.compare(b.importance, a.importance));
        areaAccepted.clear();
        for (int i = 0; i < areaPending.size(); i++) {
            PendingArea p = areaPending.get(i);
            boolean blocked = false;
            for (int j = 0; j < areaAccepted.size(); j++) {
                if (areaLabelsOverlap(p, areaAccepted.get(j))) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                areaAccepted.add(p);
                drawAreaName(p);
            }
        }
    }

    private final Vector3 visSample = new Vector3();

    /**
     * Terrain-occlusion test, mirroring the peak/place/alpine-hut labels' "hidden by mountains"
     * check. Samples a few points spread across the area — both at sea level and at its highest
     * elevation — and reports the area as visible if at least one of them is not hidden behind nearer
     * terrain. Uses the same depth (impact) pixmap those labels use.
     */
    private boolean areaHasVisiblePoint(MapArea area, float areaLon, float targetLat,
                                        float centreX, float centreY, float centreZ,
                                        PerspectiveCameraExt cam) {
        ImpactPixmap ip = MapViewerSingleton.getViewerInstance().impactPixmap;
        if (ip == null || !ip.isReady())
            return true; // no depth information yet — don't hide anything
        float peakZoff = Units.convertMetersToLatits(area.peakMeters);
        // Centre, at sea level and at the summit.
        if (checkPointVisible(centreX, centreY, centreZ, cam, ip)) return true;
        if (checkPointVisible(centreX, centreY, centreZ + peakZoff, cam, ip)) return true;
        // The four ellipse semi-axis endpoints, again at sea level and at the summit.
        float kmPerDegLon = KM_PER_DEG_LAT
                * Math.max(1e-3f, (float) Math.cos(Math.toRadians(area.lat)));
        float rot = (float) Math.toRadians(area.rotationDeg);
        float cosR = (float) Math.cos(rot);
        float sinR = (float) Math.sin(rot);
        for (int s = 0; s < 4; s++) {
            float localE = (s == 0) ? area.semiMajorKm : (s == 1) ? -area.semiMajorKm : 0f;
            float localN = (s == 2) ? area.semiMinorKm : (s == 3) ? -area.semiMinorKm : 0f;
            float eastKm = localE * cosR - localN * sinR;
            float northKm = localE * sinR + localN * cosR;
            float ptLat = area.lat + northKm / KM_PER_DEG_LAT;
            float ptLon = areaLon + eastKm / kmPerDegLon;
            float corr = ElevationUtils.getElevationCorrectionForRoundEarth(ptLat, ptLon);
            float wx = (float) Units.convertLonitsToLatits(ptLon, targetLat);
            if (checkPointVisible(wx, ptLat, -corr, cam, ip)) return true;
            if (checkPointVisible(wx, ptLat, -corr + peakZoff, cam, ip)) return true;
        }
        return false;
    }

    private boolean checkPointVisible(float x, float y, float z, PerspectiveCameraExt cam, ImpactPixmap ip) {
        float dx = x - cam.position.x, dy = y - cam.position.y, dz = z - cam.position.z;
        float distMeters = Units.convertLatitsToMeters((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        visSample.set(x, y, z); // checkIfDistanceIsVisible projects this internally; distance already taken
        return ip.checkIfDistanceIsVisible(distMeters, visSample);
    }

    /**
     * Fills a stadium (rectangle with fully rounded ends) as three NON-overlapping pieces — a centre
     * rectangle and a semicircular cap at each end. The pieces meet exactly without overlapping, so
     * a translucent fill blends uniformly instead of doubling up into darker crescents at the ends.
     */
    private void fillPill(float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) * 0.5f);
        float cy = y + h * 0.5f;
        shapeRenderer.rect(x + r, y, w - 2f * r, h);      // centre band
        shapeRenderer.arc(x + r, cy, r, 90f, 180f, 24);   // left cap (half disc, flat edge at x+r)
        shapeRenderer.arc(x + w - r, cy, r, 270f, 180f, 24); // right cap (flat edge at x+w-r)
    }

    /**
     * Measures the label into {@code out}: scales the name to span the area's on-screen width
     * (clamped to a readable range) and computes the pill rectangle. Fills the rect/scale/text so the
     * de-overlap pass can test it and {@link #drawAreaName(PendingArea)} can draw it at the same size.
     */
    private void measureAreaLabel(PendingArea out, String name, float centerX, float bottomY,
                                  float ellipseScreenW) {
        BitmapFont font = getC().styleSingleton.getBitmapFont();
        float prevScaleX = font.getScaleX();
        float prevScaleY = font.getScaleY();
        font.getData().setScale(1f);
        float baseLineHeight = font.getLineHeight();
        areaGlyph.setText(font, name);
        float naturalW = Math.max(1f, areaGlyph.width);

        float targetW = 0.78f * ellipseScreenW;
        float scale = targetW / naturalW;
        float minScale = (0.30f * widgetUnitStep) / baseLineHeight;
        float maxScale = (0.75f * widgetUnitStep) / baseLineHeight;
        if (scale < minScale) scale = minScale;
        if (scale > maxScale) scale = maxScale;
        font.getData().setScale(scale);
        areaGlyph.setText(font, name);
        float textW = areaGlyph.width;
        float textH = areaGlyph.height;
        font.getData().setScale(prevScaleX, prevScaleY);

        float padX = 0.32f * widgetUnitStep;
        float padY = 0.16f * widgetUnitStep;
        float plateW = Math.max(textW + 2f * padX, 0.94f * ellipseScreenW);
        float plateH = textH + 2f * padY;
        out.name = name;
        out.scale = scale;
        out.textW = textW;
        out.textH = textH;
        out.rx = centerX - plateW * 0.5f;
        out.ry = bottomY;
        out.rw = plateW;
        out.rh = plateH;

        // Collision rectangle: the actual text extent (centred on the plate) plus a small margin, so
        // labels are only dropped when their names would genuinely overlap — not when their wide
        // background pills happen to touch.
        float cpad = 0.15f * widgetUnitStep;
        out.crw = textW + 2f * cpad;
        out.crh = textH + 2f * padY;
        out.crx = centerX - out.crw * 0.5f;
        out.cry = bottomY + (plateH - out.crh) * 0.5f;
    }

    /** Draws a measured area label: one uniform rounded pill with the name centred on it. */
    private void drawAreaName(PendingArea p) {
        float plateX = p.rx, plateY = p.ry, plateW = p.rw, plateH = p.rh;
        float radius = plateH * 0.5f; // fully rounded ends

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        try {
            shapeRenderer.setColor(p.palette.fill);
            fillPill(plateX, plateY, plateW, plateH, radius);
        } finally {
            shapeRenderer.end();
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);

        BitmapFont font = getC().styleSingleton.getBitmapFont();
        float prevScaleX = font.getScaleX();
        float prevScaleY = font.getScaleY();
        font.getData().setScale(p.scale);
        // Name centred on the plate, with a crisp dark shadow for extra contrast.
        float tx = (plateX + plateW * 0.5f) - p.textW * 0.5f;
        float ty = plateY + plateH * 0.5f + p.textH * 0.5f;
        spriteBatch.setTransformMatrix(identityMat);
        spriteBatch.begin();
        try {
            float sh = Math.max(1.2f, 0.022f * widgetUnitStep);
            font.setColor(AREA_TEXT_SHADOW);
            font.draw(spriteBatch, p.name, tx + sh, ty - sh);
            font.draw(spriteBatch, p.name, tx - sh, ty - sh);
            font.setColor(AREA_TEXT);
            font.draw(spriteBatch, p.name, tx, ty);
        } finally {
            spriteBatch.end();
            font.getData().setScale(prevScaleX, prevScaleY);
            font.setColor(Color.WHITE);
        }
    }

    // 16-point compass, clockwise from North. Each entry is a sequence of cardinal letters; the
    // localized single-letter cardinals (see compassLabel) are substituted in, so e.g. "SSE"
    // becomes S+S+E in whatever the current language calls those directions.
    private static final String[] COMPASS_PATTERNS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };
    private String[] compassLabels; // localized, built lazily on the render thread
    private String[] compassDegrees; // e.g. "0°", "22.5°", "45°" — built alongside compassLabels
    private final GlyphLayout horizonGlyph = new GlyphLayout();
    private final boolean[] horizonVisible = new boolean[COMPASS_PATTERNS.length];
    private final float[] horizonScreenX = new float[COMPASS_PATTERNS.length];
    private final float[] horizonLabelW = new float[COMPASS_PATTERNS.length];
    private final float[] horizonLabelH = new float[COMPASS_PATTERNS.length];
    private final float[] horizonDegW = new float[COMPASS_PATTERNS.length];
    private final float[] horizonDegH = new float[COMPASS_PATTERNS.length];

    // Fixed vertical position of the heading strip, as a fraction of screen height (from the
    // bottom). The strip stays at this height however the camera pitches; only its marks slide
    // sideways as you turn.
    private static final float HORIZON_STRIP_Y_FRACTION = 0.80f;
    // A calm, cohesive palette: a deep translucent slate plate under every mark, with a per-rank
    // accent — warm coral North, gold for the other cardinals, cool cyan for the intercardinals —
    // colouring the bar and the degrees, and crisp cream direction text on top.
    private static final Color HORIZON_PLATE = new Color(0.09f, 0.13f, 0.19f, 0.68f);
    private static final Color HORIZON_HALO = new Color(0.02f, 0.04f, 0.07f, 0.55f);
    private static final Color HORIZON_ACCENT_NORTH = new Color(0.98f, 0.46f, 0.40f, 1f);
    private static final Color HORIZON_ACCENT_CARDINAL = new Color(0.99f, 0.82f, 0.45f, 1f);
    private static final Color HORIZON_ACCENT_INTER = new Color(0.55f, 0.84f, 0.92f, 1f);
    private static final Color HORIZON_DIR_TEXT = new Color(0.98f, 0.98f, 0.95f, 1f);

    private String compassLabel(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            switch (pattern.charAt(i)) {
                case 'N': sb.append(s("Compass_north")); break;
                case 'E': sb.append(s("Compass_east")); break;
                case 'S': sb.append(s("Compass_south")); break;
                case 'W': sb.append(s("Compass_west")); break;
                default: break;
            }
        }
        return sb.toString();
    }

    /**
     * Draws a heading strip: the cardinal directions (N, NE, E, …) as vertical bars with labels at
     * a fixed height near the top of the screen. Each direction's horizontal position tracks the
     * camera's heading — turn and they slide sideways to where that direction actually is — but the
     * strip does not move vertically when the camera pitches up or down. Each mark carries a dark
     * halo/plate so the bright text and bars stay legible against any sky. Toggled by the "horizon
     * compass" option.
     */
    private void renderHorizonCompass() {
        if (!P.isHorizonCompass())
            return;
        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        if (cam == null)
            return;
        // Heading = azimuth of the camera's horizontal facing (clockwise from North). If it looks
        // almost straight up or down there is no meaningful heading, so skip.
        float camHx = cam.direction.x;
        float camHy = cam.direction.y;
        if (Math.sqrt(camHx * camHx + camHy * camHy) < 1e-3f)
            return;
        // North is +Y, East is +X, so azimuth is atan2(x, y): North -> 0, East -> 90.
        double headingDeg = Math.toDegrees(Math.atan2(camHx, camHy));

        if (compassLabels == null) {
            compassLabels = new String[COMPASS_PATTERNS.length];
            compassDegrees = new String[COMPASS_PATTERNS.length];
            for (int i = 0; i < COMPASS_PATTERNS.length; i++) {
                compassLabels[i] = compassLabel(COMPASS_PATTERNS[i]);
                compassDegrees[i] = formatDegrees(i * (360.0 / COMPASS_PATTERNS.length));
            }
        }

        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();

        // Map a direction's horizontal angle from the view centre to a screen x through the
        // camera's horizontal field of view, so a direction sits where it really is on screen —
        // horizontally only. cam.fieldOfView is the vertical FOV; widen it by the aspect ratio.
        float aspect = (screenH > 0) ? (float) screenW / screenH : 1f;
        float halfVfovRad = (float) Math.toRadians(cam.fieldOfView * 0.5f);
        float tanHalfHfov = (float) (Math.tan(halfVfovRad) * aspect);
        float centerX = screenW * 0.5f;
        float baseY = screenH * HORIZON_STRIP_Y_FRACTION; // fixed vertical position

        BitmapFont font = getC().styleSingleton.getBitmapFont();
        float prevScaleX = font.getScaleX();
        float prevScaleY = font.getScaleY();
        font.getData().setScale(1f);
        float baseLineHeight = font.getLineHeight();

        int n = COMPASS_PATTERNS.length;
        for (int i = 0; i < n; i++) {
            horizonVisible[i] = false;
            float delta = (float) normalizeDegrees(i * (360.0 / n) - headingDeg); // [-180, 180]
            if (delta <= -89f || delta >= 89f)
                continue; // at or behind the sides
            float x = centerX
                    + (screenW * 0.5f) * (float) (Math.tan(Math.toRadians(delta)) / tanHalfHfov);
            if (x < -0.03f * screenW || x > screenW * 1.03f)
                continue; // outside the field of view
            font.getData().setScale(horizonLabelScale(i, baseLineHeight));
            horizonGlyph.setText(font, compassLabels[i]);
            horizonScreenX[i] = x;
            horizonLabelW[i] = horizonGlyph.width;
            horizonLabelH[i] = horizonGlyph.height;
            font.getData().setScale(horizonDegScale(baseLineHeight));
            horizonGlyph.setText(font, compassDegrees[i]);
            horizonDegW[i] = horizonGlyph.width;
            horizonDegH[i] = horizonGlyph.height;
            horizonVisible[i] = true;
        }

        float barThickness = Math.max(2f, 0.05f * widgetUnitStep);
        float labelGap = 0.12f * widgetUnitStep;   // between the bar top and the label block
        float lineGap = 0.04f * widgetUnitStep;     // between the direction line and its degrees
        float platePadX = 0.14f * widgetUnitStep;
        float platePadY = 0.07f * widgetUnitStep;

        // Bars and the dark plates behind the labels.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        try {
            for (int i = 0; i < n; i++) {
                if (!horizonVisible[i])
                    continue;
                float halfLen = horizonBarHalfHeight(i);
                float x = horizonScreenX[i];
                Color accent = horizonAccent(i);
                float barHalfW = barThickness * 0.5f;
                // Vertical bar with rounded ends: a soft dark halo for contrast, then the accent.
                shapeRenderer.setColor(HORIZON_HALO);
                horizonFillCapsuleV(x, baseY, halfLen + 1.5f, barHalfW + 1.5f);
                shapeRenderer.setColor(accent);
                horizonFillCapsuleV(x, baseY, halfLen, barHalfW);
                // Rounded slate plate behind the two-line label (direction + degrees).
                float blockW = Math.max(horizonLabelW[i], horizonDegW[i]);
                float blockH = horizonLabelH[i] + lineGap + horizonDegH[i];
                float blockBottom = baseY + halfLen + labelGap;
                float pw = blockW + 2f * platePadX;
                float ph = blockH + 2f * platePadY;
                float px = x - pw * 0.5f;
                float py = blockBottom - platePadY;
                shapeRenderer.setColor(HORIZON_PLATE);
                horizonFillRoundRect(px, py, pw, ph, Math.min(ph, pw) * 0.32f);
            }
        } finally {
            shapeRenderer.end();
        }

        // Labels on top of their plates.
        spriteBatch.setTransformMatrix(identityMat);
        spriteBatch.begin();
        try {
            for (int i = 0; i < n; i++) {
                if (!horizonVisible[i])
                    continue;
                float blockBottom = baseY + horizonBarHalfHeight(i) + labelGap;
                // Degrees on the lower line, just above the bar.
                font.getData().setScale(horizonDegScale(baseLineHeight));
                float degTx = horizonScreenX[i] - horizonDegW[i] * 0.5f;
                float degTy = blockBottom + horizonDegH[i]; // font.draw y = text top
                font.setColor(horizonAccent(i));
                font.draw(spriteBatch, compassDegrees[i], degTx, degTy);
                // Direction on the upper line, above the degrees.
                font.getData().setScale(horizonLabelScale(i, baseLineHeight));
                float dirTx = horizonScreenX[i] - horizonLabelW[i] * 0.5f;
                float dirTy = blockBottom + horizonDegH[i] + lineGap + horizonLabelH[i];
                font.setColor(HORIZON_DIR_TEXT);
                font.draw(spriteBatch, compassLabels[i], dirTx, dirTy);
            }
        } finally {
            spriteBatch.end();
            font.getData().setScale(prevScaleX, prevScaleY);
            font.setColor(Color.WHITE);
        }
    }

    /** Font scale for a mark: cardinals (N/E/S/W) a bit larger than the rest. */
    private float horizonLabelScale(int i, float baseLineHeight) {
        return ((i % 4 == 0 ? 0.55f : 0.42f) * widgetUnitStep) / baseLineHeight;
    }

    /** Font scale for the degrees line: smaller than the direction label above it. */
    private float horizonDegScale(float baseLineHeight) {
        return (0.32f * widgetUnitStep) / baseLineHeight;
    }

    /** "0°", "45°" for whole degrees, "22.5°" for the half-winds. */
    private static String formatDegrees(double deg) {
        String number = (deg == Math.floor(deg))
                ? Integer.toString((int) deg)
                : Double.toString(deg);
        return number + "°";
    }

    /** Wraps an angle in degrees to the range [-180, 180]. */
    private static double normalizeDegrees(double deg) {
        deg %= 360.0;
        if (deg > 180.0)
            deg -= 360.0;
        else if (deg < -180.0)
            deg += 360.0;
        return deg;
    }

    /** Per-rank accent colour: coral North, gold cardinals, cyan everything else. */
    private Color horizonAccent(int i) {
        if (i == 0)
            return HORIZON_ACCENT_NORTH;
        if (i % 4 == 0)
            return HORIZON_ACCENT_CARDINAL;
        return HORIZON_ACCENT_INTER;
    }

    /** Filled vertical capsule (a rect with semicircular caps) centred at (cx, cy). */
    private void horizonFillCapsuleV(float cx, float cy, float halfLen, float halfW) {
        shapeRenderer.rect(cx - halfW, cy - halfLen, 2f * halfW, 2f * halfLen);
        shapeRenderer.circle(cx, cy - halfLen, halfW, 16);
        shapeRenderer.circle(cx, cy + halfLen, halfW, 16);
    }

    /** Filled rounded rectangle: three bands plus a quarter-circle at each corner. */
    private void horizonFillRoundRect(float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) * 0.5f);
        shapeRenderer.rect(x + r, y, w - 2f * r, h);
        shapeRenderer.rect(x, y + r, r, h - 2f * r);
        shapeRenderer.rect(x + w - r, y + r, r, h - 2f * r);
        shapeRenderer.circle(x + r, y + r, r, 16);
        shapeRenderer.circle(x + w - r, y + r, r, 16);
        shapeRenderer.circle(x + r, y + h - r, r, 16);
        shapeRenderer.circle(x + w - r, y + h - r, r, 16);
    }

    /** Taller bars for the four cardinals, medium for intercardinals, short for the rest. */
    private float horizonBarHalfHeight(int i) {
        if (i % 4 == 0)
            return 0.5f * widgetUnitStep;
        if (i % 2 == 0)
            return 0.32f * widgetUnitStep;
        return 0.2f * widgetUnitStep;
    }

    private final Matrix4 tempMat = new Matrix4();

    private void renderLabelTexts() {
        for (int angle : DrawLabelCategory.getAngles()) {
            // mat4.setToRotation(0,0,1, (float)Math.PI/4);
            tempMat.set(0, 0, 0, 0, 0, 0, 0, 1, 1, 1);
            tempMat.rotate(Vector3.Z, angle);
            spriteBatch.setTransformMatrix(tempMat);
            spriteBatch.begin();
            try {
                drawWayLabels(angle, spriteBatch);
            } catch (Throwable throwable) {
                // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "spriteBatch");
                // crashLogger.logToFile();
            } finally {
                spriteBatch.end();
            }
        }
    }

    private void renderLabelLines() {

        // Draw the lines first:
        for (int angle : DrawLabelCategory.getAngles()) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            try {
                drawDisplayablePoiVerticalLines(angle);
            } catch (Throwable throwable) {
                // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "shapeRenderer");
                // crashLogger.logToFile();
            } finally {
                shapeRenderer.end();
            }
        }

        // Draw the background rectangles:
        for (int angle : DrawLabelCategory.getAngles()) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            try {
                drawDisplayablePoiRectangles(angle);
            } catch (Throwable throwable) {
                // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "shapeRenderer");
                // crashLogger.logToFile();
            } finally {
                shapeRenderer.end();
            }
        }

    }

    public void dispose() {
        compassTexture.dispose();
    }

    public void setBackgroundAlpha(float backgroundAlpha) {
        this.backgroundAlpha = backgroundAlpha;
    }

    public float getBackgroundAlpha() {
        return backgroundAlpha;
    }

    public void resize(int width, int height) {
        // spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        x = width - widgetUnitStep - w;
        y = height - widgetUnitStep;
    }
}
