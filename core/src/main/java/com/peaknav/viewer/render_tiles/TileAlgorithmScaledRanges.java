package com.peaknav.viewer.render_tiles;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;

import java.util.LinkedList;
import java.util.List;

public class TileAlgorithmScaledRanges {

    private final float startLat;
    private final float startLon;
    private final byte startZoom;
    private final int tileSize;
    private final LatLong center;
    private double maxDistance;

    public TileAlgorithmScaledRanges(float startLat, float startLon, byte startZoom, int tileSize, double maxDistance) {

        this.startLat = startLat;
        this.startLon = startLon;
        this.startZoom = startZoom;
        this.tileSize = tileSize;
        this.maxDistance = maxDistance;

        center = new LatLong(startLat, startLon);
    }

    private class TARange {
        int min_x, min_y, max_x, max_y;
    }

    private final TARange current = new TARange();
    private final TARange excluded = new TARange();
    private boolean excludedSet = false;

    public void get_tile_range_with_upper(int tile_x, int tile_y, byte zoom) {
        excluded.min_x = (tile_x - 1) / 2;
        excluded.min_y = (tile_y - 1) / 2;
        excluded.max_x = (tile_x + 1) / 2;
        excluded.max_y = (tile_y + 1) / 2;

        current.min_x = 2 * (excluded.min_x);
        current.min_y = 2 * (excluded.min_y);
        current.max_x = 2 * (excluded.max_x) + 1;
        current.max_y = 2 * (excluded.max_y) + 1;
    }

    private List<Tile> findForRect(int tileX, int tileY, byte zoom) {
        int emin_x = excluded.min_x;
        int emin_y = excluded.min_y;
        int emax_x = excluded.max_x;
        int emax_y = excluded.max_y;
        get_tile_range_with_upper(tileX, tileY, zoom);

        List<Tile> tiles = new LinkedList<>();
        for (int x = current.min_x; x <= current.max_x; x++) {
            for (int y = current.min_y; y <= current.max_y; y++) {
                if (excludedSet) {
                    if (emin_x <= x && x <= emax_x && emin_y <= y && y <= emax_y) {
                        continue;
                    }
                }
                Tile newTile = new Tile(x, y, zoom, tileSize);
                if (newTile.getBoundingBox().getCenterPoint().distance(center) > maxDistance)
                    continue;
                tiles.add(newTile);
            }
        }
        excludedSet = true;
        return tiles;
    }

    public List<Tile> getTiles() {

        int tileX = MercatorProjection.longitudeToTileX(startLon, startZoom);
        int tileY = MercatorProjection.latitudeToTileY(startLat, startZoom);
        byte zoom = startZoom;

        List<Tile> tiles = new LinkedList<>();
        int prevSize = -1;
        while (tiles.size() > prevSize) {
            prevSize = tiles.size();
            tiles.addAll(findForRect(tileX, tileY, zoom));
            tileX /= 2;
            tileY /= 2;
            zoom--;
        }
        return tiles;
    }
}
