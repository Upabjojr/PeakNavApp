package com.peaknav.database;

import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import com.badlogic.gdx.Gdx;
import com.peaknav.pbf.PbfLayer;
import org.mapsforge.core.model.Tile;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MapSqliteDesktop extends MapSqlite {
    private Connection connection;

    @Override
    public boolean isConnectionOpen() {
        return connection != null;
    }

    @Override
    public void openConnection() {
        File mapFolder = Gdx.files.external("map_folder").file();
        if (!mapFolder.exists())
            mapFolder.mkdir();
        File file = Gdx.files.external("map_folder/map_database.sqlite").file();
        openConnection(file);
    }

    public void openConnection(File file) {
        try {
            connection = DriverManager.getConnection(
                    String.format("jdbc:sqlite:%s", file.getAbsolutePath().replace("\\", "/")));
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    @Override
    public void createTables() {
        try {
            Statement statement = connection.createStatement();

            statement.execute(sqlCreateTableDownloadQueue);

        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    @Override
    public void addToDownloadQueueElevationTile(Tile tile) {
        try {
            PreparedStatement statement = connection.prepareStatement(
                    sqlInsertIntoDownloadQueue);
            statement.setInt(1, tile.tileX);
            statement.setInt(2, tile.tileY);
            statement.setInt(3, tile.zoomLevel);
            statement.setString(4, LAYER_ELEV);
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addToDownloadQueueMapData(int tileX, int tileY, int tileZ, PbfLayer pbfLayer) {
        try {
            PreparedStatement statement = connection.prepareStatement(
                    sqlInsertIntoDownloadQueue);
            statement.setInt(1, tileX);
            statement.setInt(2, tileY);
            statement.setInt(3, tileZ);
            statement.setString(4, pbfLayer.name());
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public synchronized void updateDownloadQueueMapDataTimestamp(QueuedTile queuedTile, Timestamp now) {
        try {
            PreparedStatement statement = connection.prepareStatement(
                    sqlUpdateDownloadQueueMapData);
            statement.setTimestamp(1, now);
            statement.setInt(2, queuedTile.tileX);
            statement.setInt(3, queuedTile.tileY);
            statement.setInt(4, queuedTile.tileZ);
            statement.setString(5, queuedTile.layer);
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean existDownloadedTiles() {
        try {
            PreparedStatement stmt = connection.prepareStatement(countDownloadQueue);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            int count2 = rs.getInt(1);
            if (count2 == 0)
                return false;
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void removeDownloadQueueMapData(QueuedTile queuedTile) {
        try {
            PreparedStatement statement = connection.prepareStatement(sqlRemoveDownloadQueueMapData);

            statement.setInt(1, queuedTile.tileX);
            statement.setInt(2, queuedTile.tileY);
            statement.setInt(3, queuedTile.tileZ);
            statement.setString(4, queuedTile.layer);

            statement.execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cleanQueue() {
        // TODO
    }

    @Override
    public List<QueuedTile> getDownloadQueue() {
        List<QueuedTile> queuedTiles = new ArrayList<>(512);
        try {
            PreparedStatement statement = connection.prepareStatement(
                    sqlQueryDownloadQueue);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                QueuedTile queuedTile = new QueuedTile();
                queuedTile.tileX = rs.getInt("tile_x");
                queuedTile.tileY = rs.getInt("tile_y");
                queuedTile.tileZ = (byte) rs.getInt("tile_z");
                queuedTile.layer = rs.getString("layer_type");
                try {
                    queuedTile.pbfLayer = PbfLayer.valueOf(queuedTile.layer);
                } catch (IllegalArgumentException iae) {}
                queuedTiles.add(queuedTile);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return queuedTiles;
    }

    @Override
    public List<Tile> getListOfDownloadedTiles(String layer_name) {
        List<Tile> tiles = new LinkedList<>();
        try {
            PreparedStatement statement = connection.prepareStatement(
                    sqlQueryDownloadedTiles);
            statement.setString(1, layer_name);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                tiles.add(new Tile(
                        rs.getInt("tile_x"),
                        rs.getInt("tile_y"),
                        (byte) rs.getInt("tile_z"),
                        MF_ZOOM
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return tiles;
    }

}
