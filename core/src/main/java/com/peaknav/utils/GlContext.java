package com.peaknav.utils;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;

/**
 * Which GL context the textures made now belong to, for the caches that outlive one.
 *
 * <p>A cache kept in a static field lives as long as the process, and the context can go from
 * under it two ways: Android rebuilds the activity, which replaces {@code Gdx.graphics}; or it
 * drops the EGL context in the background and makes a new one for the same activity, which
 * replaces nothing a cache could compare. libGDX reloads its managed textures then, and one of
 * them is kept here for that alone: its reload is the sign the context was made again.
 * Textures made from a Pixmap are not managed - the caches' own - and are dead after it.
 *
 * <p>Render thread only.
 */
public final class GlContext {

    private GlContext() {
    }

    private static Object graphics;
    private static Object token = new Object();
    private static Texture sentinel;
    /** Set by the sentinel's reload, on the render thread: the context was made again. */
    private static boolean remade;

    /**
     * A token for the current context: the same object for as long as the context lives, a new
     * one once it has been replaced. Textures made under an older token are gone with their
     * context, and must not be disposed: their names may now be another texture's.
     */
    public static Object current() {
        if (graphics != Gdx.graphics) {
            graphics = Gdx.graphics;
            token = new Object();
            remade = false;
            // Made under the new app: libGDX keeps its managed textures by application.
            sentinel = new Texture(new Sentinel());
        } else if (remade) {
            remade = false;
            token = new Object();
        }
        return token;
    }

    /** One pixel, uploaded by hand, so that each reload after the first is seen. */
    private static final class Sentinel implements TextureData {
        private boolean uploaded;

        @Override
        public TextureDataType getType() {
            return TextureDataType.Custom;
        }

        @Override
        public boolean isPrepared() {
            return true;
        }

        @Override
        public void prepare() {
        }

        @Override
        public Pixmap consumePixmap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean disposePixmap() {
            return false;
        }

        @Override
        public void consumeCustomData(int target) {
            if (uploaded) {
                remade = true;
            }
            uploaded = true;
            java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4);
            Gdx.gl.glTexImage2D(target, 0, GL20.GL_RGBA, 1, 1, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixel);
        }

        @Override
        public int getWidth() {
            return 1;
        }

        @Override
        public int getHeight() {
            return 1;
        }

        @Override
        public Pixmap.Format getFormat() {
            return Pixmap.Format.RGBA8888;
        }

        @Override
        public boolean useMipMaps() {
            return false;
        }

        @Override
        public boolean isManaged() {
            return true;
        }
    }
}
