package com.peaknav.viewer;

import static com.peaknav.elevation.ElevationUtils.getElevationLatitsFromMaxCoords;
import static com.peaknav.utils.PeakNavUtils.containsUnrenderableCharacters;

import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.PointOfInterest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.peaknav.pbf.PbfMapDataStore;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.labels.PoiObject;
import com.peaknav.utils.CjkLabelNames;
import com.peaknav.utils.Units;
import com.peaknav.viewer.tiles.TileId;

public class MapDataManager {

    // MultiMapDataStore multiMapDataStore = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);
    final PbfMapDataStore pbfMapDataStore;

    final int deltaDistancePOIs = 2;
    final byte poiZoom = 9;

    public static final int MAX_HASH_LENGTH = 12;

    // TreeMap<String, Long> geohashToNode = new TreeMap<>();


    public MapDataManager() {
        pbfMapDataStore = new PbfMapDataStore();
    }

    // TODO: to utility class: (as well as convert methods in MapViewer)
    public static long encodeHashToLong(double latitude, double longitude) {
        return encodeHashToLong(latitude, longitude, 8);
    }

    public static long encodeHashToLong(double latitude, double longitude, int length) {
        boolean isEven = true;
        double minLat = -90.0, maxLat = 90;
        double minLon = -180.0, maxLon = 180.0;
        long bit = 0x8000000000000000L;
        long g = 0;

        long target = 0x8000000000000000L >>> (5 * length);
        while (bit != target) {
            if (isEven) {
                double mid = (minLon + maxLon) / 2;
                if (longitude >= mid) {
                    g |= bit;
                    minLon = mid;
                } else
                    maxLon = mid;
            } else {
                double mid = (minLat + maxLat) / 2;
                if (latitude >= mid) {
                    g |= bit;
                    minLat = mid;
                } else
                    maxLat = mid;
            }

            isEven = !isEven;
            bit >>>= 1;
        }
        return g | length;
    }

    /*public MapReadResult getMapReadResult() {
        Tile tileHighwayCenter = C.L.getHighwaysTileCenter();
        int deltaHighways = C.L.getHighwaysTileRange();
        MapReadResult mapReadResult = sqliteMapDataStore.readMapData(tileHighwayCenter, deltaHighways);
        return mapReadResult;
    }*/

    public void getPOIsByCoordLazy(double currentLatitude, double currentLongitude, CallbackPoiList callbackPoiList) {
        TileId tileId = Units.getTileNumber(currentLatitude, currentLongitude, poiZoom);
        Tile tileCenter = new Tile(tileId.x, tileId.y, poiZoom, 1);

        pbfMapDataStore.readPoiDataByRangeLazy(tileCenter, deltaDistancePOIs, mapReadResult -> {
            System.err.println("number of points read " + mapReadResult.pointOfInterests.size());
            // mapReadResult.pointOfInterests.removeIf(c -> c.tags.stream().noneMatch(x -> x.key.equals("natural") && x.value.equals("peak")));
            ArrayList<PoiObject> poiList = getPoiListFromMapReadResult(mapReadResult);
            callbackPoiList.call(poiList);
        });

    }

    public ArrayList<PoiObject> getPOIsByCoord(double currentLatitude, double currentLongitude) {
        TileId tileId = Units.getTileNumber(currentLatitude, currentLongitude, poiZoom);
        Tile tileCenter = new Tile(tileId.x, tileId.y, poiZoom, 1);

        MapReadResult mapReadResult = pbfMapDataStore.readPoiDataByRange(tileCenter, deltaDistancePOIs);
        System.err.println("number of points read " + mapReadResult.pointOfInterests.size());
        // mapReadResult.pointOfInterests.removeIf(c -> c.tags.stream().noneMatch(x -> x.key.equals("natural") && x.value.equals("peak")));
        return getPoiListFromMapReadResult(mapReadResult);
        // mapReadResult.pointOfInterests;
    }

