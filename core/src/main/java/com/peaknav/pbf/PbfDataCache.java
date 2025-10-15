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
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import crosby.binary.file.BlockInputStream;
import crosby.binary.file.BlockReaderAdapter;

public class PbfDataCache {
    private static final String TAG = "PbfDataCache";
    private final EnumMap<PbfLayer, Map<Tile, MapReadResult>> readerCache = new EnumMap<>(PbfLayer.class);

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
            processTile(tile, pbfLayer, mapReadResult);
            cache.put(tile, mapReadResult);
        }
        return mapReadResult;
    }

    public void clear() {
        for (Map<Tile, MapReadResult> v : readerCache.values()) {
            v.clear();
        }
    }
}
