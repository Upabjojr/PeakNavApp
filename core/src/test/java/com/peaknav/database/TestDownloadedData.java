package com.peaknav.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.geo.Tile;
import com.peaknav.pbf.PbfLayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Deleting what was downloaded: the right files, and no others. What the app deletes on a
 * reader's device cannot be tried out on one, so it is tried here, on folders laid out as
 * the downloads lay them out.
 */
class TestDownloadedData {

    /** The download records, in memory. */
    private static final class Records extends MapSqlite {
        final List<QueuedTile> rows = new ArrayList<>();

        void downloaded(String layer, int x, int y, int zoom) {
            QueuedTile row = new QueuedTile();
            row.tileX = x;
            row.tileY = y;
            row.tileZ = (byte) zoom;
            row.layer = layer;
            row.downloadTime = new Timestamp(1L);
            rows.add(row);
        }

        boolean has(String layer, int x, int y, int zoom) {
            for (QueuedTile row : rows) {
                if (row.layer.equals(layer) && row.tileX == x && row.tileY == y && row.tileZ == zoom) {
                    return true;
                }
            }
            return false;
        }

        @Override public boolean isConnectionOpen() { return true; }
        @Override public void openConnection() { }
        @Override public void createTables() { }
        @Override public void addToDownloadQueueElevationTile(Tile tile) { }
        @Override public void addToDownloadQueueMapData(int tileX, int tileY, int tileZ, PbfLayer pbfLayer) { }
        @Override public void updateDownloadQueueMapDataTimestamp(QueuedTile queuedTile, Timestamp now) { }
        @Override public boolean existDownloadedTiles() { return !rows.isEmpty(); }

