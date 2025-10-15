package com.peaknav.database;

import static android.content.Context.MODE_PRIVATE;

import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.peaknav.pbf.PbfLayer;

import org.mapsforge.core.model.Tile;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class MapSqliteAndroid extends MapSqlite {
    private SQLiteDatabase sqLiteDatabase = null;
    private final Context context;
    private SQLiteStatement stmtInsertIntoNodes;
    private SQLiteStatement stmtInsertIntoNodeTags;
    private SQLiteStatement stmtInsertIntoWays;
    private SQLiteStatement stmtInsertIntoWayNodes;

    public MapSqliteAndroid(Context context) {
        super();
        this.context = context;
    }

    @Override
    public boolean isConnectionOpen() {
        return sqLiteDatabase != null;
    }

    @Override
    public synchronized void openConnection() {
        if (sqLiteDatabase == null) {
            synchronized (this) {
                if (sqLiteDatabase == null) {
                    sqLiteDatabase = context.openOrCreateDatabase("map_db", MODE_PRIVATE, null);

                    createTables();
                }
            }
        }
    }

    @Override
    public void createTables() {
        try {
            sqLiteDatabase.beginTransaction();

            sqLiteDatabase.execSQL(sqlCreateTableDownloadQueue);

            sqLiteDatabase.setTransactionSuccessful();
        }
        finally {
            sqLiteDatabase.endTransaction();
        }
    }

    @Override
    public void addToDownloadQueueElevationTile(Tile tile) {
        SQLiteStatement stmt = sqLiteDatabase.compileStatement(
                sqlInsertIntoDownloadQueue);
        stmt.clearBindings();
        stmt.bindLong(1, tile.tileX);
        stmt.bindLong(2, tile.tileY);
        stmt.bindLong(3, tile.zoomLevel);
        stmt.bindString(4, LAYER_ELEV);
        stmt.executeInsert();
    }

    @Override
    public void addToDownloadQueueMapData(int tileX, int tileY, int tileZ, PbfLayer pbfLayer) {
        SQLiteStatement statement = sqLiteDatabase.compileStatement (
                sqlInsertIntoDownloadQueue);
        statement.bindLong (1, tileX);
        statement.bindLong (2, tileY);
        statement.bindLong (3, tileZ);
        statement.bindString(4, pbfLayer.name());
        statement.executeUpdateDelete();
    }

    @Override
    public synchronized void updateDownloadQueueMapDataTimestamp(QueuedTile queuedTile, Timestamp now) {
        SQLiteStatement statement = sqLiteDatabase.compileStatement (
                sqlUpdateDownloadQueueMapData);
        statement.clearBindings();
        statement.bindLong(1, now.getTime());
        statement.bindLong (2, queuedTile.tileX);
        statement.bindLong (3, queuedTile.tileY);
        statement.bindLong (4, queuedTile.tileZ);
        statement.bindString(5, queuedTile.layer);
        statement.executeUpdateDelete();
    }

    @Override
    public void removeDownloadQueueMapData(QueuedTile queuedTile) {
        SQLiteStatement statement = sqLiteDatabase.compileStatement (
                sqlRemoveDownloadQueueMapData);
        statement.clearBindings();
        statement.bindLong (1, queuedTile.tileX);
        statement.bindLong (2, queuedTile.tileY);
        statement.bindLong (3, queuedTile.tileZ);
        statement.bindString(4, queuedTile.layer);
        statement.executeUpdateDelete();
    }

    @Override
    public void cleanQueue() {
        SQLiteStatement statement = sqLiteDatabase.compileStatement(
                sqlRemoveDownloadQueueNotDownloaded);
        statement.executeUpdateDelete();
    }


    @Override
    public boolean existDownloadedTiles() {
        Cursor cursor = sqLiteDatabase.rawQuery(countDownloadQueue, new String[]{});
        cursor.moveToFirst();
        int count2 = cursor.getInt(0);
        if (count2 == 0)
            return false;
        return true;
    }

    @Override
    public List<QueuedTile> getDownloadQueue() {
        List<QueuedTile> queuedTiles = new ArrayList<>(512);
        Cursor rs = sqLiteDatabase.rawQuery(
                sqlQueryDownloadQueue, new String[]{});
        if (rs.getCount() > 0) {
            rs.moveToFirst();
            do {
                QueuedTile queuedTile = new QueuedTile();
                queuedTile.tileX = rs.getInt(rs.getColumnIndexOrThrow("tile_x"));
                queuedTile.tileY = rs.getInt(rs.getColumnIndexOrThrow("tile_y"));
                queuedTile.tileZ = (byte) rs.getInt(rs.getColumnIndexOrThrow("tile_z"));
                queuedTile.layer = rs.getString(rs.getColumnIndexOrThrow("layer_type"));
                try {
                    queuedTile.pbfLayer = PbfLayer.valueOf(queuedTile.layer);
                } catch (IllegalArgumentException iae) {}
                queuedTiles.add(queuedTile);
            } while (rs.moveToNext());
        }
        return queuedTiles;
    }

    @Override
    public List<Tile> getListOfDownloadedTiles(String layer_name) {
        Cursor cur = sqLiteDatabase.rawQuery(sqlQueryDownloadedTiles, new String[] { layer_name });
        List<Tile> tiles = new ArrayList<>(cur.getCount());
        if (cur.getCount() > 0) {
            cur.moveToFirst();
            do {
                tiles.add(new Tile(
                        cur.getInt(0),
                        cur.getInt(1),
                        (byte) cur.getInt(2),
                        MF_ZOOM));
            } while (cur.moveToNext());
        }
        return tiles;
    }
}
