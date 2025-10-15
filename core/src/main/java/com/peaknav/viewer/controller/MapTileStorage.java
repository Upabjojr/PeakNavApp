package com.peaknav.viewer.controller;

import static com.peaknav.viewer.tiles.MapTile.MapTileState.IS_DRAWN;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.google.gson.Gson;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.Tile;

public class MapTileStorage {

    // public ReentrantLock mapTilesLock = new ReentrantLock();
    public final Deque<MapTile> mapTilesForDisposal = new ConcurrentLinkedDeque<>();
    public boolean readyToDispose = false;

    // TODO: this should be a CopyOnWriteArrayList:
    private final CopyOnWriteArrayList<MapTile> mapTiles = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Tile, MapTile> mapTileMap = new ConcurrentHashMap<>();
    private final ExecutorService disposeOldMapTiles = Executors.newSingleThreadExecutor();

    public synchronized void setMapTileList(List<MapTile> mapTiles) {
        List<MapTile> previousMapTiles = new LinkedList<>(this.mapTiles);
        this.mapTiles.clear();
        this.mapTiles.addAll(mapTiles);
        this.mapTileMap.clear();
        for (MapTile mapTile : mapTiles) {
            this.mapTileMap.put(
                    mapTile.tile,
                    mapTile);
        }
        disposeOldMapTiles.execute(() -> {
            previousMapTiles.removeAll(mapTiles);
            mapTilesForDisposal.addAll(previousMapTiles);
        });
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
