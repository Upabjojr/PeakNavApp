package com.peaknav.database;

import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import com.peaknav.pbf.PbfLayer;

import org.mapsforge.core.model.Tile;

import java.sql.Timestamp;
import java.util.List;

// TODO: remove this class!!!
public abstract class MapSqlite {

    public final static String LAYER_ELEV = "elev";
    protected final static String
            sqlCreateTableDownloadQueue = "CREATE TABLE IF NOT EXISTS download_queue (" +
                    "tile_x INTEGER NOT NULL," +
                    "tile_y INTEGER NOT NULL," +
                    "tile_z INTEGER NOT NULL," +
                    "layer_type TEXT NOT NULL," +
                    "download_time DATETIME," +
                    "PRIMARY KEY (tile_x, tile_y, tile_z, layer_type)" +
                    ")";

    // protected final static String ;

    protected final static String
    // TODO: maybe this should be INSERT ON CONFLICT IGNORE?
            sqlInsertIntoDownloadQueue = "INSERT OR REPLACE INTO download_queue (tile_x, tile_y, tile_z, layer_type) VALUES (?, ?, ?, ?)";

    protected final static String
            sqlQueryDownloadQueue = "SELECT tile_x, tile_y, tile_z, layer_type " +
                    "FROM download_queue " +
                    "WHERE download_time IS NULL",
            sqlQueryDownloadedTiles = "SELECT tile_x, tile_y, tile_z, layer_type " +
                    "FROM download_queue " +
                    "WHERE download_time IS NOT NULL AND layer_type = ?";

    protected final String
            countDownloadQueue = "SELECT COUNT(*) FROM download_queue";

    protected final String
            sqlUpdateDownloadQueueMapData = "UPDATE download_queue " +
                    "SET download_time = ? " +
                    "WHERE tile_x = ? AND tile_y = ? AND tile_z = ? AND layer_type = ?";

    protected final String
            sqlRemoveDownloadQueueMapData = "DELETE FROM download_queue " +
                    "WHERE tile_x = ? AND tile_y = ? AND tile_z = ? AND layer_type = ?",
            sqlRemoveDownloadQueueNotDownloaded = "DELETE FROM download_queue " +
                    "WHERE download_time IS NULL "
    ;

    public abstract boolean isConnectionOpen();

    public abstract void openConnection();
    public abstract void createTables();
    public abstract void addToDownloadQueueElevationTile(Tile tile); // modify?
    public abstract void addToDownloadQueueMapData(int tileX, int tileY, int tileZ, PbfLayer pbfLayer);

    public abstract void updateDownloadQueueMapDataTimestamp(QueuedTile queuedTile, Timestamp now);

    public abstract boolean existDownloadedTiles();

    public abstract void removeDownloadQueueMapData(QueuedTile queuedTile);

    public abstract void cleanQueue();

    public static class QueuedTile {
        public int tileX, tileY;
        public byte tileZ;
        public String layer;
        public PbfLayer pbfLayer = null;
        public Timestamp downloadTime;

        public Tile toTile() {
            return new Tile(tileX, tileY, tileZ, MF_ZOOM);
        }
    }

    public abstract List<QueuedTile> getDownloadQueue();
    public abstract List<Tile> getListOfDownloadedTiles(String layer_name);
    public List<Tile> getListOfDownloadedTiles(PbfLayer overlay) {
        return getListOfDownloadedTiles(overlay.name());
    }

}