    private ArrayList<PoiObject> getPoiListFromMapReadResult(MapReadResult mapReadResult) {
        ArrayList<PoiObject> poiList = new ArrayList<>();
        poiList.ensureCapacity(mapReadResult.pointOfInterests.size());
        for (PointOfInterest pointOfInterest : mapReadResult.pointOfInterests) {
            String name = null;
            String name_en = null;
            String name_latn = null;
            String name_ja = null;
            String name_ja_rm = null;
            String name_hira = null;
            boolean hasJaTag = false;
            float lat, lon;
            Float ele = null;
            int isolationParent = -1;
            float prominence = -1;
            DrawLabelCategory drawLabelCategory = null;
            Map<String, String> tags = new HashMap<>();
            for (Tag tag : pointOfInterest.tags) {
                tags.put(tag.key, tag.value);
                if (tag.key.contains(":ja")) {
                    // The data itself declaring the place Japanese - which decides how,
                    // and whether, its kanji may be romanized below.
                    hasJaTag = true;
                }
                switch (tag.key) {
                    case "name":
                        name = tag.value;
                        break;
                    case "name:en":
                        name_en = tag.value;
                        break;
                    case "name:ja":
                        // Some places carry name:ja and no name at all; without this
                        // they had no label whatever the tags offered.
                        name_ja = tag.value;
                        break;
                    case "name:ja_rm":
                    case "alt_name:ja_rm":
                        // Rōmaji from the mapper's own hand - the best Latin form a
                        // Japanese name can have, and it was never read until now.
                        if (name_ja_rm == null) {
                            name_ja_rm = tag.value;
                        }
                        break;
                    case "name:ja-Hira":
                        name_hira = tag.value;
                        break;
                    case "name:ja-Latn":
                    case "name:zh-Latn-pinyin":
                    case "name:ko-Latn":
                        name_latn = tag.value;
                        break;
                    case "ele":
                        try {
                            ele = Float.valueOf(tag.value);
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    case "tourism":
                        if (tag.value.equals("alpine_hut"))
                            drawLabelCategory = DrawLabelCategory.ALPINE_HUT;
                        break;
                    case "place":
                        drawLabelCategory = DrawLabelCategory.PLACE;
                        break;
                    case "natural":
                        if (tag.value.equals("peak"))
                            drawLabelCategory = DrawLabelCategory.PEAK;
                        else if (tag.value.equals("volcano"))
                            drawLabelCategory = DrawLabelCategory.PEAK;
                        break;
                    case "prominence":
                        try {
                            prominence = Float.parseFloat(tag.value);
                        } catch (NumberFormatException notANumber) {
                            // Some are written "3776 m" or with a range; no prominence is
                            // better than a wrong one, so it stays unknown.
                        }
                        break;
                    case "isolation_parent":
                        String isolationParentS = tags.get("isolation_parent");
                        isolationParent = Integer.parseInt(isolationParentS);
                        break;
                }
            }
            lat = (float)pointOfInterest.position.getLatitude();
            lon = (float)pointOfInterest.position.getLongitude();
            if (name == null) {
                name = name_ja;
            }
            if (name == null || drawLabelCategory == null)
                continue;
            if (ele == null) {
                ele = getElevationLatitsFromMaxCoords(lon, lat, false);
            }
            if (containsUnrenderableCharacters(name)) {
                // Latin forms from the data first; kana romanized if that is all there
                // is; and for a kanji-only Japanese name with no reading anywhere, no
                // label at all - the fallback transliterator reads kanji as Chinese,
                // which mislabelled every such mountain in Japan ("gao zuo shan" on
                // 高座山). Chinese and Korean names pass through and keep their correct
                // romanizations.
                name = CjkLabelNames.bestLatinName(
                        name, name_en, name_ja_rm, name_latn, name_hira,
                        hasJaTag, lat, lon);
                if (name == null) {
                    continue;
                }
            }
            if (ele != null) {
                poiList.add(new PoiObject(name, lon, lat, ele, tags, prominence, isolationParent, drawLabelCategory));
            }
        }
        return poiList;
    }

    public PbfMapDataStore getMultiMapDataStore() {
        return pbfMapDataStore;
    }

    public interface CallbackPoiList {
        void call(List<PoiObject> poiObjectList);
    }
}
