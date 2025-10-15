package com.peaknav.viewer.labels;


import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.peaknav.viewer.screens.MapViewerScreen;

public class DrawLabel {
    // TODO: remove this class?
    private final MapViewerScreen mapViewerScreen;
    public final DrawLabelCategory drawLabelCategory;
    private volatile float screenLabelY;
    public final GlyphLayout glyphLayoutSmall;
    public final GlyphLayout glyphLayoutMedium;
    public volatile float invRotUpperLeftGlyphX, invRotUpperLeftGlyphY;
    private volatile float screenPoiX, screenPoiY;
    // public final Vector3 position3D = new Vector3();
    public volatile boolean hiddenByMountains;
    // public volatile boolean hidden;
    public volatile boolean hiddenBehind;
    public volatile boolean hiddenByLabel;
    private final Polygon polygon = new Polygon(new float[8]);
    public final ReentrantLock lock = new ReentrantLock();
    public final PoiObject poiObject;
    private static final float heightScaleY = 0.2f;
    private float rectangleHeight = 0f;
    private float rectangleWidth = 0f;

    public void drawOnSpriteBatch(SpriteBatch spriteBatch) {
        BitmapFont bitmapFont = getDrawLabelFont();
        bitmapFont.draw(spriteBatch, getCurrentGlyphLayout(), invRotUpperLeftGlyphX, invRotUpperLeftGlyphY);
    }

    private GlyphLayout getCurrentGlyphLayout() {
        if (P.getViewLargeFonts()) {
            return glyphLayoutMedium;
        } else {
            return glyphLayoutSmall;
        }
    }

    public boolean isVisible() {
        return isVisibleByPreferences() && (!hiddenByMountains) &&
                (!hiddenBehind) &&
                (!hiddenByLabel);
    }

    public boolean isVisibleIgnoreCameraOrientation() {
        return isVisibleByPreferences() && (!hiddenByMountains) &&
                (!hiddenByLabel);
    }

    public boolean isVisibleByMountains() {
        return isVisibleByPreferences() && (!hiddenByMountains);
    }

    public void drawRectangle(ShapeRenderer shapeRenderer) {
        if (polygon == null)
            return;
        shapeRenderer.setColor(drawLabelCategory.getBackgroundColor());
        float[] vertices = polygon.getTransformedVertices();
        shapeRenderer.triangle(vertices[0], vertices[1], vertices[2], vertices[3], vertices[4], vertices[5]);
        shapeRenderer.triangle(vertices[4], vertices[5], vertices[6], vertices[7], vertices[0], vertices[1]);
        shapeRenderer.setColor(Color.RED);
    }

    public DrawLabel(DrawLabelCategory drawLabelCategory, PoiObject poiObject) {
        this.poiObject = poiObject;
        this.drawLabelCategory = drawLabelCategory;
        this.mapViewerScreen = getC().getMapViewerScreen();

        updatePosition(poiObject.getPosition3D(tempVec3));

        String text = this.getText();
        Color color = this.drawLabelCategory.getTextColor();

        BitmapFont bitmapFontMedium = getC().styleSingleton.getBitmapFontMedium();
        BitmapFont bitmapFontSmall =  getC().styleSingleton.getBitmapFontSmall();

        glyphLayoutMedium = new GlyphLayout(bitmapFontMedium, text, color, mapViewerScreen.targetWidth, Align.left, false);
        glyphLayoutSmall = new GlyphLayout(bitmapFontSmall, text, color, mapViewerScreen.targetWidth, Align.left, false);
    }


    public boolean equals(Object other) {
        if (!(other instanceof DrawLabel)) {
            return false;
        }
        DrawLabel dl = (DrawLabel) other;
        return dl.getText().equals(getText()); // && dl.labelX == labelX && dl.labelY == labelY;
    }

    private String getText() {
        return drawLabelCategory.getTextFromDrawLabel(poiObject);
    }

    public void updateHiddenByMountains() {
        if (poiObject.name.equals("Doss del Sabion")) {
                System.err.println("a");
        }
        hiddenByMountains = !getC().visibility.checkVisible(
                poiObject.getPosition3D(tempVec1), mapViewerScreen.impactPixmap);
    }

    // TODO: check if we can reduce the number of these temp vars:
    private final Vector3 tempVec1 = new Vector3();
    private final Vector3 tempVec2 = new Vector3();
    private final Vector3 tempVec3 = new Vector3();

    private final Vector3 mPosition = new Vector3();
    private final Vector3 tempVec4 = new Vector3();

    public void updatePosition() {
        updatePosition(poiObject.getPosition3D(tempVec2));
        updateLabelPolygonCoordinates();
    }

    private void updatePosition(Vector3 tempVec) {
        mPosition.set(tempVec);
        try {
            // TODO: camera lock?
            mapViewerScreen.cam.project(mPosition);
        } catch (NullPointerException nullPointerException) {
            return;
        }
        screenPoiX = mPosition.x;
        screenPoiY = mPosition.y;
        screenLabelY = Float.max(
                getCategoryScreenLabelY(),
                screenPoiY + drawLabelCategory.shiftLabelY);
        updateHiddenBehindCamera();
    }

