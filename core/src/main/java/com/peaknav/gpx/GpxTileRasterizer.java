package com.peaknav.gpx;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.utils.TileBoundingBox;
import com.peaknav.viewer.render_tiles.PixmapLayerName;
import com.peaknav.viewer.tiles.MapTile;

import java.util.List;

/**
 * Paints the loaded GPX paths onto the map tiles as a per-tile overlay texture, the same way roads
 * are drawn: each tile gets a {@link PixmapLayerName#GPX_PATH} texture in its own UV space, and the
 * terrain shader blends it over the lit tile surface. This keeps the path on the ground — draped,
 * shaded and occluded exactly like the terrain — instead of a separate floating overlay.
 *
 * <p>Runs off the GL thread: it builds pixmaps and hands them to {@link
 * MapTile#setTexturePixmap}, which queues them for upload on the render thread. Each tile records
 * the paths' version it was drawn for, so tiles pick the paths up as they stream in and are
 * redrawn only when the paths actually change.
 */
public final class GpxTileRasterizer {

    private static final int RES = 512;             // overlay resolution per tile
    /**
     * Path half-width in texels, and the width of the soft ramp outside it. The alpha channel
     * holds coverage that falls off linearly across the ramp instead of a hard 0/1 edge: a binary
     * mask can only ever be as smooth as one texel, which is what made the path look like a
     * staircase of blocks when the fly-over camera got close. A ramp keeps its shape under
     * magnification, so the shader can pull a clean antialiased edge out of it at any zoom.
     */
    private static final float CORE_RADIUS = 2.2f;
    private static final float EDGE_FEATHER = 1.8f;
    private static final float INFLUENCE = CORE_RADIUS + EDGE_FEATHER;
    /** Long segments are diced so each one's bounding box stays tight (see rasterize). */
    private static final float CHUNK_PIXELS = 24f;
    private static final float PATTERN_METERS = 120f; // flow band length along the track

    private GpxTileRasterizer() {
    }

    /** Bring every tile's GPX overlay up to date with the current paths and version. */
    public static void updateTiles(List<MapTile> tiles, List<GpxTrack> tracks, int version) {
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        for (GpxTrack t : tracks) {
            for (GpxTrack.Point p : t.getPoints()) {
                minLat = Math.min(minLat, p.lat);
                maxLat = Math.max(maxLat, p.lat);
                minLon = Math.min(minLon, p.lon);
                maxLon = Math.max(maxLon, p.lon);
            }
        }
        boolean anyPath = minLat <= maxLat;

        for (MapTile tile : tiles) {
            if (tile == null || tile.isDisposed() || tile.gpxVersionDrawn == version) {
                continue;
            }
            TileBoundingBox bb = tile.tileBoundingBox;
            if (bb == null) {
                continue;
            }
            boolean intersects = anyPath && !(maxLon < bb.west || minLon > bb.east
                    || maxLat < bb.south || minLat > bb.north);
            if (intersects) {
                tile.setTexturePixmap(PixmapLayerName.GPX_PATH, rasterize(bb, tracks));
                tile.hasGpxTexture = true;
            } else if (tile.hasGpxTexture) {
                // This tile had a path and no longer does (cleared, or the tracks moved off it):
                // overlay an empty pixmap so the stale path disappears.
                tile.setTexturePixmap(PixmapLayerName.GPX_PATH, emptyPixmap());
                tile.hasGpxTexture = false;
            }
            tile.gpxVersionDrawn = version;
        }
    }

