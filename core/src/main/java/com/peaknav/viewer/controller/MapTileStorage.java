package com.peaknav.viewer.controller;

import static com.peaknav.viewer.tiles.MapTile.MapTileState.IS_DRAWN;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.google.gson.Gson;
import com.peaknav.viewer.tiles.MapTile;

import com.peaknav.geo.Tile;

public class MapTileStorage {

    // public ReentrantLock mapTilesLock = new ReentrantLock();
    public final Deque<MapTile> mapTilesForDisposal = new ConcurrentLinkedDeque<>();
    public volatile boolean readyToDispose = false;

    /**
     * The tiles, and the same by their index: each replaced whole, never changed in place.
     * They were cleared and then filled, and a frame drawn in between had no terrain, a lookup
     * made in between found no tile.
     */
    private volatile List<MapTile> mapTiles = new CopyOnWriteArrayList<>();
    private volatile Map<Tile, MapTile> mapTileMap = new ConcurrentHashMap<>();

    public synchronized void setMapTileList(List<MapTile> mapTiles) {
        List<MapTile> previousMapTiles = this.mapTiles;
        Map<Tile, MapTile> byIndex = new ConcurrentHashMap<>();
        for (MapTile mapTile : mapTiles) {
            byIndex.put(mapTile.tile, mapTile);
        }
        this.mapTileMap = byIndex;
        this.mapTiles = new CopyOnWriteArrayList<>(mapTiles);
        // The tiles left out go for disposal now, before the caller says they are ready to
        // be: queued from another thread, as they were, the render thread could take the
        // flag and find the queue still empty, and they kept their meshes and textures
        // until the next update - which standing still never comes.
        Set<MapTile> kept = new HashSet<>(mapTiles);
        for (MapTile previous : previousMapTiles) {
            if (!kept.contains(previous)) {
                mapTilesForDisposal.add(previous);
            }
        }
    }

    public MapTile getFromMapIndexLessEq(final Tile tile1) {
        Tile tile = tile1;
        while (true) {
            MapTile found = this.mapTileMap.get(tile);
            if (found != null)
                return found;
            tile = tile.getParent();
            if (tile == null) {
                return null; // throw new RuntimeException("no parent tile!");
            }
        }
    }

    public MapTile getFromTileIndexExact(final Tile tile1) {
        return this.mapTileMap.get(tile1);
    }

    public synchronized boolean containsMapIndex(Tile mapIndex) {
        return this.getFromMapIndexLessEq(mapIndex) != null;
    }

    public List<MapTile> getMapTiles() {
        return mapTiles;
    }

    public synchronized int getNumberOfMapTiles() {
        return this.mapTiles.size();
    }

    public synchronized void exportToJson(String fileName) {
        try {
            String json = new Gson().toJson(mapTiles);
            FileOutputStream outputStream = new FileOutputStream(fileName);
            outputStream.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getSummaryStats() {
        List<MapTile> mapTiles = getMapTiles();
        int totalSize = 0;
        for (MapTile mapTile : mapTiles) {
            int size = mapTile.getWidth() * mapTile.getHeight();
            totalSize += size;
        }
        float avgSize = ((float)totalSize) / ((float)mapTiles.size());
        return "Tiles: " + mapTiles.size() + " avgVert: " + avgSize + " totVert: " + totalSize;
    }

    public void serializeToDir(String dir) {
        FileHandle dirHandle = Gdx.files.external(dir);
        dirHandle.mkdirs();
        for (MapTile mapTile : mapTiles) {
            String name = mapTile.tile.toString();
            name = name.replaceAll(", ", ".");
            name += ".float32";
            mapTile.serializeVerticesToFile(new File(dirHandle.file(), name));
        }
    }

    public void queueWeldersForAlreadyDrawnTiles() {
        for (MapTile mapTile : mapTiles) {
            if (mapTile.getMapTileState() != IS_DRAWN)
                continue;
            mapTile.addWeldersForTile();
        }
    }
}
