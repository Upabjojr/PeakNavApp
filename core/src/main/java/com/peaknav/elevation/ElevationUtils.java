package com.peaknav.elevation;

import static com.peaknav.database.CheckMissingData.getMaxZoomTile;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.Units.radiusOfEarthInLatits;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED;

import org.mapsforge.core.model.Tile;

import com.peaknav.utils.Units;
import com.peaknav.viewer.tiles.MapTile;

public class ElevationUtils {

    // An unbounded Guava cache of elevation tiles used to be built here and assigned to a
    // field that nothing ever read from or wrote to again - it was already marked "TODO:
    // remove this". Removing it took the last Guava usage out of core along with it; the
    // elevation tiles themselves are held by MapTileStorage, which is what actually caches
    // them.

    private ElevationUtils() {
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
        return roundEarthDropLatits(latitude - getC().L.getTargetLatitude(),
                longitude - getC().L.getTargetLongitude(), getC().L.getTargetLatitude());
    }

    /**
     * How far below the flat world's plane the round Earth has curved away, at a point
     * offset from the reference by {@code dLat},{@code dLon} degrees - in latits,
     * positive downward, to be SUBTRACTED from an elevation.
     *
     * <p>The world is rendered flat: y is latitude, x is longitude times the cosine of
     * the reference latitude, and this correction is what makes distant terrain sink
     * the way the real horizon does. The horizontal metric here is deliberately the
     * SAME equirectangular one the renderer places vertices with - not the true
     * great-circle distance - so a mountain and its label, both corrected through this
     * formula, sink together with the mesh they stand on.
     *
     * <p>The drop is {@code R(sec θ - 1)} for angular distance θ. The textbook
     * tangent-plane drop is {@code R(1 - cos θ)}; the two agree to fourth order
     * (θ²R/2 - about 785 m at 100 km), and against the angle-preserving ideal
     * {@code Rθ·tan(θ/2)} the secant form overshoots by {@code θ⁴R/6} - one metre at
     * 200 km, so the choice between the three is invisible at any distance labels are
     * drawn. See TestRoundEarthCurvature for the numbers.
     */
    public static float roundEarthDropLatits(float dLat, float dLon, float refLat) {
        final float corrForRadius = (float) Math.pow(Math.cos(Math.toRadians(refLat)), 2);
        float dz = (float) Math.sqrt(corrForRadius * dLon * dLon + dLat * dLat) * Units.deg2rad;
        dz = (float) ( - radiusOfEarthInLatits + radiusOfEarthInLatits / Math.cos(dz) );
        return dz;
    }
}
