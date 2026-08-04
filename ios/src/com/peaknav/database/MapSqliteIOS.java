package com.peaknav.database;

import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import com.peaknav.pbf.PbfLayer;

import org.mapsforge.core.model.Tile;
import org.robovm.rt.bro.ptr.LongPtr;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * The downloaded-tile catalogue on iOS, over the system SQLite.
 *
 * <p>Same table and the same SQL as every other platform - both live in {@link MapSqlite} -
 * reached through {@link SQLite3} instead of a JDBC driver, because RoboVM has no JDBC and
 * the driver's natives do not exist for iOS anyway.
 *
 * <p>The file lives in {@code Documents}, not in the caches: it records what has been
 * downloaded, so losing it would silently orphan gigabytes of tiles the app would then
 * believe it never had. (The tiles themselves are cache; this index is not.)
 *
 * <p><b>Threading.</b> Every method is synchronized on this object. The connection is opened
 * without SQLite's own serialisation configured, and the callers here are several download
 * threads plus the render thread; one lock over the lot is both correct and, for a table
 * touched a few times per tile, far cheaper than the alternative being wrong.
 */
public class MapSqliteIOS extends MapSqlite {

    /** The open database, or 0. Opaque to Java - see {@link SQLite3}. */
    private long database = 0;

    /** Statements are prepared per call; only this one is worth keeping, being the hot one. */
    private long insertStatement = 0;

    @Override
    public synchronized boolean isConnectionOpen() {
        return database != 0;
    }

    @Override
    public synchronized void openConnection() {
        openConnection(defaultFile());
    }

    /** Documents/map_folder/map_database.sqlite inside the app's sandbox. */
    private static File defaultFile() {
        String home = System.getenv("HOME");
        File folder = new File(home == null ? "." : home, "Documents/map_folder");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return new File(folder, "map_database.sqlite");
    }

    public synchronized void openConnection(File file) {
        if (database != 0) {
            return;
        }
        LongPtr handle = new LongPtr();
        int result = SQLite3.open(file.getAbsolutePath(), handle);
        if (result != SQLite3.SQLITE_OK) {
            throw new IllegalStateException(
                    "could not open " + file + " (sqlite error " + result + ")");
        }
        database = handle.get();
        // Write-ahead logging, as on the desktop: it lets a reader carry on while a
        // download is writing, which is the normal state of this app. The busy timeout is
        // what turns the remaining contention into a wait rather than an SQLITE_BUSY.
        execute("PRAGMA journal_mode=WAL");
        execute("PRAGMA busy_timeout=30000");
    }

    @Override
    public synchronized void createTables() {
        execute(sqlCreateTableDownloadQueue);
    }

    // ------------------------------------------------------------------ writing

    @Override
    public synchronized void addToDownloadQueueElevationTile(Tile tile) {
        insertQueued(tile.tileX, tile.tileY, tile.zoomLevel, LAYER_ELEV);
    }

    @Override
    public synchronized void addToDownloadQueueMapData(int tileX, int tileY, int tileZ,
                                                       PbfLayer pbfLayer) {
        insertQueued(tileX, tileY, tileZ, pbfLayer.name());
    }

    private void insertQueued(int tileX, int tileY, int tileZ, String layer) {
        if (insertStatement == 0) {
            insertStatement = prepare(sqlInsertIntoDownloadQueue);
        }
        SQLite3.reset(insertStatement);
        SQLite3.bindInt(insertStatement, 1, tileX);
        SQLite3.bindInt(insertStatement, 2, tileY);
        SQLite3.bindInt(insertStatement, 3, tileZ);
        SQLite3.bindString(insertStatement, 4, layer);
        int stepped = SQLite3.step(insertStatement);
        if (stepped != SQLite3.SQLITE_DONE) {
            throw new IllegalStateException("queue insert failed: " + lastError());
        }
    }

    /**
     * Groups the inserts of one download into a single transaction.
     *
     * <p>Without it SQLite commits - and fsyncs - once per row, which is what made queuing
     * a region take seconds per tile on the desktop before the same batching was added
     * there.
     */
    @Override
    public synchronized void beginQueueBatch() {
        execute("BEGIN TRANSACTION");
    }

    @Override
    public synchronized void endQueueBatch() {
        execute("COMMIT");
    }

