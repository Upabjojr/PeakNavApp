package com.peaknav.database;

import com.peaknav.elevation.ElevationImageStorage;
import com.peaknav.geo.Tile;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.utils.PathUtils;
import com.peaknav.viewer.tiles.MapTile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the downloads have put on the device, measured and deleted: the archives kept after
 * they are unpacked, the imagery fetched for the maps, and the map data itself - all of it,
 * or one block of it.
 *
 * <p>A block is a tile of zoom {@value #BLOCK_ZOOM}, the one the elevation and the peaks'
 * archives are cut at. The roads' and pistes' archives are sixteen to a block, and go with
 * it. The area names' archive is four blocks wide, so it goes with the last of its four.
 *
 * <p>Only what the app itself names is touched: files under the folders and with the names
 * its own paths give. On a desktop the folders beside them may hold anything. Nothing is
 * followed through a symbolic link.
 */
public final class DownloadedData {

    public static final byte BLOCK_ZOOM = 6;
    /** The finest tiles any layer is unpacked to: the roads' and pistes'. */
    private static final byte FINEST_ZOOM = PbfLayer.ZOOM_LEVEL_HIGHWAYS;
    /** The zoom the elevation is unpacked to: sixteen tiles to a block. */
    private static final byte ELEVATION_ZOOM = MapTile.ZOOM_LEVEL_MIN;

    private static final String DOWNLOADS = "peaknav_downloads";

    private final File root;
    private final File imagery;
    private final MapSqlite database;

    /**
     * @param root     the app's external folder, which holds {@code map_folder},
     *                 {@code elev_tiles} and the rest
     * @param imagery  the folder the imagery is kept in, in the device's cache
     * @param database the record of what was downloaded; null leaves the records alone
     */
    public DownloadedData(File root, File imagery, MapSqlite database) {
        this.root = real(root);
        this.imagery = real(imagery);
        this.database = database;
    }

    /** A folder by the path it really has, so that the paths built on it can be checked. */
    private static File real(File folder) {
        try {
            return folder.getCanonicalFile();
        } catch (IOException unreadable) {
            return folder.getAbsoluteFile();
        }
    }

    /** The app's own: its external folder, its imagery cache, its database. */
    public static DownloadedData ofTheApp() {
        return new DownloadedData(
                com.badlogic.gdx.Gdx.files.external(".").file(),
                new File(com.peaknav.utils.PeakNavUtils.getCacheDir(), "sat_downloads"),
                com.peaknav.utils.PeakNavUtils.getC().mapSqlite);
    }

    // ------------------------------------------------------------------ the archives

    /** Bytes of the archives kept after unpacking, and of downloads left unfinished. */
    public long archivesBytes() {
        long bytes = 0;
        for (File folder : archiveFolders()) {
            bytes += sizeOf(folder, ARCHIVES);
        }
        return bytes;
    }

    /**
     * Deletes the archives. The data unpacked from them stays, and so do the records: a
     * block downloaded again is fetched again.
     *
     * @return the bytes freed
     */
    public long deleteArchives() {
        long bytes = 0;
        for (File folder : archiveFolders()) {
            bytes += delete(folder, ARCHIVES, false);
        }
        return bytes;
    }

    private List<File> archiveFolders() {
        List<File> folders = new ArrayList<>();
        folders.add(new File(root, DOWNLOADS + "/" + PathUtils.getElevationFolder() + "/zoom_06"));
        for (PbfLayer layer : PbfLayer.values()) {
            folders.add(new File(root, DOWNLOADS + "/" + PathUtils.getMapFolder() + "/" + layer.name()));
        }
        return folders;
    }

    // ------------------------------------------------------------------ the imagery

    /** Bytes of the imagery kept for the maps and the terrain. */
    public long imageryBytes() {
        return sizeOf(imagery, EVERYTHING);
    }

    /** Deletes the imagery kept; it is fetched again as it is looked at. */
    public long deleteImagery() {
        return delete(imagery, EVERYTHING, false);
    }

    // ------------------------------------------------------------------ the map data

    /** Bytes of the map data: elevation, peaks and places, roads, pistes, area names. */
    public long mapDataBytes() {
        long bytes = 0;
        for (File folder : dataFolders()) {
            bytes += sizeOf(folder, EVERYTHING);
        }
        return bytes;
    }

    /**
     * Deletes all the map data, its archives and its records, those of downloads not
     * finished included.
     *
     * @return the bytes freed
     */
    public long deleteAll() {
        long bytes = deleteArchives();
        for (File folder : dataFolders()) {
            bytes += delete(folder, EVERYTHING, false);
        }
        if (database != null) {
            database.cleanQueue();
            database.beginQueueBatch();
            try {
                for (String layer : layerNames()) {
                    for (Tile tile : database.getListOfDownloadedTiles(layer)) {
                        forget(layer, tile.tileX, tile.tileY, tile.zoomLevel);
                    }
                }
            } finally {
                database.endQueueBatch();
            }
        }
        return bytes;
    }

    private List<File> dataFolders() {
        List<File> folders = new ArrayList<>();
        folders.add(new File(root, PathUtils.getElevationFolder()));
        for (PbfLayer layer : PbfLayer.values()) {
            folders.add(new File(root, PathUtils.getMapFolder() + "/" + layer.name()));
        }
        return folders;
    }

    private static List<String> layerNames() {
        List<String> names = new ArrayList<>();
        names.add(MapSqlite.LAYER_ELEV);
        for (PbfLayer layer : PbfLayer.values()) {
            names.add(layer.name());
        }
        return names;
    }

    // ------------------------------------------------------------------ one block

    /** The block a place is in. */
    public static Tile blockAt(double latitude, double longitude) {
        return CheckMissingData.getTileAtZoomLevel(latitude, longitude, BLOCK_ZOOM);
    }

    /** Whether anything of a block is recorded as downloaded. */
    public boolean isDownloaded(Tile block) {
        if (database == null) {
            return false;
        }
        for (String layer : layerNames()) {
            for (Tile tile : database.getListOfDownloadedTiles(layer)) {
                if (isIn(block, tile)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The bytes deleting a block would free: what {@link #deleteBlock} would delete, counted
     * and left where it is.
     */
    public long blockBytes(Tile block) {
        return block(block, false);
    }

    /**
     * Deletes what was downloaded for one block: its data, its archives and its records.
     *
     * @return the bytes freed
     */
    public long deleteBlock(Tile block) {
        return block(block, true);
    }

    /** Counts what belongs to a block, and deletes it if asked: one walk for both, so that
     * what is promised is what is freed. */
    private long block(Tile block, boolean delete) {
        if (block.zoomLevel != BLOCK_ZOOM) {
            throw new IllegalArgumentException("a block is a tile of zoom " + BLOCK_ZOOM + ": " + block);
        }
        long bytes = 0;

        // The elevation: the block's archive, and the folders of its sixteen tiles.
        bytes += file(new File(root, DOWNLOADS + "/" + ElevationImageStorage.getElevTileTarGzPath(block)), delete);
        int side = 1 << (ELEVATION_ZOOM - BLOCK_ZOOM);
        for (int x = block.tileX * side; x < (block.tileX + 1) * side; x++) {
            for (int y = block.tileY * side; y < (block.tileY + 1) * side; y++) {
                File folder = new File(root, String.format(Locale.ENGLISH, "%s/zoom_%02d/x_%05d/y_%05d",
                        PathUtils.getElevationFolder(), ELEVATION_ZOOM, x, y));
                bytes += delete ? delete(folder, EVERYTHING, true) : sizeOf(folder, EVERYTHING);
            }
        }
        if (delete) {
            forget(MapSqlite.LAYER_ELEV, block.tileX, block.tileY, BLOCK_ZOOM);
        }

        for (PbfLayer layer : PbfLayer.values()) {
            if (layer.getArchiveZoom() < BLOCK_ZOOM) {
                continue;   // wider than a block: below
            }
            for (byte zoom = BLOCK_ZOOM; zoom <= FINEST_ZOOM; zoom++) {
                bytes += tilesOf(layer, block, zoom, delete);
            }
        }

        // The layers cut wider than a block, the area names: their tiles inside the block go,
        // their archive and its record only with the last of the blocks it covers.
        for (PbfLayer layer : PbfLayer.values()) {
            if (layer.getArchiveZoom() >= BLOCK_ZOOM) {
                continue;
            }
            for (byte zoom = BLOCK_ZOOM; zoom <= FINEST_ZOOM; zoom++) {
                bytes += tilesOf(layer, block, zoom, delete);
            }
            Tile wide = ancestor(block, layer.getArchiveZoom());
            if (database != null && !anyOtherBlockIn(wide, block)) {
                bytes += file(archiveOf(layer, wide), delete);
                bytes += file(PathUtils.getPbfFilePath(root, wide, layer), delete);
                if (delete) {
                    forget(layer.name(), wide.tileX, wide.tileY, wide.zoomLevel);
                }
            }
        }
        return bytes;
    }

    /** One layer's tiles of one zoom inside a block: the unpacked files, archives and records. */
    private long tilesOf(PbfLayer layer, Tile block, byte zoom, boolean delete) {
        long bytes = 0;
        int side = 1 << (zoom - BLOCK_ZOOM);
        for (int x = block.tileX * side; x < (block.tileX + 1) * side; x++) {
            for (int y = block.tileY * side; y < (block.tileY + 1) * side; y++) {
                Tile tile = new Tile(x, y, zoom, MapTile.TILE_SIZE);
                bytes += file(PathUtils.getPbfFilePath(root, tile, layer), delete);
                if (zoom == layer.getArchiveZoom()) {
                    bytes += file(archiveOf(layer, tile), delete);
                    if (delete) {
                        forget(layer.name(), x, y, zoom);
                    }
                }
            }
        }
        return bytes;
    }

    /** One file's bytes, the file deleted if asked. */
    private long file(File file, boolean delete) {
        if (delete) {
            return deleteFile(file);
        }
        return isOurs(file) && file.isFile() ? file.length() : 0;
    }

    private File archiveOf(PbfLayer layer, Tile tile) {
        List<String> parts = PathUtils.getDirsOfOsmPbfFile(
                PathUtils.getMapFolder() + "/" + layer.name(), tile, layer.getArchiveExtension(), layer.name());
        File file = new File(root, DOWNLOADS);
        for (String part : parts) {
            file = new File(file, part);
        }
        return file;
    }

    /** Whether a block other than this one, under a wider tile, has its elevation recorded. */
    private boolean anyOtherBlockIn(Tile wide, Tile block) {
        for (Tile tile : database.getListOfDownloadedTiles(MapSqlite.LAYER_ELEV)) {
            if (tile.zoomLevel == block.zoomLevel && tile.tileX == block.tileX && tile.tileY == block.tileY) {
                continue;
            }
            if (tile.zoomLevel >= wide.zoomLevel && isIn(wide, tile)) {
                return true;
            }
        }
        return false;
    }

    private void forget(String layer, int x, int y, int zoom) {
        if (database == null) {
            return;
        }
        MapSqlite.QueuedTile row = new MapSqlite.QueuedTile();
        row.tileX = x;
        row.tileY = y;
        row.tileZ = (byte) zoom;
        row.layer = layer;
        database.removeDownloadQueueMapData(row);
    }

    /** Whether a tile is a block, inside it, or wider than it and over it. */
    static boolean isIn(Tile block, Tile tile) {
        if (tile.zoomLevel >= block.zoomLevel) {
            int shift = tile.zoomLevel - block.zoomLevel;
            return (tile.tileX >> shift) == block.tileX && (tile.tileY >> shift) == block.tileY;
        }
        int shift = block.zoomLevel - tile.zoomLevel;
        return (block.tileX >> shift) == tile.tileX && (block.tileY >> shift) == tile.tileY;
    }

    private static Tile ancestor(Tile tile, byte zoom) {
        int shift = tile.zoomLevel - zoom;
        return new Tile(tile.tileX >> shift, tile.tileY >> shift, zoom, tile.tileSize);
    }

    // ------------------------------------------------------------------ files

    private interface Kind {
        boolean is(File file);
    }

    private static final Kind EVERYTHING = file -> true;

    /** An archive, or what is left of a download that was cut off. */
    private static final Kind ARCHIVES = file -> {
        String name = file.getName();
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.contains(".part-");
    };

    private long sizeOf(File file, Kind kind) {
        if (!isOurs(file)) {
            return 0;
        }
        if (file.isFile()) {
            return kind.is(file) ? file.length() : 0;
        }
        long bytes = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                bytes += sizeOf(child, kind);
            }
        }
        return bytes;
    }

    /**
     * Deletes the files of a kind under a folder, and the folders this leaves empty.
     *
     * @param itself whether the folder given goes too, if empty; the folders inside it always do
     */
    private long delete(File file, Kind kind, boolean itself) {
        if (!isOurs(file) || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return kind.is(file) ? deleteFile(file) : 0;
        }
        long bytes = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                bytes += delete(child, kind, true);
            }
        }
        if (itself) {
            file.delete();   // fails, as it should, while anything is left inside
        }
        return bytes;
    }

    private long deleteFile(File file) {
        if (!isOurs(file) || !file.isFile()) {
            return 0;
        }
        long bytes = file.length();
        return file.delete() ? bytes : 0;
    }

    /**
     * Whether a file is the app's to measure and delete: inside one of its two folders, by
     * the path it really has. A symbolic link anywhere on the way to it leads to what is
     * someone else's - a desktop's data folder may be a link into a shared collection - and
     * the path it really has is then not the one it was reached by.
     */
    private boolean isOurs(File file) {
        try {
            String reached = file.getAbsolutePath();
            if (!reached.equals(file.getCanonicalPath())) {
                return false;
            }
            return isUnder(reached, root) || isUnder(reached, imagery);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static boolean isUnder(String path, File folder) {
        String base = folder.getPath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    /**
     * After deleting: what the app holds in memory of the data is dropped, the terrain in view
     * is made again from what is left, and the map of what is downloaded is drawn again.
     */
    public static void tellTheApp() {
        com.peaknav.viewer.controller.MapController app = com.peaknav.utils.PeakNavUtils.getC();
        app.elevationImageProviderManager.clearProviders();
        app.areaRegistry.invalidateCache();
        app.mapDataManager.getMultiMapDataStore().resetCache();
        app.tileManager.updateMapTiles(true);
        com.peaknav.viewer.mapscreens.MapScreens.downloadedDataChanged();
    }

    // ------------------------------------------------------------------ captions

    /** A size as a reader takes it in: "850 MB", "1.2 GB". */
    public static String readable(long bytes) {
        double megabytes = bytes / (1024.0 * 1024.0);
        if (megabytes >= 1000) {
            return String.format(Locale.ROOT, "%.1f GB", megabytes / 1024.0);
        }
        if (megabytes >= 10 || megabytes == 0) {
            return String.format(Locale.ROOT, "%d MB", Math.round(megabytes));
        }
        return String.format(Locale.ROOT, "%.1f MB", megabytes);
    }
}
