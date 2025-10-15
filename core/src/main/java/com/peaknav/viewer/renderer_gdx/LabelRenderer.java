package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLoadFactory;
import static com.peaknav.utils.Units.deg2rad;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.utils.CrashLogger;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.labels.DrawLabel;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.screens.BackgroundPicManager;

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
        getC().O.iterateOverDisplayablePois(poiObject -> {
            DrawLabel drawLabel = poiObject.drawLabel;
            if (!drawLabel.isVisible())
                return;
            if (drawLabel.drawLabelCategory.rotationAngle != currentAngle) {
                return;
            }
            drawLabel.drawOnSpriteBatch(spriteBatch);
        });
    }

    private void drawDisplayablePoiVerticalLines(int currentAngle) {
        getC().O.iterateOverDisplayablePois(poiObject -> {
            DrawLabel drawLabel = poiObject.drawLabel;
            if (!drawLabel.isVisible())
                return;
            if (drawLabel.drawLabelCategory.rotationAngle != currentAngle) {
                return;
            }
            float upperPos = drawLabel.getScreenLabelY();
            float screenPoiX = drawLabel.getScreenPoiX();
            float screenPoiY = drawLabel.getScreenPoiY();
            shapeRenderer.setColor(drawLabel.drawLabelCategory.getBackgroundColor());
            shapeRenderer.rect(screenPoiX-1, screenPoiY, 3, upperPos-screenPoiY);
        });
    }

    private void drawDisplayablePoiRectangles(int angle) {
        getC().O.iterateOverDisplayablePois(poiObject -> {
            if (!poiObject.drawLabel.isVisible())
                return;
            if (poiObject.drawLabelCategory.rotationAngle != angle)
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
        renderLabelLines();
        renderLabelTexts();
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
        spriteBatch.setTransformMatrix(new Matrix4());
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