    public void updateHiddenBehindCamera() {
        poiObject.getPosition3D(tempVec4);
        hiddenBehind = tempVec4.sub(mapViewerScreen.cam.position).dot(mapViewerScreen.cam.direction) < 0;
    }

    private float getCategoryScreenLabelY() {
        switch (drawLabelCategory) {
            case PEAK:
                return Gdx.graphics.getHeight()*0.6f;
            case ALPINE_HUT:
                return Gdx.graphics.getHeight()*0.34f;
        }
        return Gdx.graphics.getHeight()*0.4f;
    }

    public void updateGlyphData(final List<Polygon> polygonsFront, final List<Polygon> polygonsBack) {
        List<Polygon> polygons;
        if (this.hiddenBehind) {
            polygons = polygonsBack;
        } else {
            polygons = polygonsFront;
        }

        // updatePositionExtra();

        for (Polygon previousRect : polygons) {
            if (Intersector.overlapConvexPolygons(polygon, previousRect)) {
                this.hiddenByLabel = true;
                return;
            }
        }
        hiddenByLabel = false;
        polygons.add(polygon);

    }

    public void updateLabelPolygonCoordinates() {
        GlyphLayout glyphLayout = getCurrentGlyphLayout();

        rectangleHeight = 1.3f * glyphLayout.height;
        float deltaW = glyphLayout.height * heightScaleY;

        rectangleWidth = glyphLayout.width;
        float rwp2 = rectangleWidth +2*deltaW;

        float[] polyVerts = polygon.getVertices();
        polyVerts[0] = -deltaW;
        polyVerts[1] = -deltaW;
        polyVerts[2] = rwp2;
        polyVerts[3] = -deltaW;
        polyVerts[4] = rwp2;
        polyVerts[5] = rectangleHeight + 1.5f*deltaW;
        polyVerts[6] = -deltaW;
        polyVerts[7] = rectangleHeight + 1.5f*deltaW;

        polygon.setPosition(0, 0);
        polygon.setRotation(0);

        polygon.rotate(drawLabelCategory.rotationAngle);
        polygon.translate(this.screenPoiX, this.screenLabelY);

        correctionForOutOfScreen();

        float rectUpperLeftX = this.screenPoiX - glyphLayout.height * drawLabelCategory.rotationAngleSin;
        float rectUpperLeftY = this.screenLabelY + rectangleHeight * drawLabelCategory.rotationAngleCos;

        float invRotUpperLeftX = drawLabelCategory.rotationAngleCos * rectUpperLeftX + drawLabelCategory.rotationAngleSin * rectUpperLeftY;
        float invRotUpperLeftY = -drawLabelCategory.rotationAngleSin * rectUpperLeftX + drawLabelCategory.rotationAngleCos * rectUpperLeftY;

        this.invRotUpperLeftGlyphX = invRotUpperLeftX;
        this.invRotUpperLeftGlyphY = invRotUpperLeftY;
    }

    private void correctionForOutOfScreen() {
        // float[] verts = polygon.getTransformedVertices();
        float maxX = screenPoiX + rectangleWidth*drawLabelCategory.rotationAngleCos; // verts[2];
        float maxY = screenLabelY + rectangleHeight*drawLabelCategory.rotationAngleCos + rectangleWidth*drawLabelCategory.rotationAngleSin; // verts[5];
        if (screenPoiX > Gdx.graphics.getWidth()) {
            // hiddenBehind = true;
        }
        if (maxY > Gdx.graphics.getHeight()) {
            float diffY = maxY - Gdx.graphics.getHeight();
            if (diffY < screenLabelY - screenPoiY) {
                screenLabelY -= diffY;
                polygon.translate(0, -diffY);
            } else {
                // hiddenBehind = true;
            }
        }
    }

    private static BitmapFont getDrawLabelFont() {
        if (P.getViewLargeFonts()) {
            return getC().styleSingleton.getBitmapFontMedium();
        } else {
            return getC().styleSingleton.getBitmapFontSmall();
        }
    }

    public boolean isVisibleByPreferences() {
        switch (drawLabelCategory) {
            case PEAK:
                return P.isPeakVisible();
            case PISTE:
                return P.getPisteVisible();
            case PLACE:
                return P.isVisiblePlaceNames();
            case ALPINE_HUT:
                return P.isVisibleAlpineHuts();
            default:
                return true;
        }
    }

    public void dispose() {
    }

    public float getScreenLabelY() {
        return screenLabelY;
    }

    public float getScreenPoiX() {
        return screenPoiX;
    }

    public float getScreenPoiY() {
        return screenPoiY;
    }

    public void resetVisibility() {
        hiddenBehind = false;
        hiddenByLabel = false;
        hiddenByMountains = false;
    }
}
