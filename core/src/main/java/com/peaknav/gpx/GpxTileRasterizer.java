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
    private static final int HALF_THICK = 2;        // path half-thickness in pixels (~5 px line)
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
                // Split into short sub-segments so the flow phase varies smoothly along the line.
                int steps = Math.max(1, Math.round((float) Math.hypot(bx - ax, by - ay) / 4f));
                for (int s = 0; s < steps; s++) {
                    float t0 = (float) s / steps;
                    float t1 = (float) (s + 1) / steps;
                    float distM = cum[i] + (cum[i + 1] - cum[i]) * (t0 + t1) * 0.5f;
                    float phase = distM / PATTERN_METERS;
                    phase -= (float) Math.floor(phase); // fract -> [0,1), stored in the red channel
                    pixmap.setColor(phase, 0f, 0f, 1f);
                    drawThickLine(pixmap,
                            Math.round(ax + (bx - ax) * t0), Math.round(ay + (by - ay) * t0),
                            Math.round(ax + (bx - ax) * t1), Math.round(ay + (by - ay) * t1));
                }
            }
        }
        return pixmap;
    }

    /** Rough ground distance in metres between two track points (good enough for flow spacing). */
    private static float distMeters(GpxTrack.Point a, GpxTrack.Point b) {
        double meanLat = Math.toRadians((a.lat + b.lat) * 0.5);
        double dy = (b.lat - a.lat) * 111320.0;
        double dx = (b.lon - a.lon) * 111320.0 * Math.cos(meanLat);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** A thick line via parallel perpendicular offsets, with round caps so joins look continuous. */
    private static void drawThickLine(Pixmap pm, int x0, int y0, int x1, int y1) {
        float ddx = x1 - x0;
        float ddy = y1 - y0;
        float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        if (len < 1e-3f) {
            pm.fillCircle(x0, y0, HALF_THICK);
            return;
        }
        float px = -ddy / len; // perpendicular unit
        float py = ddx / len;
        for (int k = -HALF_THICK; k <= HALF_THICK; k++) {
            pm.drawLine(Math.round(x0 + px * k), Math.round(y0 + py * k),
                    Math.round(x1 + px * k), Math.round(y1 + py * k));
        }
        pm.fillCircle(x0, y0, HALF_THICK);
        pm.fillCircle(x1, y1, HALF_THICK);
    }
}
