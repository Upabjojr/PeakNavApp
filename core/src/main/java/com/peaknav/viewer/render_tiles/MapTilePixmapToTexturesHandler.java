package com.peaknav.viewer.render_tiles;

import com.peaknav.viewer.tiles.MapTile;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class MapTilePixmapToTexturesHandler {

    private final Queue<MapTile> mapTilesRequiringTextureDrawing = new LinkedBlockingQueue<>();

    public MapTilePixmapToTexturesHandler() {
    }

    public void renderTextureJoinerAllTiles() {
        while (!mapTilesRequiringTextureDrawing.isEmpty()) {
            MapTile mapTile = mapTilesRequiringTextureDrawing.remove();
            mapTile.renderPixmapsToTextures();
        }
    }

    public void addMapTileToQueue(MapTile mapTile) {
        mapTilesRequiringTextureDrawing.add(mapTile);
    }
}