    private static Pixmap emptyPixmap() {
        Pixmap pixmap = new Pixmap(RES, RES, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        pixmap.setColor(0f, 0f, 0f, 0f);
        pixmap.fill();
        return pixmap;
    }

    private static Pixmap rasterize(TileBoundingBox bb, List<GpxTrack> tracks) {
        Pixmap pixmap = emptyPixmap();
        float dLon = bb.east - bb.west;
        float dLat = bb.north - bb.south;
        if (dLon == 0f || dLat == 0f) {
            return pixmap;
        }
        // Coverage and phase are accumulated in plain arrays and written to the pixmap in one
        // pass at the end: per-pixel Pixmap calls would be tens of thousands of JNI hops per tile.
        float[] cov = new float[RES * RES];
        float[] phase = new float[RES * RES];
        boolean any = false;

        for (GpxTrack track : tracks) {
            List<GpxTrack.Point> pts = track.getPoints();
            int n = pts.size();
            if (n < 2) {
                continue;
            }
            // Cumulative distance (m) along the track, so each pixel can carry its flow phase.
            float[] cum = new float[n];
            for (int i = 1; i < n; i++) {
                cum[i] = cum[i - 1] + distMeters(pts.get(i - 1), pts.get(i));
            }
            for (int i = 0; i + 1 < n; i++) {
                GpxTrack.Point a = pts.get(i);
                GpxTrack.Point b = pts.get(i + 1);
                // Tile UV, matching MapTile: u = (lon-west)/dLon, v = (north-lat)/dLat (north-up).
                float ax = (a.lon - bb.west) / dLon * RES;
                float ay = (bb.north - a.lat) / dLat * RES;
                float bx = (b.lon - bb.west) / dLon * RES;
                float by = (bb.north - b.lat) / dLat * RES;
                float segLen = (float) Math.hypot(bx - ax, by - ay);
                // Dice long segments so each chunk's bounding box hugs the line; stamping a whole
                // diagonal at once would test the entire tile for a five-texel-wide band.
                int chunks = Math.max(1, (int) Math.ceil(segLen / CHUNK_PIXELS));
                for (int c = 0; c < chunks; c++) {
                    float t0 = (float) c / chunks;
                    float t1 = (float) (c + 1) / chunks;
                    any |= stampSegment(cov, phase,
                            ax + (bx - ax) * t0, ay + (by - ay) * t0,
                            ax + (bx - ax) * t1, ay + (by - ay) * t1,
                            cum[i] + (cum[i + 1] - cum[i]) * t0,
                            cum[i] + (cum[i + 1] - cum[i]) * t1);
                }
            }
        }
        if (any) {
            writeToPixmap(pixmap, cov, phase);
        }
        return pixmap;
    }

    /**
     * Stamps one straight chunk into the coverage/phase buffers, giving every texel within
     * {@link #INFLUENCE} its true distance to the line (so the edge is a ramp, not a staircase)
     * and the flow phase at its own closest point on the line. Where chunks overlap — at joins and
     * where a track crosses itself — the nearer one wins, which keeps corners continuous.
     *
     * @return true if anything was written
     */
    private static boolean stampSegment(float[] cov, float[] phase,
                                        float ax, float ay, float bx, float by,
                                        float distA, float distB) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - INFLUENCE));
        int maxX = Math.min(RES - 1, (int) Math.ceil(Math.max(ax, bx) + INFLUENCE));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - INFLUENCE));
        int maxY = Math.min(RES - 1, (int) Math.ceil(Math.max(ay, by) + INFLUENCE));
        if (minX > maxX || minY > maxY) {
            return false;
        }
        float ex = bx - ax;
        float ey = by - ay;
        float lenSq = ex * ex + ey * ey;
        boolean wrote = false;
        for (int y = minY; y <= maxY; y++) {
            int row = y * RES;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f - ax;
                float py = y + 0.5f - ay;
                float t = (lenSq < 1e-6f) ? 0f : (px * ex + py * ey) / lenSq;
                t = (t < 0f) ? 0f : (t > 1f ? 1f : t);
                float dx = px - ex * t;
                float dy = py - ey * t;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= INFLUENCE) {
                    continue;
                }
                float c = (d <= CORE_RADIUS) ? 1f : (INFLUENCE - d) / EDGE_FEATHER;
                int idx = row + x;
                if (c > cov[idx]) {
                    cov[idx] = c;
                    phase[idx] = (distA + (distB - distA) * t) / PATTERN_METERS;
                    wrote = true;
                }
            }
        }
        return wrote;
    }

    /**
     * Packs the buffers into the pixmap. The phase is stored as its sine and cosine rather than
     * as a raw 0..1 fraction: the fraction wraps, and a linear texture filter blending 0.98 with
     * 0.02 lands in the middle of the pattern instead of across the seam, producing a visible
     * band of garbage at every wrap. Sine/cosine interpolate cleanly through the wrap, so the
     * texture can be filtered smoothly and the shader recovers the angle with atan.
     */
    private static void writeToPixmap(Pixmap pixmap, float[] cov, float[] phase) {
        java.nio.ByteBuffer buf = pixmap.getPixels();
        buf.position(0);
        byte[] row = new byte[RES * 4];
        for (int y = 0; y < RES; y++) {
            int base = y * RES;
            for (int x = 0; x < RES; x++) {
                int idx = base + x;
                float c = cov[idx];
                int o = x * 4;
                if (c <= 0f) {
                    row[o] = 0;
                    row[o + 1] = 0;
                    row[o + 2] = 0;
                    row[o + 3] = 0;
                    continue;
                }
                double ang = phase[idx] * 2.0 * Math.PI;
                row[o] = unorm((float) (Math.sin(ang) * 0.5 + 0.5));
                row[o + 1] = unorm((float) (Math.cos(ang) * 0.5 + 0.5));
                row[o + 2] = 0;
                row[o + 3] = unorm(c > 1f ? 1f : c);
            }
            buf.put(row);
        }
        buf.position(0);
    }

    private static byte unorm(float v) {
        int i = Math.round(v * 255f);
        return (byte) ((i < 0) ? 0 : (i > 255 ? 255 : i));
    }

    /** Rough ground distance in metres between two track points (good enough for flow spacing). */
    private static float distMeters(GpxTrack.Point a, GpxTrack.Point b) {
        double meanLat = Math.toRadians((a.lat + b.lat) * 0.5);
        double dy = (b.lat - a.lat) * 111320.0;
        double dx = (b.lon - a.lon) * 111320.0 * Math.cos(meanLat);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

}
