package com.peaknav.viewer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.peaknav.viewer.MapViewerSingleton;

public class BackgroundPicManager {
    private volatile Pixmap backgroundPixmap = null;
    private volatile Texture backgroundTexture = null;
    private int iw, ih;

    public Texture getBackgroundTexture() {
        return backgroundTexture;
    }

    public void setBackgroundTexture(Texture texture) {
        if (texture != null) {
            MapViewerSingleton.getViewerInstance().tableTool.tableCameraControl.setVisible(true);
        }
        backgroundTexture = texture;

        recomputeSizes();
    }

    public void recomputeSizes() {
        if (backgroundTexture == null) {
            return;
        }
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        if (sw > sh) {
            iw = Math.round(1.0f * sh / backgroundTexture.getHeight() * backgroundTexture.getWidth());
            ih = sh;
        } else {
            iw = sw;
            ih = Math.round(1.0f * sw / backgroundTexture.getWidth() * backgroundTexture.getHeight());
        }
    }

    public Pixmap getBackgroundPixmap() { // new Pixmap(new FileHandle("C:\\profili\\U459045\\Pictures\\comp.001.gm.jpg"));
        return backgroundPixmap;
    }

    public void setBackgroundPixmap(Pixmap bg) {
        Pixmap prev = this.backgroundPixmap;
        this.backgroundPixmap = bg;
        if (prev != null) {
            prev.dispose();
        }
        // Drop the old texture so the renderer recreates it from the new pixmap.
        // Texture.dispose() is a GL call and this method may run off the render
        // thread (e.g. from the camera/gallery background executor), so defer the
        // dispose to the GL thread.
        final Texture prevTexture = this.backgroundTexture;
        this.backgroundTexture = null;
        if (prevTexture != null) {
            Gdx.app.postRunnable(prevTexture::dispose);
        }
    }

    public int getWidth() {
        return iw;
    }

    public int getHeight() {
        return ih;
    }
}
