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
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.islands.Island;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;
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
        renderIslands();
        renderLabelLines();
        renderLabelTexts();
        renderHorizonCompass();
        if (getAppState().isLoadingMapData()) {
            renderLoading(deltaTime);
        } else {
            angle = 0;
        }
        renderCompass();
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

    // ---- Island area overlay -------------------------------------------------------------------
    // Islands (from islands.json) are drawn as a translucent ellipse hovering over the water they
    // occupy, with the island name spanning the ellipse. This marks the extent of an island the way
    // an atlas stretches a region's name across it — the peak/town labels alone never say which
    // island you are looking at.
    private static final int ISLAND_SEGMENTS = 48;
    private static final float ISLAND_MAX_KM = 260f; // only draw islands near the current view
    private static final float KM_PER_DEG_LAT = 111.32f;
    private final Vector3 islandTmp = new Vector3();
    private final float[] islandBoundaryX = new float[ISLAND_SEGMENTS];
    private final float[] islandBoundaryY = new float[ISLAND_SEGMENTS];
    private final GlyphLayout islandGlyph = new GlyphLayout();
    private static final Color ISLAND_FILL = new Color(0.30f, 0.68f, 0.95f, 0.18f);
    private static final Color ISLAND_OUTLINE = new Color(0.55f, 0.86f, 1.0f, 0.85f);
    private static final Color ISLAND_TEXT = new Color(0.98f, 0.99f, 1.0f, 1f);
    private static final Color ISLAND_TEXT_SHADOW = new Color(0.03f, 0.10f, 0.18f, 0.9f);

    /**
     * Draws each configured island as a translucent ellipse over the sea it occupies, with the
     * island name spanning it. The ellipse sits at sea level (so it hugs the coastline footprint)
     * and the name floats a little above the centre so it stays legible over the terrain. Only
     * islands within {@link #ISLAND_MAX_KM} of the current target are drawn.
     */
    private void renderIslands() {
        List<Island> islands = getC().islandRegistry.getIslands();
        if (islands.isEmpty())
            return;
        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        if (cam == null)
            return;

        float targetLat = getC().L.getTargetLatitude();
        float targetLon = (float) getC().L.getTargetLongitude();
        float cosTargetLat = (float) Math.cos(Math.toRadians(targetLat));
        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();

        for (Island island : islands) {
            // Distance cull: keep it to the archipelago you are actually near.
            float dLatKm = (island.lat - targetLat) * KM_PER_DEG_LAT;
            float dLonKm = (island.lon - targetLon) * KM_PER_DEG_LAT * cosTargetLat;
            if (dLatKm * dLatKm + dLonKm * dLonKm > ISLAND_MAX_KM * ISLAND_MAX_KM)
                continue;

            // Footprint centre at sea level (elevation 0, round-earth corrected).
            float centreCorr = ElevationUtils.getElevationCorrectionForRoundEarth(island.lat, island.lon);
            float centreX = (float) Units.convertLonitsToLatits(island.lon, targetLat);
            float centreY = island.lat;
            float centreZ = -centreCorr;
            // Skip islands behind the camera.
            float toX = centreX - cam.position.x;
            float toY = centreY - cam.position.y;
            float toZ = centreZ - cam.position.z;
            if (toX * cam.direction.x + toY * cam.direction.y + toZ * cam.direction.z <= 0f)
                continue;

            // Ellipse boundary. Local (east, north) km, major axis rotated CCW from East.
            float kmPerDegLon = KM_PER_DEG_LAT * (float) Math.cos(Math.toRadians(island.lat));
            float rot = (float) Math.toRadians(island.rotationDeg);
            float cosR = (float) Math.cos(rot);
            float sinR = (float) Math.sin(rot);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int k = 0; k < ISLAND_SEGMENTS; k++) {
                float t = (float) (2.0 * Math.PI * k / ISLAND_SEGMENTS);
                float localE = island.semiMajorKm * (float) Math.cos(t);
                float localN = island.semiMinorKm * (float) Math.sin(t);
                float eastKm = localE * cosR - localN * sinR;
                float northKm = localE * sinR + localN * cosR;
                float ptLat = island.lat + northKm / KM_PER_DEG_LAT;
                float ptLon = island.lon + eastKm / kmPerDegLon;
                float corr = ElevationUtils.getElevationCorrectionForRoundEarth(ptLat, ptLon);
                islandTmp.set((float) Units.convertLonitsToLatits(ptLon, targetLat), ptLat, -corr);
                cam.project(islandTmp);
                islandBoundaryX[k] = islandTmp.x;
                islandBoundaryY[k] = islandTmp.y;
                if (islandTmp.x < minX) minX = islandTmp.x;
                if (islandTmp.x > maxX) maxX = islandTmp.x;
                if (islandTmp.y < minY) minY = islandTmp.y;
                if (islandTmp.y > maxY) maxY = islandTmp.y;
            }
            // Off-screen cull (with a margin so a partially visible island still draws).
            if (maxX < -0.1f * screenW || minX > 1.1f * screenW
                    || maxY < -0.1f * screenH || minY > 1.1f * screenH)
                continue;

            // Footprint centre and the (slightly raised) name anchor, in screen space.
            islandTmp.set(centreX, centreY, centreZ);
            cam.project(islandTmp);
            float fanX = islandTmp.x;
            float fanY = islandTmp.y;
            islandTmp.set(centreX, centreY, centreZ + Units.convertMetersToLatits(nameLiftMeters(island)));
            cam.project(islandTmp);
            float nameX = islandTmp.x;
            float nameY = islandTmp.y;

            drawIslandShape(fanX, fanY);
            drawIslandName(island.name, nameX, nameY, maxX - minX);
        }
    }

    /** How high above the sea the island name floats, so it clears the terrain and stays readable. */
    private float nameLiftMeters(Island island) {
        float lift = island.semiMajorKm * 60f;
        return Math.max(300f, Math.min(1500f, lift));
    }

    private void drawIslandShape(float fanX, float fanY) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        try {
            shapeRenderer.setColor(ISLAND_FILL);
            for (int k = 0; k < ISLAND_SEGMENTS; k++) {
                int k2 = (k + 1) % ISLAND_SEGMENTS;
                shapeRenderer.triangle(
                        fanX, fanY,
                        islandBoundaryX[k], islandBoundaryY[k],
                        islandBoundaryX[k2], islandBoundaryY[k2]);
            }
        } finally {
            shapeRenderer.end();
        }
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        try {
            shapeRenderer.setColor(ISLAND_OUTLINE);
            for (int k = 0; k < ISLAND_SEGMENTS; k++) {
                int k2 = (k + 1) % ISLAND_SEGMENTS;
                shapeRenderer.line(
                        islandBoundaryX[k], islandBoundaryY[k],
                        islandBoundaryX[k2], islandBoundaryY[k2]);
            }
        } finally {
            shapeRenderer.end();
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawIslandName(String name, float centerX, float centerY, float ellipseScreenW) {
        if (name == null || name.isEmpty())
            return;
        BitmapFont font = getC().styleSingleton.getBitmapFont();
        float prevScaleX = font.getScaleX();
        float prevScaleY = font.getScaleY();
        font.getData().setScale(1f);
        float baseLineHeight = font.getLineHeight();
        islandGlyph.setText(font, name);
        float naturalW = Math.max(1f, islandGlyph.width);

        // Scale the name to span most of the ellipse's on-screen width, clamped so it stays a
        // sensible, readable size for both tiny and large islands.
        float targetW = 0.82f * ellipseScreenW;
        float scale = targetW / naturalW;
        float minScale = (0.30f * widgetUnitStep) / baseLineHeight;
        float maxScale = (0.75f * widgetUnitStep) / baseLineHeight;
        if (scale < minScale) scale = minScale;
        if (scale > maxScale) scale = maxScale;
        font.getData().setScale(scale);
        islandGlyph.setText(font, name);
        float tx = centerX - islandGlyph.width * 0.5f;
        float ty = centerY + islandGlyph.height * 0.5f;

        spriteBatch.setTransformMatrix(identityMat);
        spriteBatch.begin();
        try {
            float sh = Math.max(1f, 0.02f * widgetUnitStep);
            font.setColor(ISLAND_TEXT_SHADOW);
            font.draw(spriteBatch, name, tx + sh, ty - sh);
            font.setColor(ISLAND_TEXT);
            font.draw(spriteBatch, name, tx, ty);
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
