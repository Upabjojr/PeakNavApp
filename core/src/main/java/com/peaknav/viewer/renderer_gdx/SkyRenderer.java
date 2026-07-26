package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

import com.peaknav.sky.ConstellationData;
import com.peaknav.sky.SkyBody;
import com.peaknav.sky.SkyModel;
import com.peaknav.sky.StarCatalog;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;

/**
 * Draws the astronomically-correct sky — Sun, Moon, planets, stars and constellation figures — as
 * 2D sprites projected from the {@link SkyModel}'s cached horizontal directions. It is rendered
 * <em>before</em> the terrain, so opaque terrain drawn afterwards naturally occludes anything below
 * a ridge, giving correct horizon hiding for free. Star/constellation brightness fades with the
 * Sun's altitude so the sky is empty by day and fills in through dusk.
 */
public final class SkyRenderer {

    private final SpriteBatch spriteBatch;
    private final ShapeRenderer shapeRenderer;

    /** Radius (in latits) at which sky objects are placed; comfortably inside the camera far plane. */
    private static final float SKY_RADIUS = 12f;
    private static final float HORIZON_SIN = -0.02f; // allow a hair below the mathematical horizon

    private final Vector3 tmp = new Vector3();
    private final Vector3 tmp2 = new Vector3();

    private com.badlogic.gdx.graphics.Texture moonTexture;
    private boolean moonTextureTried = false;

    private com.badlogic.gdx.graphics.Texture moonTexture() {
        if (!moonTextureTried) {
            moonTextureTried = true;
            try {
                com.badlogic.gdx.files.FileHandle f = Gdx.files.internal("sky/moon.png");
                if (f.exists()) {
                    moonTexture = new com.badlogic.gdx.graphics.Texture(f);
                    moonTexture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,
                            com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
                }
            } catch (Exception ignore) {
                // fall back to a plain disc
            }
        }
        return moonTexture;
    }

    // Sky background colour keyframes by Sun altitude (deg): {alt, r, g, b}. Interpolated in between.
    // This palette is used only while the sky view is on (stars are always shown then), so the day
    // sky is a deeper blue than a plain daytime sky — dark enough for white stars to stay visible —
    // and it passes through a distinct dusky twilight so dawn and after-sunset visibly darken.
    private static final float[][] SKY_COLORS = {
            {  15f, 0.26f, 0.40f, 0.64f}, // day: deep blue (stars still read against it)
            {   4f, 0.20f, 0.28f, 0.48f}, // Sun just above the horizon
            {   0f, 0.24f, 0.19f, 0.30f}, // sunrise / sunset: dusky violet
            {  -5f, 0.10f, 0.10f, 0.20f}, // civil twilight — clear darkening
            { -10f, 0.05f, 0.05f, 0.12f}, // nautical twilight
            { -16f, 0.02f, 0.02f, 0.06f}, // astronomical night
    };

    public SkyRenderer(SpriteBatch spriteBatch, ShapeRenderer shapeRenderer) {
        this.spriteBatch = spriteBatch;
        this.shapeRenderer = shapeRenderer;
    }

    /** Sky background colour for the GL clear, given the Sun altitude. Writes r,g,b into {@code out}. */
    public static void skyColor(double sunAltDeg, float[] out) {
        float[] hi = SKY_COLORS[0], lo = SKY_COLORS[SKY_COLORS.length - 1];
        if (sunAltDeg >= hi[0]) { out[0] = hi[1]; out[1] = hi[2]; out[2] = hi[3]; return; }
        if (sunAltDeg <= lo[0]) { out[0] = lo[1]; out[1] = lo[2]; out[2] = lo[3]; return; }
        for (int i = 0; i < SKY_COLORS.length - 1; i++) {
            float[] a = SKY_COLORS[i], b = SKY_COLORS[i + 1];
            if (sunAltDeg <= a[0] && sunAltDeg >= b[0]) {
                float t = (float) ((a[0] - sunAltDeg) / (a[0] - b[0]));
                out[0] = MathUtils.lerp(a[1], b[1], t);
                out[1] = MathUtils.lerp(a[2], b[2], t);
                out[2] = MathUtils.lerp(a[3], b[3], t);
                return;
            }
        }
    }

    public static final int MODE_LOCAL = 0, MODE_DAY = 1, MODE_NIGHT = 2;