        @Override
        public void removeDownloadQueueMapData(QueuedTile gone) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                QueuedTile row = rows.get(i);
                if (row.layer.equals(gone.layer) && row.tileX == gone.tileX && row.tileY == gone.tileY
                        && row.tileZ == gone.tileZ) {
                    rows.remove(i);
                }
            }
        }

        @Override
        public void cleanQueue() {
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (rows.get(i).downloadTime == null) {
                    rows.remove(i);
                }
            }
        }

        @Override
        public List<QueuedTile> getDownloadQueue() {
            return new ArrayList<>();
        }

        @Override
        public List<Tile> getListOfDownloadedTiles(String layer) {
            List<Tile> tiles = new ArrayList<>();
            for (QueuedTile row : rows) {
                if (row.layer.equals(layer) && row.downloadTime != null) {
                    tiles.add(row.toTile());
                }
            }
            return tiles;
        }
    }

    private static File file(Path root, String path, int bytes) throws IOException {
        File file = root.resolve(path).toFile();
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[bytes]);
        }
        return file;
    }

    /**
     * Two blocks side by side, 33/22 and 34/22 of zoom 6, under the one area archive 16/11 of
     * zoom 5, as a download leaves them; and beside them what is not the app's.
     */
    private static Records twoBlocks(Path root) throws IOException {
        Records records = new Records();
        for (int block = 33; block <= 34; block++) {
            records.downloaded("elev", block, 22, 6);
            records.downloaded("PBF_POI", block, 22, 6);
            file(root, "peaknav_downloads/elev_tiles/zoom_06/x_000" + block + "/y_00022.tar.gz", 1000);
            file(root, "elev_tiles/zoom_08/x_00" + (block * 4) + "/y_00088/elev.z08.f000.jpg", 300);
            file(root, "elev_tiles/zoom_08/x_00" + (block * 4 + 3) + "/y_00091/elev.z08.f000.png", 300);
            file(root, "peaknav_downloads/map_folder/PBF_POI/zoom_06/xa_00/xb_" + block
                    + "/ya_00/yb_22_PBF_POI_z_06_x_00" + block + "_y_0022.tar", 500);
            file(root, "map_folder/PBF_POI/zoom_09/xa_02/xb_" + (block * 8 - 200)
                    + "/ya_01/yb_76_PBF_POI_z_09_x_0" + (block * 8) + "_y_0176.osm.pbf", 200);
            int road = block * 4;
            records.downloaded("PBF_HIGHWAYS", road, 88, 8);
            file(root, "peaknav_downloads/map_folder/PBF_HIGHWAYS/zoom_08/xa_01/xb_" + (road - 100)
                    + "/ya_00/yb_88_PBF_HIGHWAYS_z_08_x_0" + road + "_y_0088.tar", 400);
            file(root, "map_folder/PBF_HIGHWAYS/zoom_10/xa_05/xb_" + (road * 4 - 500)
                    + "/ya_03/yb_52_PBF_HIGHWAYS_z_10_x_0" + (road * 4) + "_y_0352.osm.pbf", 100);
        }
        records.downloaded("AREAS", 16, 11, 5);   // 33/22 is its child, 34/22 is 17/11's
        records.downloaded("AREAS", 17, 11, 5);
        file(root, "peaknav_downloads/map_folder/AREAS/zoom_05/xa_00/xb_16/ya_00/yb_11_AREAS_z_05_x_0016_y_0011.tar.gz", 50);
        file(root, "map_folder/AREAS/zoom_09/xa_02/xb_64/ya_01/yb_76_AREAS_z_09_x_0264_y_0176.json", 20);
        // Not the app's, and not to be touched by anything here.
        file(root, "map_folder/map_database.sqlite", 4096);
        file(root, "peaknav_downloads/elev_tiles_alps/README.md", 10);
        file(root, "peaknav_downloads/notes.txt", 10);
        file(root, "markers.gpx", 10);
        return records;
    }

    private static boolean exists(Path root, String path) {
        return root.resolve(path).toFile().exists();
    }

    @Test
    @DisplayName("the archives go, the data and the rest stay")
    void archives(@TempDir Path root, @TempDir Path cache) throws IOException {
        Records records = twoBlocks(root);
        file(root, "peaknav_downloads/elev_tiles/zoom_06/x_00033/y_00023.tar.gz.part-1234", 70);
        DownloadedData data = new DownloadedData(root.toFile(), cache.toFile(), records);

        long expected = 2 * (1000 + 500 + 400) + 50 + 70;
        assertEquals(expected, data.archivesBytes());
        assertEquals(expected, data.deleteArchives());
        assertEquals(0, data.archivesBytes());

        assertFalse(exists(root, "peaknav_downloads/elev_tiles/zoom_06/x_00033/y_00022.tar.gz"));
        assertTrue(exists(root, "elev_tiles/zoom_08/x_00132/y_00088/elev.z08.f000.jpg"), "the data stays");
        assertTrue(exists(root, "peaknav_downloads/elev_tiles_alps/README.md"), "a folder beside the app's stays");
        assertTrue(exists(root, "peaknav_downloads/notes.txt"));
        assertTrue(records.has("elev", 33, 22, 6), "the records stay");
    }

    @Test
    @DisplayName("one block goes, with its roads; its neighbour and the database stay")
    void oneBlock(@TempDir Path root, @TempDir Path cache) throws IOException {
        Records records = twoBlocks(root);
        DownloadedData data = new DownloadedData(root.toFile(), cache.toFile(), records);
        Tile block = new Tile(33, 22, (byte) 6, 256);
        assertTrue(data.isDownloaded(block));

        // What is promised is what is freed, and counting it deletes nothing.
        long expected = 1000 + 300 + 300 + 500 + 200 + 400 + 100 + 50 + 20;
        assertEquals(expected, data.blockBytes(block));
        assertTrue(exists(root, "elev_tiles/zoom_08/x_00132/y_00088/elev.z08.f000.jpg"));
        assertTrue(records.has("elev", 33, 22, 6));

        long freed = data.deleteBlock(block);
        assertEquals(expected, freed);
        assertEquals(0, data.blockBytes(block));

        assertFalse(exists(root, "elev_tiles/zoom_08/x_00132/y_00088"), "the emptied folder goes too");
        assertFalse(exists(root, "map_folder/PBF_POI/zoom_09/xa_02/xb_64/ya_01/yb_76_PBF_POI_z_09_x_0264_y_0176.osm.pbf"));
        assertFalse(exists(root, "map_folder/PBF_HIGHWAYS/zoom_10/xa_05/xb_28/ya_03/yb_52_PBF_HIGHWAYS_z_10_x_0528_y_0352.osm.pbf"));
        assertFalse(records.has("elev", 33, 22, 6));
        assertFalse(records.has("PBF_POI", 33, 22, 6));
        assertFalse(records.has("PBF_HIGHWAYS", 132, 88, 8));
        assertFalse(data.isDownloaded(block));
        // The area names' archive covered this block alone of the four under it.
        assertFalse(records.has("AREAS", 16, 11, 5));

        assertTrue(exists(root, "elev_tiles/zoom_08/x_00136/y_00088/elev.z08.f000.jpg"), "the neighbour stays");
        assertTrue(exists(root, "map_folder/PBF_POI/zoom_09/xa_02/xb_72/ya_01/yb_76_PBF_POI_z_09_x_0272_y_0176.osm.pbf"));
        assertTrue(records.has("elev", 34, 22, 6));
        assertTrue(records.has("PBF_HIGHWAYS", 136, 88, 8));
        assertTrue(records.has("AREAS", 17, 11, 5));
        assertTrue(exists(root, "map_folder/map_database.sqlite"));
        assertTrue(exists(root, "markers.gpx"));
    }

    @Test
    @DisplayName("the area names stay while another block under their archive is left")
    void areaNamesShared(@TempDir Path root, @TempDir Path cache) throws IOException {
        Records records = twoBlocks(root);
        records.downloaded("elev", 32, 22, 6);   // the other child of 16/11 in this row
        DownloadedData data = new DownloadedData(root.toFile(), cache.toFile(), records);

        data.deleteBlock(new Tile(33, 22, (byte) 6, 256));

        assertTrue(records.has("AREAS", 16, 11, 5));
        assertTrue(exists(root,
                "peaknav_downloads/map_folder/AREAS/zoom_05/xa_00/xb_16/ya_00/yb_11_AREAS_z_05_x_0016_y_0011.tar.gz"));
    }

    @Test
    @DisplayName("everything goes but what is not map data")
    void everything(@TempDir Path root, @TempDir Path cache) throws IOException {
        Records records = twoBlocks(root);
        file(cache, "sat_downloads/09b76a44/1/32/0/09b_8_132_88.jpeg", 900);
        DownloadedData data = new DownloadedData(root.toFile(), new File(cache.toFile(), "sat_downloads"), records);

        assertEquals(900, data.imageryBytes());
        assertEquals(2 * (300 + 300 + 200 + 100) + 20, data.mapDataBytes());
        data.deleteAll();

        assertEquals(0, data.mapDataBytes());
        assertEquals(0, data.archivesBytes());
        assertTrue(records.rows.isEmpty());
        assertEquals(900, data.imageryBytes(), "the imagery is its own entry");
        assertTrue(exists(root, "map_folder/map_database.sqlite"));
        assertTrue(exists(root, "peaknav_downloads/elev_tiles_alps/README.md"));
        assertTrue(exists(root, "markers.gpx"));

        assertEquals(900, data.deleteImagery());
        assertEquals(0, data.imageryBytes());
    }

    @Test
    @DisplayName("nothing is deleted, or counted, through a symbolic link")
    void links(@TempDir Path root, @TempDir Path cache, @TempDir Path elsewhere) throws IOException {
        File theirs = file(elsewhere, "zoom_08/x_00132/y_00088/elev.z08.f000.jpg", 300);
        root.resolve("elev_tiles").toFile().mkdirs();
        try {
            Files.createSymbolicLink(root.resolve("elev_tiles/zoom_08"), elsewhere.resolve("zoom_08"));
        } catch (UnsupportedOperationException | IOException noLinksHere) {
            return;
        }
        DownloadedData data = new DownloadedData(root.toFile(), cache.toFile(), new Records());

        assertEquals(0, data.mapDataBytes());
        data.deleteAll();
        data.deleteBlock(new Tile(33, 22, (byte) 6, 256));
        assertTrue(theirs.exists(), "what a link leads to is someone else's");
    }

    @Test
    @DisplayName("sizes read as a reader takes them in")
    void sizes() {
        assertEquals("0 MB", DownloadedData.readable(0));
        assertEquals("2.5 MB", DownloadedData.readable(2_621_440L));
        assertEquals("850 MB", DownloadedData.readable(850L * 1024 * 1024));
        assertEquals("1.2 GB", DownloadedData.readable(1_288_490_189L));
    }
}
