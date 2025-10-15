package com.peaknav.elevation;

import static com.peaknav.database.CheckMissingData.getMaxZoomTile;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.Units.radiusOfEarthInLatits;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED;

import org.mapsforge.core.model.Tile;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.peaknav.utils.Units;
import com.peaknav.viewer.tiles.MapTile;

public class ElevationUtils {
    // TODO: implement a proper cache, this is just a hash-map:
    private static Cache<Integer, ElevationTile> elevationTileCache; // TODO: remove this

    private ElevationUtils() {
    }

    public static void initializeElevationTileCache() {
        elevationTileCache = CacheBuilder.newBuilder()
                .build();
    }

    private static MapTile getElevationMapTileForQuery(double lon, double lat, boolean wait) {
        Tile index = getMaxZoomTile(lat, lon);
        MapTile mapTile;
        while (true) {
            mapTile = getC().mapTileStorage.getFromMapIndexLessEq(index);
            if (mapTile != null)
                break;
            if (!wait)
                return null;
            synchronized (getC().tileManager) {
                try {
                    getC().tileManager.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        while (mapTile.getMapTileState() == ELEVATION_DATA_NOT_LOADED) {
            if (!wait)
                return null;
            synchronized (mapTile) {
                try {
                    mapTile.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return mapTile;
    }

    // TODO: maybe this should not depend on MapTile?
    public static Float getElevationLatitsFromMaxCoords(double lon, double lat, boolean wait) {
        MapTile mapTile = getElevationMapTileForQuery(lon, lat, wait);
        if (mapTile == null)
            return null;
        if (mapTile.isDisposed())
            return null;
        if (mapTile.elevationImage == null)
            return null;
        return mapTile.elevationImage.getTileElevationLatitsFromMaxCoords(lon, lat);
    }

    public static Float getElevationLatitsFromMaxCoords(double lon, double lat) {
        return getElevationLatitsFromMaxCoords(lon, lat, true);
    }

    public static float getElevationCorrectionForRoundEarth(float latitude, float longitude) {
        final float cLon = getC().L.getTargetLongitude();
        final float cLat = getC().L.getTargetLatitude();
        final float corrForRadius = (float) Math.pow(Math.cos(Math.toRadians(cLat)), 2);

        float dLon = longitude - cLon;
        float dLat = latitude - cLat;

        float dz = (float) Math.sqrt(corrForRadius * dLon * dLon + dLat * dLat) * Units.deg2rad;
        dz = (float) ( - radiusOfEarthInLatits + radiusOfEarthInLatits / Math.cos(dz) );
        return dz;
    }
}
