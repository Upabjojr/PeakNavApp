package com.peaknav.database;

import static com.peaknav.elevation.ElevationImageStorage.getElevationCropsPathJpg;
import static com.peaknav.elevation.ElevationImageStorage.getElevationCropsPathPng;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock.checkS3ElevationExistence;
import static com.peaknav.utils.PathUtils.findTileWithDataByZoomingOut;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLogger;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;

import com.badlogic.gdx.Gdx;
import com.peaknav.elevation.blocks.CheckElevExistBlock;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;

import java.util.HashSet;
import java.util.Set;

public class CheckMissingData {

    private static String TAG = "CheckMissingData";
    private final MapSqlite mapSqlite;

    private Set<Integer> dismissed = new HashSet<>();

    public CheckMissingData(MapSqlite mapSqlite) {
        this.mapSqlite = mapSqlite;
    }

    public static boolean checkMissingElevationForCoord(double latitude, double longitude) {
        Tile tile = getMinZoomTile(latitude, longitude);
        return checkMissingElevationForTile(tile);
    }

    public static Tile getMinZoomTile(double latitude, double longitude) {
        int tileX = MercatorProjection.longitudeToTileX(longitude, MapTile.ZOOM_LEVEL_MIN);
        int tileY = MercatorProjection.latitudeToTileY(latitude, MapTile.ZOOM_LEVEL_MIN);
        return new Tile(tileX, tileY, MapTile.ZOOM_LEVEL_MIN, MapTile.MF_ZOOM);
    }

    public static Tile getMaxZoomTile(double latitude, double longitude) {
        int tileX = MercatorProjection.longitudeToTileX(longitude, MapTile.ZOOM_LEVEL_MIN);
        int tileY = MercatorProjection.latitudeToTileY(latitude, MapTile.ZOOM_LEVEL_MIN);
        return new Tile(tileX, tileY, MapTile.ZOOM_LEVEL_MAX, MapTile.MF_ZOOM);
    }

    public static Tile getTileAtZoomLevel(double latitude, double longitude, byte zoomLevel) {
        int tileX = MercatorProjection.longitudeToTileX(longitude, zoomLevel);
        int tileY = MercatorProjection.latitudeToTileY(latitude, zoomLevel);
        return new Tile(tileX, tileY, zoomLevel, MapTile.MF_ZOOM);
    }

    /**
     * Whether elevation for this tile is missing <em>and could be obtained</em>.
     *
     * <p>Asking only whether the files are on disk is not enough. Out at sea there is no elevation
     * block to fetch, so the files are absent, downloading changes nothing, and the answer stays
     * "missing" for ever — which is what made the app keep offering a download that had already
     * been done. The published blocks are known up front (the same table
     * {@link com.peaknav.elevation.ElevationImageStorage} consults to decide whether a tile has
     * any data at all), so where there is nothing to fetch, nothing is reported missing.
     */
    public static boolean checkMissingElevationForTile(Tile tile) {
        getLogger().debug(TAG, "checkMissingElevationForCoord entered");

        if (!checkS3ElevationExistence(tile.tileX, tile.tileY)) {
            getLogger().debug(TAG, "no elevation block published for " + tile + "; nothing to fetch");
            return false;
        }

        int zoomElevLevel = MapTile.computeZoomElevFactor(tile);
        // TODO: should this check other rescale factors? (i.e. not just 2?)
        boolean cond = (!Gdx.files.external(getElevationCropsPathJpg(tile, zoomElevLevel)).exists())
                ||
                (!Gdx.files.external(getElevationCropsPathPng(tile, zoomElevLevel)).exists());
        getLogger().debug(TAG, "checkMissingElevationForCoord for " + tile +
                " was " + cond);
        return cond;
    }

    private boolean checkMissingByLayerForCoord(double lat, double lon, PbfLayer pbfLayer) {
        byte zoomLevel = (byte) 14;
        int x = MercatorProjection.longitudeToTileX(lon, zoomLevel);
        int y = MercatorProjection.latitudeToTileY(lat, zoomLevel);
        Tile tile = new Tile(x, y, zoomLevel, 256);
        Tile dataTile = findTileWithDataByZoomingOut(tile, pbfLayer);
        boolean missing = (dataTile == null);
        getLogger().debug(TAG, "checkMissingByLayerForCoord for " + lat + ", " + lon +
                    " missing is " + missing);
        return missing;
    }

    private boolean checkMissingHighwaysForCoord(double lat, double lon) {
        return checkMissingByLayerForCoord(lat, lon, PbfLayer.PBF_HIGHWAYS);
    }

    private boolean checkMissingPoiForCoord(double lat, double lon) {
        return checkMissingByLayerForCoord(lat, lon, PbfLayer.PBF_POI);
    }

    public boolean checkMissingDataForCoord(double lat, double lon) {
        if (!CheckElevExistBlock.checkElevationExistence((int) Math.floor(lat), (int) Math.floor(lon)))
            return false;
        if (checkMissingElevationForCoord(lat, lon)) {
            return true;
        }
        return checkMissingHighwaysForCoord(lat, lon) ||
                checkMissingPoiForCoord(lat, lon);
    }

    public void dismiss(double lat, double lon) {
        dismissed.add(encodeDismissed(lat, lon));
    }

    private int encodeDismissed(double lat, double lon) {
        int iLat = (int) Math.floor(lat);
        int iLon = (int) Math.floor(lon);
        return encodeDismissed(iLat, iLon);
    }

    private int encodeDismissed(int iLat, int iLon) {
        return iLon*1000 + iLat;
    }

    public boolean isDismissed(double lat, double lon) {
        return dismissed.contains(encodeDismissed(lat, lon));
    }

    public boolean checkMissingIfNotDismissed(double lat, double lon) {
        if (isDismissed(lat, lon)) {
            return false;
        }
        return checkMissingDataForCoord(lat, lon);
    }

    /**
     * Elevation-only counterpart of {@link #checkMissingIfNotDismissed}, for the prompt raised
     * when the target coordinates move. It has to honour the same dismissal as the in-app banner,
     * otherwise the user gets asked again for an area they already answered about.
     */
    public boolean checkMissingElevationIfNotDismissed(double lat, double lon) {
        if (isDismissed(lat, lon)) {
            return false;
        }
        return checkMissingElevationForCoord(lat, lon);
    }

    public void downloadMissingData(double lat, double lon) {
        if (checkMissingElevationForCoord(lat, lon) ||
                checkMissingPoiForCoord(lat, lon) ||
                checkMissingHighwaysForCoord(lat, lon)
        ) {
            getC().missingDataDownloader.setCoords(lat, lon);
            getC().missingDataDownloader.doDownload();
        }
    }

}