    /**
     * The Sun altitude that drives the sky <em>ambiance</em> (background colour + star fade). In the
     * default LOCAL mode this is the real Sun altitude; DAY forces a bright daytime sky and NIGHT a
     * dark starry one, regardless of the actual time (the objects themselves still sit at their real
     * positions for the current/custom time).
     */
    public static double ambianceSunAltitude(double realSunAltDeg) {
        switch (P.getSkyMode()) {
            case MODE_DAY: return 20.0;
            case MODE_NIGHT: return -18.0;
            default: return realSunAltDeg;
        }
    }

    public void render() {
        SkyModel sky = getC().skyModel;
        if (sky == null || !sky.isLoaded()) return;
        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        if (cam == null) return;

        // When the sky view is on the objects are always visible — they no longer fade out during the
        // day. The sky mode (local time / forced day / forced night) only tints the background sky
        // colour (see clearScreen) and the terrain light, not whether the stars show.
        float starNight = 1f;
        // The Sun is always drawn; the Moon, planets, stars and constellations are the "sky objects"
        // that the on/off checkbox toggles.
        boolean objects = P.isSkyView();
        boolean showConstellations = objects && P.isSkyConstellations();
        float px = Math.max(1f, Gdx.graphics.getHeight() / 900f); // pixel scale for hi-dpi

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        // Draw the sky to the colour buffer ONLY — no depth writes, no depth test. Otherwise the 2D
        // star pixels stamp a near depth and the terrain drawn afterwards fails the depth test
        // against them, showing stars through the mountains. With depth writes off, the opaque
        // terrain simply paints over any sky pixel it covers → mountains occlude the stars.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());

        // 1) Constellation lines (faint)
        if (showConstellations && starNight > 0.05f) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
            shapeRenderer.setColor(0.62f, 0.74f, 1.0f, 0.5f * starNight);
            for (float[] enu : sky.getConstellationEnu()) {
                for (int i = 0; i + 5 < enu.length; i += 3) {
                    if (project(cam, enu[i], enu[i + 1], enu[i + 2])) {
                        float ax = tmp.x, ay = tmp.y;
                        if (project(cam, enu[i + 3], enu[i + 4], enu[i + 5])) {
                            shapeRenderer.line(ax, ay, tmp.x, tmp.y);
                        }
                    }
                }
            }
            shapeRenderer.end();
        }

        // 2) Stars + solar-system discs
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        if (objects && starNight > 0.05f) {
            StarCatalog stars = sky.getStars();
            float[] senu = sky.getStarEnu();
            for (int i = 0; i < stars.count; i++) {
                int o = i * 3;
                if (!project(cam, senu[o], senu[o + 1], senu[o + 2])) continue;
                float mag = stars.mag[i];
                float radius = MathUtils.clamp((6.5f - mag) * 0.6f + 1.3f, 1.3f, 6.0f) * px;
                float alpha = MathUtils.clamp(0.6f + (6.5f - mag) * 0.12f, 0.6f, 1.0f) * starNight;
                // Dark contrast ring: invisible over the near-black night sky, but darkens the blue
                // daytime sky just around each star so the white dot still stands out.
                shapeRenderer.setColor(0f, 0.02f, 0.06f, 0.4f);
                shapeRenderer.circle(tmp.x, tmp.y, radius * 1.7f, 10);
                // Soft glow so the brighter stars read against any sky.
                if (mag < 2.5f) {
                    shapeRenderer.setColor(0.75f, 0.83f, 1.0f, 0.22f * starNight);
                    shapeRenderer.circle(tmp.x, tmp.y, radius * 2.4f, 12);
                }
                shapeRenderer.setColor(1.0f, 1.0f, 1.0f, alpha);
                shapeRenderer.circle(tmp.x, tmp.y, radius, 10);
            }
        }
        // planets / sun / moon
        float[] benu = sky.getBodyEnu();
        java.util.List<SkyBody> bodies = sky.getSolarSystem().bodies;
        for (int i = 0; i < bodies.size(); i++) {
            SkyBody b = bodies.get(i);
            if (b.kind != SkyBody.Kind.SUN && !objects) continue; // Sun always; the rest gated
            if (b.kind == SkyBody.Kind.MOON) continue; // drawn separately with its texture + phase
            int o = i * 3;
            if (!project(cam, benu[o], benu[o + 1], benu[o + 2])) continue;
            drawBodyDisc(b, tmp.x, tmp.y, px, starNight);
        }
        shapeRenderer.end();

        // The Moon: a low-res photo texture with a graphically rendered phase (crescent/gibbous).
        if (objects) {
            drawMoon(cam, sky, px);
        }

