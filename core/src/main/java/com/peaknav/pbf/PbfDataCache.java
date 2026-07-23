package com.peaknav.pbf;

import static com.peaknav.utils.PathUtils.getPbfExternalFilePath;

import com.peaknav.utils.PeakNavUtils;

import org.mapsforge.core.model.Tile;
import org.mapsforge.map.datastore.MapReadResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import crosby.binary.file.BlockInputStream;
import crosby.binary.file.BlockReaderAdapter;

public class PbfDataCache {
    private static final String TAG = "PbfDataCache";
    private final EnumMap<PbfLayer, Map<Tile, MapReadResult>> readerCache = new EnumMap<>(PbfLayer.class);

    // Parsed tile data (mapsforge Ways/POIs, the bulk of the heap's Tag/LatLong instances) used
    // to be kept for every tile visited until the next location change, so panning far within one
    // downloaded area grew it steadily. Cap the total across all layers and drop the
    // oldest-inserted entries once over: the active working set is only ~25 tiles per refresh
    // (deltaDistancePOIs), so this leaves ample headroom while bounding the retention. An evicted
    // tile is simply re-parsed from disk on its next miss.
    private static final int MAX_CACHED_TILES = 512;
    private final ArrayDeque<LayerTile> insertionOrder = new ArrayDeque<>();
    private final Object evictionLock = new Object();

    private static final class LayerTile {
        final PbfLayer layer;
        final Tile tile;
        LayerTile(PbfLayer layer, Tile tile) {
            this.layer = layer;
            this.tile = tile;
        }
    }

    public PbfDataCache() {
        for (PbfLayer pbfLayer : PbfLayer.values()) {
            readerCache.put(pbfLayer, new ConcurrentHashMap<>());
        }
    }

    public MapReadResult processTile(Tile dataTile, PbfLayer pbfLayer, MapReadResult mapReadResult) {
        File file = getPbfExternalFilePath(dataTile, pbfLayer);

        if (!file.exists()) {
            PeakNavUtils.getLogger().info(TAG, "externalFilePath not found");
            return new MapReadResult();
        }

        try {
            InputStream inputStream = new FileInputStream(file);
            BlockReaderAdapter adapter = new PbfTileBinaryParser(dataTile, mapReadResult);
            BlockInputStream blockInputStream = new BlockInputStream(inputStream, adapter);
            blockInputStream.process();
        } catch (FileNotFoundException fileNotFoundException) {
            // mapSqlite.removeMapPbfData(externalFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mapReadResult;
    }

    public MapReadResult get(Tile tile, PbfLayer pbfLayer) {
        Map<Tile, MapReadResult> cache = readerCache.get(pbfLayer);
        MapReadResult mapReadResult = cache.get(tile);
        if (mapReadResult == null) {
            mapReadResult = new MapReadResult();
            // Parse outside any lock (it hits disk); putIfAbsent settles the benign race where two
            // threads parse the same tile at once, and only the winner records it for eviction.
            processTile(tile, pbfLayer, mapReadResult);
            MapReadResult existing = cache.putIfAbsent(tile, mapReadResult);
            if (existing != null) {
                mapReadResult = existing;
            } else {
                recordInsertionAndEvict(pbfLayer, tile);
            }
        }
        return mapReadResult;
    }

    private void recordInsertionAndEvict(PbfLayer layer, Tile tile) {
        synchronized (evictionLock) {
            insertionOrder.addLast(new LayerTile(layer, tile));
            while (insertionOrder.size() > MAX_CACHED_TILES) {
                LayerTile evicted = insertionOrder.pollFirst();
                if (evicted != null) {
                    // Dropping the map entry only makes it eligible for GC; any caller still
                    // holding the returned MapReadResult keeps its copy alive until done.
                    readerCache.get(evicted.layer).remove(evicted.tile);
                }
            }
        }
    }

    public void clear() {
        for (Map<Tile, MapReadResult> v : readerCache.values()) {
            v.clear();
        }
        synchronized (evictionLock) {
            insertionOrder.clear();
        }
    }
}