    @Override
    public synchronized void updateDownloadQueueMapDataTimestamp(QueuedTile queuedTile,
                                                                 Timestamp now) {
        long statement = prepare(sqlUpdateDownloadQueueMapData);
        try {
            // Epoch milliseconds in a DATETIME column: SQLite types are per-value, not per
            // column, and nothing here ever reads the time back - the queries ask only
            // whether it IS NULL. A number cannot be misparsed the way a formatted date can.
            SQLite3.bindLong(statement, 1, now == null ? System.currentTimeMillis() : now.getTime());
            SQLite3.bindInt(statement, 2, queuedTile.tileX);
            SQLite3.bindInt(statement, 3, queuedTile.tileY);
            SQLite3.bindInt(statement, 4, queuedTile.tileZ);
            SQLite3.bindString(statement, 5, queuedTile.layer);
            if (SQLite3.step(statement) != SQLite3.SQLITE_DONE) {
                throw new IllegalStateException("marking a tile downloaded failed: " + lastError());
            }
        } finally {
            SQLite3.finalizeStatement(statement);
        }
    }

    @Override
    public synchronized void removeDownloadQueueMapData(QueuedTile queuedTile) {
        long statement = prepare(sqlRemoveDownloadQueueMapData);
        try {
            SQLite3.bindInt(statement, 1, queuedTile.tileX);
            SQLite3.bindInt(statement, 2, queuedTile.tileY);
            SQLite3.bindInt(statement, 3, queuedTile.tileZ);
            SQLite3.bindString(statement, 4, queuedTile.layer);
            SQLite3.step(statement);
        } finally {
            SQLite3.finalizeStatement(statement);
        }
    }

    @Override
    public synchronized void cleanQueue() {
        execute(sqlRemoveDownloadQueueNotDownloaded);
    }

    // ------------------------------------------------------------------ reading

    @Override
    public synchronized boolean existDownloadedTiles() {
        long statement = prepare(countDownloadQueue);
        try {
            return SQLite3.step(statement) == SQLite3.SQLITE_ROW
                    && SQLite3.columnInt(statement, 0) > 0;
        } finally {
            SQLite3.finalizeStatement(statement);
        }
    }

    @Override
    public synchronized List<QueuedTile> getDownloadQueue() {
        List<QueuedTile> queued = new ArrayList<>();
        long statement = prepare(sqlQueryDownloadQueue);
        try {
            while (SQLite3.step(statement) == SQLite3.SQLITE_ROW) {
                QueuedTile tile = new QueuedTile();
                tile.tileX = SQLite3.columnInt(statement, 0);
                tile.tileY = SQLite3.columnInt(statement, 1);
                tile.tileZ = (byte) SQLite3.columnInt(statement, 2);
                tile.layer = SQLite3.columnString(statement, 3);
                if (tile.layer != null && !LAYER_ELEV.equals(tile.layer)) {
                    // An unknown layer name is data from a newer version of the app; skip
                    // the row rather than dying on valueOf.
                    try {
                        tile.pbfLayer = PbfLayer.valueOf(tile.layer);
                    } catch (IllegalArgumentException unknownLayer) {
                        continue;
                    }
                }
                queued.add(tile);
            }
        } finally {
            SQLite3.finalizeStatement(statement);
        }
        return queued;
    }

    @Override
    public synchronized List<Tile> getListOfDownloadedTiles(String layerName) {
        List<Tile> tiles = new ArrayList<>();
        long statement = prepare(sqlQueryDownloadedTiles);
        try {
            SQLite3.bindString(statement, 1, layerName);
            while (SQLite3.step(statement) == SQLite3.SQLITE_ROW) {
                tiles.add(new Tile(SQLite3.columnInt(statement, 0),
                        SQLite3.columnInt(statement, 1),
                        (byte) SQLite3.columnInt(statement, 2), MF_ZOOM));
            }
        } finally {
            SQLite3.finalizeStatement(statement);
        }
        return tiles;
    }

    // ------------------------------------------------------------------ plumbing

    private long prepare(String sql) {
        requireOpen();
        LongPtr statement = new LongPtr();
        int result = SQLite3.prepare(database, sql, -1, statement, 0);
        if (result != SQLite3.SQLITE_OK || statement.get() == 0) {
            throw new IllegalStateException("could not prepare [" + sql + "]: " + lastError());
        }
        return statement.get();
    }

    private void execute(String sql) {
        requireOpen();
        int result = SQLite3.exec(database, sql, 0, 0, 0);
        if (result != SQLite3.SQLITE_OK) {
            throw new IllegalStateException("[" + sql + "] failed: " + lastError());
        }
    }

    private void requireOpen() {
        if (database == 0) {
            throw new IllegalStateException("the tile database is not open");
        }
    }

    private String lastError() {
        String message = SQLite3.errmsg(database);
        return message == null ? "unknown sqlite error" : message;
    }

    /** Closes the database. Safe to call twice. */
    public synchronized void closeConnection() {
        if (insertStatement != 0) {
            SQLite3.finalizeStatement(insertStatement);
            insertStatement = 0;
        }
        if (database != 0) {
            SQLite3.close(database);
            database = 0;
        }
    }
}