        // 3) Labels
        BitmapFont font = getC().styleSingleton.getBitmapFont();
        spriteBatch.begin();
        // constellation names
        if (showConstellations && starNight > 0.15f) {
            font.setColor(0.6f, 0.7f, 0.9f, 0.5f * starNight);
            java.util.List<ConstellationData.Label> labs = sky.getConstellations().labels;
            float[] lenu = sky.getLabelEnu();
            for (int i = 0; i < labs.size(); i++) {
                int o = i * 3;
                if (project(cam, lenu[o], lenu[o + 1], lenu[o + 2])) {
                    font.draw(spriteBatch, labs.get(i).name, tmp.x + 4 * px, tmp.y);
                }
            }
        }
        // bright star names
        if (objects && starNight > 0.15f) {
            font.setColor(0.85f, 0.9f, 1.0f, 0.75f * starNight);
            float[] nenu = sky.getNamedStarEnu();
            for (int i = 0; i < SkyModel.NAMED_STARS.length; i++) {
                int o = i * 3;
                if (project(cam, nenu[o], nenu[o + 1], nenu[o + 2])) {
                    font.draw(spriteBatch, SkyModel.NAMED_STARS[i].name, tmp.x + 5 * px, tmp.y + 5 * px);
                }
            }
        }
        // Sun label always; Moon/planet labels only when sky objects are on and dark enough
        for (int i = 0; i < bodies.size(); i++) {
            SkyBody b = bodies.get(i);
            if (b.kind != SkyBody.Kind.SUN && !objects) continue;
            int o = i * 3;
            if (!project(cam, benu[o], benu[o + 1], benu[o + 2])) continue;
            if (b.kind == SkyBody.Kind.PLANET && starNight < 0.15f) continue;
            String name = localizedBodyName(b);
            String label = b.kind == SkyBody.Kind.MOON
                    ? String.format(java.util.Locale.getDefault(), "%s %d%%", name, Math.round(b.phase * 100))
                    : name;
            font.setColor(b.r, b.g, b.b, 0.9f);
            font.draw(spriteBatch, label, tmp.x + 6 * px, tmp.y + 6 * px);
        }
        font.setColor(Color.WHITE);
        spriteBatch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
        // Restore depth state so the terrain (drawn next) depth-tests and writes normally.
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
    }

    /** Localised label for a Sun/Moon/planet, e.g. {@code Sky_sun} → "Sole" in Italian. */
    private static String localizedBodyName(SkyBody b) {
        return s("Sky_" + b.name.toLowerCase(java.util.Locale.ENGLISH));
    }

    private void drawBodyDisc(SkyBody b, float x, float y, float px, float night) {
        if (b.kind == SkyBody.Kind.PLANET) {
            if (night < 0.05f) return; // planets fade in at dusk
            float radius = MathUtils.clamp((2.0f - (float) b.magnitude) * 1.0f + 3.0f, 3.0f, 9.0f) * px;
            // coloured glow
            shapeRenderer.setColor(b.r, b.g, b.b, 0.28f * MathUtils.clamp(night, 0.5f, 1f));
            shapeRenderer.circle(x, y, radius * 2.2f, 18);
            // dark contrast ring so the dot pops against a bright or dark sky
            shapeRenderer.setColor(0.04f, 0.05f, 0.09f, 0.75f);
            shapeRenderer.circle(x, y, radius * 1.28f, 22);
            // coloured body + bright core
            shapeRenderer.setColor(b.r, b.g, b.b, 1f);
            shapeRenderer.circle(x, y, radius, 22);
            shapeRenderer.setColor(1f, 1f, 1f, 0.85f);
            shapeRenderer.circle(x, y, radius * 0.38f, 12);
            return;
        }
        // Sun: a disc a bit larger than life with a soft glow. (The Moon is drawn separately.)
        float radius = Math.max(6f, Gdx.graphics.getHeight() / 55f);
        shapeRenderer.setColor(1f, 0.82f, 0.35f, 0.30f);
        shapeRenderer.circle(x, y, radius * 2.6f, 28);
        shapeRenderer.setColor(1f, 0.88f, 0.5f, 0.55f);
        shapeRenderer.circle(x, y, radius * 1.5f, 28);
        shapeRenderer.setColor(1f, 0.96f, 0.75f, 1f);
        shapeRenderer.circle(x, y, radius, 28);
    }

    private static final int MOON_RADIUS = 28;

    /**
     * Draws the Moon as a low-res photo texture with a graphically rendered phase: a dark terminator
     * lune is drawn over the disc, oriented so the bright limb faces the Sun on screen and sized by
     * the illuminated fraction (full = no shadow, new = all shadow, quarter = straight terminator).
     */
    private void drawMoon(PerspectiveCameraExt cam, SkyModel sky, float px) {
        float[] be = sky.getBodyEnu();
        // Moon is body index 1 → ENU offset 3.
        if (!project(cam, be[3], be[4], be[5])) return;
        float mx = tmp.x, my = tmp.y;
        float r = Math.max(7f, Gdx.graphics.getHeight() / 52f);

        // Bright-limb direction on screen: project a point nudged from the Moon towards the Sun.
        float nx = be[3] + 0.1f * be[0], ny = be[4] + 0.1f * be[1], nz = be[5] + 0.1f * be[2];
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-4f) len = 1f;
        tmp2.set(cam.position.x + nx / len * SKY_RADIUS,
                cam.position.y + ny / len * SKY_RADIUS,
                cam.position.z + nz / len * SKY_RADIUS);
        cam.project(tmp2);
        float ang = (float) Math.atan2(tmp2.y - my, tmp2.x - mx); // toward the Sun (bright limb)

        // Dark rim for contrast against a bright sky.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.09f, 0.10f, 0.15f, 0.85f);
        shapeRenderer.circle(mx, my, r * 1.06f, MOON_RADIUS);
        shapeRenderer.end();

        com.badlogic.gdx.graphics.Texture tex = moonTexture();
        if (tex != null) {
            spriteBatch.begin();
            spriteBatch.setColor(0.97f, 0.97f, 0.94f, 1f);
            spriteBatch.draw(tex, mx - r, my - r, 2f * r, 2f * r);
            spriteBatch.setColor(Color.WHITE);
            spriteBatch.end();
        } else {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            shapeRenderer.setColor(0.86f, 0.87f, 0.83f, 1f);
            shapeRenderer.circle(mx, my, r, MOON_RADIUS);
            shapeRenderer.end();
        }

        // Phase shadow: a lune between the dark limb (u = -L) and the terminator (u = c·L), with the
        // bright axis u along `ang`. c = 1 - 2k: full (k=1) → no shadow, new (k=0) → whole disc.
        float k = MathUtils.clamp((float) sky.getSolarSystem().moon.phase, 0f, 1f);
        float c = 1f - 2f * k;
        float ux = (float) Math.cos(ang), uy = (float) Math.sin(ang);
        float vx = -uy, vy = ux;
        int nSeg = 28;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.04f, 0.05f, 0.09f, 0.9f);
        for (int s = 0; s < nSeg; s++) {
            float v0 = -r + 2f * r * s / nSeg;
            float v1 = -r + 2f * r * (s + 1) / nSeg;
            float l0 = (float) Math.sqrt(Math.max(0f, r * r - v0 * v0));
            float l1 = (float) Math.sqrt(Math.max(0f, r * r - v1 * v1));
            float ax = mx + (-l0) * ux + v0 * vx, ay = my + (-l0) * uy + v0 * vy;
            float bx = mx + (c * l0) * ux + v0 * vx, by = my + (c * l0) * uy + v0 * vy;
            float cx = mx + (c * l1) * ux + v1 * vx, cy = my + (c * l1) * uy + v1 * vy;
            float dx = mx + (-l1) * ux + v1 * vx, dy = my + (-l1) * uy + v1 * vy;
            shapeRenderer.triangle(ax, ay, bx, by, cx, cy);
            shapeRenderer.triangle(ax, ay, cx, cy, dx, dy);
        }
        shapeRenderer.end();
    }

    /**
     * Places an ENU direction at the sky radius, culls it if below the horizon or outside the view
     * frustum, and projects it to screen pixels in {@link #tmp}. Returns true if it should be drawn.
     */
    private boolean project(PerspectiveCameraExt cam, float ex, float ey, float ez) {
        if (ez < HORIZON_SIN) return false;
        float wx = cam.position.x + ex * SKY_RADIUS;
        float wy = cam.position.y + ey * SKY_RADIUS;
        float wz = cam.position.z + ez * SKY_RADIUS;
        if (!cam.frustum.pointInFrustum(wx, wy, wz)) return false;
        tmp.set(wx, wy, wz);
        cam.project(tmp);
        return true;
    }
}
