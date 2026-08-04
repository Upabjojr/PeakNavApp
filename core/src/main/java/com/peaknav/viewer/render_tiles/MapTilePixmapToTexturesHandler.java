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

    /**
     * Tiles whose freshly drawn pixmaps are still waiting to become GL textures.
     *
     * <p>A layer counts as "drawn" the moment its pixmap exists, but it is not on screen until the
     * render thread joins it into a texture here. A capture taken in between shows the tile without
     * that layer — roads and paths in particular, since they are the last to arrive.
     */
    public int pendingTextureJoins() {
        return mapTilesRequiringTextureDrawing.size();
    }
}
