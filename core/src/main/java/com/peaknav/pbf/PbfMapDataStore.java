package com.peaknav.pbf;

import static com.peaknav.pbf.PbfLayer.PBF_HIGHWAYS;
import static com.peaknav.pbf.PbfLayer.PBF_POI;
import static com.peaknav.utils.PathUtils.findTileWithDataByZoomingOut;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.PointOfInterest;
import org.mapsforge.map.datastore.Way;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class PbfMapDataStore extends MapDataStore {

    private static final String TAG = "PbfMapDataStore";
    private final PbfDataCache cache = new PbfDataCache();
    // private Map<Tile, MapReadResult> cachedDataForTiles = new HashMap<>();
    // private Map<Tile, MapReadResult> cachedDataForTilesPoi = new HashMap<>();

    public PbfMapDataStore() {
    }

    @Override
    public BoundingBox boundingBox() {
        // TODO: handle the proper bounding box:
        return new BoundingBox(-90, -180, 90, 180);
    }

    @Override
    public void close() {

    }

    @Override
    public long getDataTimestamp(Tile tile) {
        return 0;
    }

    private void insertWayIfContained(BoundingBox bb, Way way, MapReadResult mapReadResult) {
        for (LatLong[] latLongs : way.latLongs) {
            for (LatLong latLong : latLongs) {
                if (bb.contains(latLong)) {
                    mapReadResult.ways.add(way);
                    return;
                }
                // TODO: this could be speeded up by pre-caching the bounding box of ways:
                // break;
            }
        }
    }

    private MapReadResult trimMapReadResultForSubTile(Tile tile, MapReadResult mapReadResultData) {
        // TODO: is this necessary?
        final BoundingBox bb = tile.getBoundingBox();
        MapReadResult mapReadResult = new MapReadResult();
        for (PointOfInterest poi : mapReadResultData.pointOfInterests) {
            if (bb.contains(poi.position)) {
                mapReadResult.pointOfInterests.add(poi);
            }
        }
        for (Way way : mapReadResultData.ways) {
            insertWayIfContained(bb, way, mapReadResult);
        }
        return mapReadResult;
    }

    /*
    private MapReadResult readMapDataA(Tile dataTile, PbfLayer pbfLayer, Tile tile) {
        String externalFilePath = mapSqlite.queryMapPbfData(dataTile, pbfLayer);
        File file;
        if (externalFilePath == null) {
            file = getPbfExternalFilePath(dataTile, pbfLayer);
            if (file.exists()) {
                externalFilePath = file.getPath();
            } else {
                PeakNavUtils.getLogger().info(TAG, "externalFilePath not found");
                return new MapReadResult();
            }
        } else {
            file = new File(Gdx.files.getExternalStoragePath(), externalFilePath);
        }

        MapReadResult mapReadResultData = new MapReadResult();
        try {
            InputStream inputStream = new FileInputStream(file);
            BlockReaderAdapter adapter = new PbfToMapsforge(tile, mapReadResultData, pbfLayer);
            BlockInputStream blockInputStream = new BlockInputStream(inputStream, adapter);
            blockInputStream.process();
        } catch (FileNotFoundException fileNotFoundException) {
            mapSqlite.removeMapPbfData(externalFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mapReadResultData;
    }
     */

    private MapReadResult readMapDataByLayer(Tile tile, PbfLayer pbfLayer) {

        Tile tileWithData = findTileWithDataByZoomingOut(tile, pbfLayer);
        if (tileWithData == null) {
            return new MapReadResult();
        }

        MapReadResult mapReadResultData = cache.get(tileWithData, pbfLayer);
        if (tileWithData == tile) {
            return mapReadResultData; // TODO: does this occur?
        }
        // TOO MUCH STORAGE: layerCache.put(tile, mapReadResult);
        return trimMapReadResultForSubTile(tile, mapReadResultData);
    }

    @Override
    public MapReadResult readMapData(Tile tile) {
        return readMapDataByLayer(tile, PBF_HIGHWAYS);
    }

    @Override
    public MapReadResult readPoiData(Tile tile) {
        return readMapDataByLayer(tile, PBF_POI);
    }

    public void readPoiDataByRangeLazy(Tile tileCenter, int range, CallbackMapResult callbackMapResult) {

        Set<Tile> tileSet = new HashSet<>();
        for (int i = 0; i <= range; i++) {
            for (int x = -i; x <= i; x++) {
                for (int y = -i; y <= i; y++) {
                    if (Math.abs(x) != i && Math.abs(y) != i)
                        continue;
                    Tile current = new Tile(
                            tileCenter.tileX + x,
                            tileCenter.tileY + y,
                            tileCenter.zoomLevel, tileCenter.tileSize);
                    Tile supTile = findTileWithDataByZoomingOut(current, PbfLayer.PBF_POI);
                    if (supTile != null)
                        tileSet.add(supTile);
                }
            }
        }
        List<Tile> tiles = new LinkedList<>(tileSet);
        Collections.sort(tiles, (t1, t2) -> {
            int d1 = Math.abs(t1.tileX - tileCenter.tileX) + Math.abs(t1.tileY - tileCenter.tileY);
            int d2 = Math.abs(t2.tileX - tileCenter.tileX) + Math.abs(t2.tileY - tileCenter.tileY);
            return Integer.compare(d1, d2);
        });
        for (Tile tile : tiles) {
            MapReadResult mapReadResult = readPoiData(tile);
            callbackMapResult.call(mapReadResult);
        }
    }

    public MapReadResult readPoiDataByRange(Tile tileCenter, int range) {
        MapReadResult result = new MapReadResult();
        for (int i = 0; i <= range; i++) {
            for (int x = -i; x <= i; x++) {
                for (int y = -i; y <= i; y++) {
                    if (Math.abs(x) != i && Math.abs(y) != i)
                        continue;
                    if (x*x + y*y > i*i)
                        continue;
                    Tile current = new Tile(
                            tileCenter.tileX + x,
                            tileCenter.tileY + y,
                            tileCenter.zoomLevel, tileCenter.tileSize);
                    result.add(readPoiData(current), false);
                }
            }
        }
        return result;
    }

    @Override
    public LatLong startPosition() {
        return null;
    }

    @Override
    public Byte startZoomLevel() {
        return null;
    }

    @Override
    public boolean supportsTile(Tile tile) {
        return true;
    }

    public synchronized void resetCache() {
        cache.clear();
    }

    public interface CallbackMapResult {
        void call(MapReadResult mapReadResult);
    }
}
