package com.peaknav.viewer.mapscreens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Disposable;
import com.peaknav.geo.BoundingBox;
import com.peaknav.geo.Tile;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A flat map to pick a point on: web-mercator tiles, dragged, pinched, scrolled and tapped.
 *
 * <p>The search screen and the download chooser used to be osmdroid views, which exist only on
 * Android; the desktop had a Swing window and iOS a text prompt, and neither had a map at all.
 * This is the same thing as a scene2d widget, so the three platforms share one implementation.
 *
 * <p>The tiles come through {@link SatelliteImageProvider}, the code that already feeds the 3D
 * view: it downloads with a timeout, writes through a temporary file and keeps a disk cache,
 * and it does so on every platform. The widget only decodes what is on disk (on a worker) and
 * turns it into a texture (on the render thread). A tile that is not loaded yet is drawn from
 * the nearest coarser one already in memory, so zooming shows a blurred map rather than holes.
 *
 * <p>The decoded tiles are shared by every map, in {@link #TILES}, and outlive the screen that
 * loaded them: opening the download chooser after the search screen showed the same place draws
 * those tiles at once instead of reading each one off the disk again. The disk cache below it is
 * the provider's own, which the 3D view's satellite layer shares.
 *
 * <p>Coordinates are kept as web-mercator fractions, 0..1 across and down the world, and the
 * zoom as a real number: level z is 2^z tiles across.
 */
public class SlippyMap extends Widget implements Disposable {

    public interface TapListener {
        void tapped(double latitude, double longitude);
    }

    /** A set of boxes drawn over the map in one colour: downloaded tiles, the area to fetch. */
    public static final class Shading {
        final List<BoundingBox> boxes;
        final Color fill;
        final Color outline;
        /** How far inside each box the outline runs, in outline widths: 0 is on the edge. */
        final int inset;
        /** The outline's thickness, in the map's standard line widths. */
        final float thickness;

        public Shading(List<BoundingBox> boxes, Color fill, Color outline) {
            this(boxes, fill, outline, 0, 1f);
        }

        /**
         * Layers whose boxes coincide - the same tile downloaded for several kinds of data - would
         * draw their outlines on top of one another, and only the last would show. Set a little
         * inside each other, they run side by side.
         */
        public Shading(List<BoundingBox> boxes, Color fill, Color outline, int inset, float thickness) {
            this.boxes = boxes;
            this.fill = fill;
            this.outline = outline;
            this.inset = inset;
            this.thickness = thickness;
        }
    }

    /** Fetches and decodes tiles off the render thread. A few, so one slow tile does not stall the rest. */
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "slippy-map-tiles");
        t.setDaemon(true);
        return t;
    });

    /** Textures kept in memory; a phone screen shows about 30, and this keeps a few screens' worth. */
    private static final int MAX_TEXTURES = 160;
    /** A tile that failed is not asked for again until this long has passed. */
    private static final long RETRY_MILLIS = 15_000;
    /**
     * A queued tile no map has drawn a wish for in this long is dropped before it is fetched:
     * the map has moved on. Long enough to span a few frames at the idle frame rate.
     */
    private static final long STALE_MILLIS = 700;

    private SatelliteImageProvider provider;
    private final float tileSize;
    private final Drawable white;
    private final Drawable marker;
    private final float markerSize;
    private final BitmapFont attributionFont;

    private double centerX = 0.5, centerY = 0.5;
    private float zoom = 3f;
    private float minZoom = 1f;
    private float maxZoom = 14f;

    /** The tiles in memory, shared by every map; see {@link TileTextures}. Render thread only. */
    private static final TileTextures TILES = new TileTextures();

    private final List<Shading> shadings = new ArrayList<>();
    private Double markerLat, markerLon;
    private TapListener tapListener;
    private final GlyphLayout attributionLayout = new GlyphLayout();

    private float zoomAtPinchStart;

    /**
     * @param widgetUnit the interface's unit (a button's width), which sizes the tiles and the
     *                   marker: a 256 px tile is a stamp on a phone's 1080 px screen
     */
    public SlippyMap(SatelliteImageProvider provider, float widgetUnit, Drawable white,
                     Drawable marker, BitmapFont attributionFont) {
        this.provider = provider;
        this.tileSize = Math.max(256f, 2.4f * widgetUnit);
        this.white = white;
        this.marker = marker;
        this.markerSize = widgetUnit;
        this.attributionFont = attributionFont;
        setTouchable(Touchable.enabled);

        addListener(new ActorGestureListener() {
            @Override
            public void touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (pointer == 1) {
                    zoomAtPinchStart = zoom;
                }
            }

            @Override
            public void pan(InputEvent event, float x, float y, float deltaX, float deltaY) {
                double world = worldSize();
                centerX -= deltaX / world;
                centerY += deltaY / world;
                clampCenter();
            }

            @Override
            public void zoom(InputEvent event, float initialDistance, float distance) {
                if (initialDistance <= 0 || distance <= 0) {
                    return;
                }
                setZoomAround(zoomAtPinchStart + (float) (Math.log(distance / initialDistance) / Math.log(2)),
                        getWidth() / 2, getHeight() / 2);
            }

            @Override
            public void tap(InputEvent event, float x, float y, int count, int button) {
                if (count >= 2) {
                    setZoomAround(zoom + 1, x, y);
                    return;
                }
                if (tapListener != null) {
                    double mx = centerX + (x - getWidth() / 2) / worldSize();
                    double my = centerY - (y - getHeight() / 2) / worldSize();
                    tapListener.tapped(latitudeOf(my), longitudeOf(wrap(mx)));
                }
            }
        });

        // The mouse wheel, on the desktop. Scroll events go to the stage's scroll focus, which
        // nothing gives the map unless the pointer entering it does.
        addListener(new InputListener() {
            @Override
            public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                setZoomAround(zoom - 0.5f * amountY, x, y);
                return true;
            }

            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (getStage() != null) {
                    getStage().setScrollFocus(SlippyMap.this);
                }
            }
        });
    }

    /** Render thread: shows another provider's imagery, from its own cache or its server. */
    public void setProvider(SatelliteImageProvider provider) {
        if (provider == null || provider.getId().equals(this.provider.getId())) {
            return;
        }
        // Nothing to clear: the shared tiles are keyed by provider, and the other provider's
        // stay in memory in case the reader switches back.
        this.provider = provider;
    }

    public SatelliteImageProvider getProvider() {
        return provider;
    }

    public void setTapListener(TapListener tapListener) {
        this.tapListener = tapListener;
    }

    public void setZoomRange(float min, float max) {
        this.minZoom = min;
        this.maxZoom = max;
        setZoom(zoom);
    }

    public void setZoom(float zoom) {
        this.zoom = MathUtils.clamp(zoom, minZoom, maxZoom);
    }

    public float getZoom() {
        return zoom;
    }

    public void zoomBy(float delta) {
        setZoomAround(zoom + delta, getWidth() / 2, getHeight() / 2);
    }

    public void setCenter(double latitude, double longitude) {
        centerX = mercatorX(longitude);
        centerY = mercatorY(latitude);
        clampCenter();
    }

    public double getCenterLatitude() {
        return latitudeOf(centerY);
    }

    public double getCenterLongitude() {
        return longitudeOf(wrap(centerX));
    }

    /** The pin, where a point has been chosen; null hides it. */
    public void setMarker(Double latitude, Double longitude) {
        this.markerLat = latitude;
        this.markerLon = longitude;
    }

    public void setShadings(List<Shading> shadings) {
        this.shadings.clear();
        this.shadings.addAll(shadings);
    }

    /** Keeps the point under local (x, y) where it is while the zoom changes: a pinch's centre. */
    private void setZoomAround(float newZoom, float x, float y) {
        newZoom = MathUtils.clamp(newZoom, minZoom, maxZoom);
        double before = worldSize();
        double mx = centerX + (x - getWidth() / 2) / before;
        double my = centerY - (y - getHeight() / 2) / before;
        zoom = newZoom;
        double after = worldSize();
        centerX = mx - (x - getWidth() / 2) / after;
        centerY = my + (y - getHeight() / 2) / after;
        clampCenter();
    }

    private double worldSize() {
        return tileSize * Math.pow(2, zoom);
    }

    private void clampCenter() {
        centerX = wrap(centerX);
        // Above and below the mercator square there is nothing: the centre stops where the
        // map's own edge meets the widget's.
        double halfHeight = getHeight() / 2 / worldSize();
        if (halfHeight >= 0.5) {
            centerY = 0.5;
        } else {
            centerY = MathUtils.clamp(centerY, halfHeight, 1 - halfHeight);
        }
    }

    private static double wrap(double x) {
        x = x % 1.0;
        return x < 0 ? x + 1 : x;
    }

    static double mercatorX(double longitude) {
        return (longitude + 180) / 360;
    }

    static double mercatorY(double latitude) {
        double lat = Math.toRadians(MathUtils.clamp(latitude, -85.0511, 85.0511));
        return (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2;
    }

    static double longitudeOf(double mx) {
        return mx * 360 - 180;
    }

    static double latitudeOf(double my) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * my))));
    }

    @Override
    public float getPrefWidth() {
        return 0;
    }

    @Override
    public float getPrefHeight() {
        return 0;
    }

    @Override
    public void layout() {
        clampCenter();
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Color old = batch.getColor().cpy();
        batch.setColor(0.1f, 0.12f, 0.14f, parentAlpha);
        white.draw(batch, getX(), getY(), getWidth(), getHeight());
        batch.setColor(1, 1, 1, parentAlpha);

        batch.flush();
        if (clipBegin()) {
            drawTiles(batch);
            drawShadings(batch, parentAlpha);
            drawMarker(batch, parentAlpha);
            drawAttribution(batch, parentAlpha);
            batch.flush();
            clipEnd();
        }
        batch.setColor(old);
    }

    private void drawTiles(Batch batch) {
        int tileZoom = MathUtils.clamp((int) Math.floor(zoom), 0, provider.getMaxZoom());
        int n = 1 << tileZoom;
        double world = worldSize();
        double shown = world / n;   // a tile's size on screen at this zoom
        double left = centerX - getWidth() / 2 / world;
        double top = centerY - getHeight() / 2 / world;
        int tx0 = (int) Math.floor(left * n);
        int tx1 = (int) Math.floor((left + getWidth() / world) * n);
        int ty0 = Math.max(0, (int) Math.floor(top * n));
        int ty1 = Math.min(n - 1, (int) Math.floor((top + getHeight() / world) * n));
        // Nearest the centre first: the order the missing ones are queued in, so after a pan the
        // tiles in the middle of the screen are fetched before those at its edges.
        final double ctx = centerX * n, cty = centerY * n;
        java.util.List<int[]> order = new ArrayList<>();
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                order.add(new int[]{tx, ty});
            }
        }
        java.util.Collections.sort(order, (a, b) -> Double.compare(
                distance2(a[0] + 0.5 - ctx, a[1] + 0.5 - cty), distance2(b[0] + 0.5 - ctx, b[1] + 0.5 - cty)));
        for (int[] t : order) {
            int tx = t[0], ty = t[1];
            int wx = ((tx % n) + n) % n;
            float sx = (float) (getX() + getWidth() / 2 + (tx / (double) n - centerX) * world);
            float syTop = (float) (getY() + getHeight() / 2 - (ty / (double) n - centerY) * world);
            float size = (float) shown;
            drawTile(batch, tileZoom, wx, ty, sx, syTop - size, size);
        }
    }

    private static double distance2(double dx, double dy) {
        return dx * dx + dy * dy;
    }

    /** Draws tile (z, x, y), or the part of a coarser tile in memory that covers it meanwhile. */
    private void drawTile(Batch batch, int z, int x, int y, float sx, float sy, float size) {
        Texture texture = textureOrRequest(z, x, y);
        if (texture != null) {
            batch.draw(texture, sx, sy, size, size, 0, 0, texture.getWidth(), texture.getHeight(), false, false);
            return;
        }
        for (int up = 1; up <= 5 && z - up >= 0; up++) {
            Texture coarse = TILES.get(key(provider, z - up, x >> up, y >> up));
            if (coarse != null) {
                int parts = 1 << up;
                int cw = coarse.getWidth() / parts, ch = coarse.getHeight() / parts;
                int srcX = (x - ((x >> up) << up)) * cw;
                int srcY = (y - ((y >> up) << up)) * ch;
                batch.draw(coarse, sx, sy, size, size, srcX, srcY, cw, ch, false, false);
                return;
            }
        }
    }

    private Texture textureOrRequest(int z, int x, int y) {
        String key = key(provider, z, x, y);
        Texture texture = TILES.get(key);
        if (texture != null) {
            return texture;
        }
        TILES.wanted(key);
        if (!TILES.shouldLoad(key)) {
            return null;
        }
        final SatelliteImageProvider source = provider;
        final Object context = TILES.context;
        LOADER.execute(() -> load(source, context, key, z, x, y));
        return null;
    }

    /** Worker thread: fetch the tile into the provider's cache if need be, and decode it. */
    /**
     * Worker thread. Finishes even when the screen that asked has closed: the tile then lands in
     * the shared cache for the next screen, which is the point of sharing it.
     */
    private static void load(SatelliteImageProvider source, Object context, String key, int z, int x, int y) {
        if (!TILES.stillWanted(key)) {
            // Queued for a view the reader has left - panned away, zoomed, closed. Dropped before
            // anything is fetched, so the tiles now on screen are not kept waiting behind it; if
            // it comes back into view it is simply asked for again.
            Gdx.app.postRunnable(() -> TILES.dropped(key, context));
            return;
        }
        Pixmap pixmap = null;
        {
            try {
                source.downloadTileImageIfNotExists(new Tile(x, y, (byte) z, 256));
                File file = source.getImageFileHandle(z, x, y);
                if (file.exists()) {
                    byte[] bytes = readAll(file);
                    pixmap = new Pixmap(bytes, 0, bytes.length);
                }
            } catch (Throwable e) {
                // A missing, truncated or refused tile: the coarser one stays on show, and the
                // tile is asked for again after RETRY_MILLIS.
                pixmap = null;
            }
        }
        final Pixmap decoded = pixmap;
        Gdx.app.postRunnable(() -> TILES.landed(key, decoded, context));
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return bytes;
        }
    }

    private static String key(SatelliteImageProvider provider, int z, int x, int y) {
        return provider.getId() + '/' + z + '/' + x + '/' + y;
    }

    /**
     * The decoded tiles of every map, least recently drawn dropped first. Render thread only.
     *
     * <p>A texture belongs to the graphics context that made it, and Android and iOS can hand
     * the app a new one - the activity rebuilt, the context lost in the background - while this,
     * being static, lives on with the process. So the cache remembers the context it was filled
     * in and starts again, empty, when another one is current: drawing a texture of a dead
     * context shows garbage or nothing. Those textures are not disposed, their context having
     * taken them with it.
     */
    private static final class TileTextures {
        private final LinkedHashMap<String, Texture> textures = new LinkedHashMap<String, Texture>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Texture> eldest) {
                if (size() > MAX_TEXTURES) {
                    eldest.getValue().dispose();
                    return true;
                }
                return false;
            }
        };
        private final Set<String> pending = new HashSet<>();
        /** When a map last drew a wish for each missing tile; read by the loader threads. */
        private final java.util.concurrent.ConcurrentHashMap<String, Long> lastWanted =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Long> failedAt = new HashMap<>();
        /** The graphics the textures were made under: Gdx.graphics is replaced with the context. */
        Object context;

        private void checkContext() {
            if (context != Gdx.graphics) {
                textures.clear();
                pending.clear();
                failedAt.clear();
                lastWanted.clear();
                context = Gdx.graphics;
            }
        }

        Texture get(String key) {
            checkContext();
            return textures.get(key);
        }

        void wanted(String key) {
            lastWanted.put(key, System.currentTimeMillis());
        }

        /** Any thread: whether some map still wanted the tile a moment ago. */
        boolean stillWanted(String key) {
            Long at = lastWanted.get(key);
            return at != null && System.currentTimeMillis() - at < STALE_MILLIS;
        }

        void dropped(String key, Object loadedUnder) {
            if (loadedUnder == context) {
                pending.remove(key);
                // Forgotten unless a map has wanted it again since: the marks would pile up.
                if (!stillWanted(key)) {
                    lastWanted.remove(key);
                }
            }
        }

        /** Whether a tile that is not in memory should be asked for now: not already, not failing. */
        boolean shouldLoad(String key) {
            if (pending.contains(key)) {
                return false;
            }
            Long failed = failedAt.get(key);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MILLIS) {
                return false;
            }
            pending.add(key);
            return true;
        }

        void landed(String key, Pixmap decoded, Object loadedUnder) {
            if (loadedUnder != context) {
                // Asked for under a context that has gone; its bookkeeping went with it.
                if (decoded != null) {
                    decoded.dispose();
                }
                return;
            }
            pending.remove(key);
            lastWanted.remove(key);
            if (decoded == null) {
                failedAt.put(key, System.currentTimeMillis());
                return;
            }
            Texture texture = new Texture(decoded);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            decoded.dispose();
            Texture old = textures.put(key, texture);
            if (old != null) {
                old.dispose();
            }
        }
    }

    private void drawShadings(Batch batch, float parentAlpha) {
        float line = Math.max(1f, markerSize * 0.03f);
        for (Shading shading : shadings) {
            for (BoundingBox box : shading.boxes) {
                float[] r = screenRect(box);
                if (r == null) {
                    continue;
                }
                if (shading.fill != null) {
                    batch.setColor(shading.fill.r, shading.fill.g, shading.fill.b, shading.fill.a * parentAlpha);
                    white.draw(batch, r[0], r[1], r[2], r[3]);
                }
                if (shading.outline != null) {
                    float w = line * shading.thickness;
                    float in = shading.inset * line * 1.5f;
                    float x = r[0] + in, y = r[1] + in, bw = r[2] - 2 * in, bh = r[3] - 2 * in;
                    if (bw <= 2 * w || bh <= 2 * w) {
                        continue;
                    }
                    batch.setColor(shading.outline.r, shading.outline.g, shading.outline.b, shading.outline.a * parentAlpha);
                    white.draw(batch, x, y, bw, w);
                    white.draw(batch, x, y + bh - w, bw, w);
                    white.draw(batch, x, y, w, bh);
                    white.draw(batch, x + bw - w, y, w, bh);
                }
            }
        }
        batch.setColor(1, 1, 1, parentAlpha);
    }

    /** A lat/lon box as x, y, width, height on screen - a rectangle, as mercator keeps them. */
    private float[] screenRect(BoundingBox box) {
        double world = worldSize();
        double x0 = mercatorX(box.minLongitude), x1 = mercatorX(box.maxLongitude);
        double yTop = mercatorY(box.maxLatitude), yBottom = mercatorY(box.minLatitude);
        // The copy of the world nearest the centre, so a box does not vanish across the
        // antimeridian when the map has been panned round it.
        double shift = Math.rint(centerX - (x0 + x1) / 2);
        x0 += shift;
        x1 += shift;
        float sx = (float) (getX() + getWidth() / 2 + (x0 - centerX) * world);
        float sw = (float) ((x1 - x0) * world);
        float syTop = (float) (getY() + getHeight() / 2 - (yTop - centerY) * world);
        float sh = (float) ((yBottom - yTop) * world);
        if (sw < 0.5f || sh < 0.5f) {
            return null;
        }
        return new float[]{sx, syTop - sh, sw, sh};
    }

    private void drawMarker(Batch batch, float parentAlpha) {
        if (markerLat == null || markerLon == null || marker == null) {
            return;
        }
        double world = worldSize();
        double mx = mercatorX(markerLon);
        mx += Math.rint(centerX - mx);
        float sx = (float) (getX() + getWidth() / 2 + (mx - centerX) * world);
        float sy = (float) (getY() + getHeight() / 2 - (mercatorY(markerLat) - centerY) * world);
        batch.setColor(1, 1, 1, parentAlpha);
        // The pin's point, at the bottom middle of the icon, on the spot.
        marker.draw(batch, sx - markerSize / 2, sy, markerSize, markerSize);
    }

    private void drawAttribution(Batch batch, float parentAlpha) {
        if (attributionFont == null) {
            return;
        }
        String notice = provider.getCopyrightNotice();   // it carries its own © already
        attributionLayout.setText(attributionFont, notice);
        float pad = markerSize * 0.12f;
        batch.setColor(0, 0, 0, 0.45f * parentAlpha);
        white.draw(batch, getX() + getWidth() - attributionLayout.width - 2 * pad, getY(),
                attributionLayout.width + 2 * pad, attributionLayout.height + 2 * pad);
        batch.setColor(1, 1, 1, parentAlpha);
        attributionFont.draw(batch, attributionLayout,
                getX() + getWidth() - attributionLayout.width - pad, getY() + attributionLayout.height + pad);
    }

    /**
     * Nothing of its own to release: the tiles stay in the shared cache for the next map, which
     * bounds their number (MAX_TEXTURES) whatever opens and closes.
     */
    @Override
    public void dispose() {
    }
}
