package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.utils.PeakNavUtils.getC;

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

    // Sky background colour keyframes by Sun altitude (deg): {alt, r, g, b}. Interpolated in between.
    private static final float[][] SKY_COLORS = {
            {  10f, 0.53f, 0.81f, 0.98f}, // full day
            {   0f, 0.42f, 0.52f, 0.62f}, // sunset at the horizon
            {  -6f, 0.12f, 0.14f, 0.26f}, // civil dusk
            { -12f, 0.04f, 0.05f, 0.12f}, // nautical
            { -18f, 0.02f, 0.02f, 0.06f}, // astronomical night
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

    /** 0 by day, 1 in astronomical darkness — how strongly stars/constellations show. */
    private static float nightFactor(double sunAltDeg) {
        return MathUtils.clamp((float) (-sunAltDeg / 12.0), 0f, 1f);
    }

    public void render() {
        SkyModel sky = getC().skyModel;
        if (sky == null || !sky.isLoaded()) return;
        PerspectiveCameraExt cam = MapViewerSingleton.getViewerInstance().cam;
        if (cam == null) return;

        float night = nightFactor(sky.getSunAltitudeDeg());
        float px = Math.max(1f, Gdx.graphics.getHeight() / 900f); // pixel scale for hi-dpi

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());

        // 1) Constellation lines (faint, night only)
        if (night > 0.05f) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
            shapeRenderer.setColor(0.45f, 0.55f, 0.75f, 0.28f * night);
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
        if (night > 0.05f) {
            StarCatalog stars = sky.getStars();
            float[] senu = sky.getStarEnu();
            for (int i = 0; i < stars.count; i++) {
                int o = i * 3;
                if (!project(cam, senu[o], senu[o + 1], senu[o + 2])) continue;
                float mag = stars.mag[i];
                float radius = MathUtils.clamp((6.5f - mag) * 0.45f + 0.5f, 0.5f, 4.0f) * px;
                float alpha = MathUtils.clamp(0.35f + (6.5f - mag) * 0.11f, 0.35f, 1.0f) * night;
                shapeRenderer.setColor(0.95f, 0.96f, 1.0f, alpha);
                shapeRenderer.circle(tmp.x, tmp.y, radius, 8);
            }
        }
        // planets / sun / moon
        float[] benu = sky.getBodyEnu();
        java.util.List<SkyBody> bodies = sky.getSolarSystem().bodies;
        for (int i = 0; i < bodies.size(); i++) {
            SkyBody b = bodies.get(i);
            int o = i * 3;
            if (!project(cam, benu[o], benu[o + 1], benu[o + 2])) continue;
            drawBodyDisc(b, tmp.x, tmp.y, px, night);
        }
        shapeRenderer.end();

        // 3) Labels
        BitmapFont font = getC().styleSingleton.getBitmapFont();
        spriteBatch.begin();
        // constellation names
        if (night > 0.15f) {
            font.setColor(0.6f, 0.7f, 0.9f, 0.5f * night);
            java.util.List<ConstellationData.Label> labs = sky.getConstellations().labels;
            float[] lenu = sky.getLabelEnu();
            for (int i = 0; i < labs.size(); i++) {
                int o = i * 3;
                if (project(cam, lenu[o], lenu[o + 1], lenu[o + 2])) {
                    font.draw(spriteBatch, labs.get(i).name, tmp.x + 4 * px, tmp.y);
                }
            }
            // bright star names
            font.setColor(0.85f, 0.9f, 1.0f, 0.75f * night);
            float[] nenu = sky.getNamedStarEnu();
            for (int i = 0; i < SkyModel.NAMED_STARS.length; i++) {
                int o = i * 3;
                if (project(cam, nenu[o], nenu[o + 1], nenu[o + 2])) {
                    font.draw(spriteBatch, SkyModel.NAMED_STARS[i].name, tmp.x + 5 * px, tmp.y + 5 * px);
                }
            }
        }
        // Sun/Moon/planet names (always, so they read in daylight too)
        for (int i = 0; i < bodies.size(); i++) {
            SkyBody b = bodies.get(i);
            int o = i * 3;
            if (!project(cam, benu[o], benu[o + 1], benu[o + 2])) continue;
            if (b.kind == SkyBody.Kind.PLANET && night < 0.15f) continue; // planets only show at dusk
            String label = b.kind == SkyBody.Kind.MOON
                    ? String.format(java.util.Locale.ENGLISH, "%s %d%%", b.name, Math.round(b.phase * 100))
                    : b.name;
            font.setColor(b.r, b.g, b.b, 0.9f);
            font.draw(spriteBatch, label, tmp.x + 6 * px, tmp.y + 6 * px);
        }
        font.setColor(Color.WHITE);
        spriteBatch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawBodyDisc(SkyBody b, float x, float y, float px, float night) {
        if (b.kind == SkyBody.Kind.PLANET) {
            if (night < 0.05f) return; // planets fade in at dusk
            float radius = MathUtils.clamp((2.0f - (float) b.magnitude) * 0.7f + 1.2f, 1.2f, 5.0f) * px;
            shapeRenderer.setColor(b.r, b.g, b.b, MathUtils.clamp(0.6f + night, 0.6f, 1f));
            shapeRenderer.circle(x, y, radius, 12);
            return;
        }
        // Sun and Moon: a disc of a roughly realistic apparent size (~0.5°), with a soft glow.
        float radius = Math.max(3f, Gdx.graphics.getHeight() / 70f);
        if (b.kind == SkyBody.Kind.SUN) {
            shapeRenderer.setColor(1f, 0.85f, 0.4f, 0.25f);
            shapeRenderer.circle(x, y, radius * 2.2f, 24);
            shapeRenderer.setColor(1f, 0.95f, 0.7f, 1f);
            shapeRenderer.circle(x, y, radius, 24);
        } else { // MOON — plain disc; the illuminated percentage is shown in its label.
            shapeRenderer.setColor(0.20f, 0.22f, 0.28f, 0.9f);
            shapeRenderer.circle(x, y, radius * 1.06f, 24); // faint dark rim
            shapeRenderer.setColor(0.86f, 0.87f, 0.83f, 0.95f);
            shapeRenderer.circle(x, y, radius, 24);
        }
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
