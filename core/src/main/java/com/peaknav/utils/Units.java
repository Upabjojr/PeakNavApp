package com.peaknav.utils;

import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.viewer.tiles.TileId;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.LatLongUtils;

public class Units {
    public final static double radiusOfEarth = 6371000.f; // meters
    public final static float radiusOfEarthInLatits = convertMetersToLatits(radiusOfEarth); // latits
    public static final int cLondexShift = 3600;
    public static final int cLatdexShift = 1800;
    public final static int SUB = 20;
    public final static float COORD_STEP = 1.f/ SUB;
    private static final int cLondexFactor = 10000;
    // private final float corrX0, corrX1, corrY0, corrY1;
    public static final float deg2rad = 0.017453292519943295f;

    public static TileId getTileNumber(final double lat, final double lon, final int zoom) {
        int xtile = (int)Math.floor( (lon + 180) / 360 * (1<<zoom) ) ;
        int ytile = (int)Math.floor( (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1<<zoom) ) ;
        xtile = correctTileX(xtile, zoom);
        ytile = correctTileY(ytile, zoom);
        return new TileId(zoom, xtile, ytile);
    }

    public static int correctTileX(int xtile, int zoom) {
        if (xtile < 0)
            xtile=0;
        if (xtile >= (1<<zoom))
            xtile=((1<<zoom)-1);
        return xtile;
    }

    public static int correctTileY(int ytile, int zoom) {
        if (ytile < 0)
            ytile=0;
        if (ytile >= (1<<zoom))
            ytile=((1<<zoom)-1);
        return ytile;
    }

    public static TileBoundingBox tile2boundingBox(final int x, final int y, final int zoom) {
        TileBoundingBox bb = new TileBoundingBox();
        bb.north = tile2lat(y, zoom);
        bb.south = tile2lat(y + 1, zoom);
        bb.west = tile2lon(x, zoom);
        bb.east = tile2lon(x + 1, zoom);
        return bb;
    }

    static float tile2lon(int x, int z) {
        return (float) (x / Math.pow(2.0, z) * 360.0 - 180);
    }

    static float tile2lat(int y, int z) {
        double n = Math.PI - (2.0 * Math.PI * y) / Math.pow(2.0, z);
        return (float)Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    public static float convertMetersToLonits(float elevation, float latitude) {
        double metersPerDegLong = 2*Math.PI*Math.cos(Math.toRadians(latitude))*radiusOfEarth/360;
        return (float)(elevation/metersPerDegLong);
    }

    public static double convertLonitsToMeters(double lonit, double latitude) {
        return (lonit*(2*Math.PI*Math.cos(Math.toRadians(latitude))*radiusOfEarth/360));
    }

    public static float convertMetersToLatits(double meters) {
        return (float) (meters/(2*Math.PI*radiusOfEarth/360));
    }

    public static float convertLatitsToMeters(float latit) {
        return (float) (latit*2*Math.PI*radiusOfEarth/360);
    }

    public static double convertLonitsToLatits(double lonit, double latitude) {
        return lonit * Math.cos(Math.toRadians(latitude));
    }

    public static float convertLatitsToLonits(float latit, float latitude) {
        return latit / (float) Math.cos(Math.toRadians(latitude));
    }

    public static int convertLondexLatdexToMapTileIndex(int londex, int latdex) {
        return cLondexFactor*(cLondexShift + londex) + cLatdexShift + latdex;
    }

    public static int convertMapTileIndexToLondex(int index) {
        return index/cLondexFactor - cLondexShift;
    }

    /*
    public static int convertMapTileIndexToLatdex(int index) {
        return index % cLondexFactor - cLatdexShift;
    }

    public static int convertCoordinatesToLondex(double lon) {
        return (int) Math.floor(SUB*lon);
    }

    public static int convertCoordinatesToLatdex(double lat) {
        return (int) Math.floor(SUB*lat);
    }

    public static int convertCoordinatesToMapTileIndex(double lon, double lat) {
        int londex = convertCoordinatesToLondex(lon);
        int latdex = convertCoordinatesToLatdex(lat);

        return convertLondexLatdexToMapTileIndex(londex, latdex);
    }
     */

    public static int convertLatitsToTexture(float latits) {
        return Math.round(256*256*10*(latits + 0.01f))*256 + 255;
    }

    public static float getWidgetUnitStep() {
        return Float.min(Gdx.graphics.getHeight(), Gdx.graphics.getWidth())/10f;
    }

    public static int computeDistanceBetweenWorldVectors(Vector3 pos1, Vector3 pos2) {
        try {
            double sphericalDist = LatLongUtils.sphericalDistance(
                    new LatLong(pos1.y, convertLatitsToLonits(pos1.x, pos1.y)),
                    new LatLong(pos2.y, convertLatitsToLonits(pos2.x, pos2.y))
            );
            return (int) Math.round(
                    Math.sqrt(sphericalDist * sphericalDist +
                            Math.pow(convertLatitsToMeters(pos1.z - pos2.z), 2)));
        } catch (IllegalArgumentException illegalArgumentException) {
            return Integer.MAX_VALUE;
        }
    }

    public static String formatDistanceToUnitSystem(float distanceMeters) {
        return formatDistanceToUnitSystem(distanceMeters, Float.POSITIVE_INFINITY);
    }

    public static String formatDistanceToUnitSystem(float distanceMeters, float thresholdMetersToKm) {
        String elevString;
        switch (P.getUnitSystem()) {
            case METRIC:
                if (distanceMeters > thresholdMetersToKm)
                    elevString = Math.round(distanceMeters/1000) + "km";
                else
                    elevString = Math.round(distanceMeters) + "m";
                break;
            case IMPERIAL:
                int eleFeet = Math.round(3.28084f*distanceMeters);
                if (distanceMeters > thresholdMetersToKm)
                    elevString = Math.round(eleFeet/5280f) + "miles";
                else
                    elevString = eleFeet + "ft";
                break;
            default:
                throw new RuntimeException("Unknown unit system");
        }
        return elevString;
    }
}
