package com.peaknav.network;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.getTileAtZoomLevel;
import static com.peaknav.utils.PathUtils.createRecurrentPathsForOsmTilesInExternal;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLogger;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import com.badlogic.gdx.Gdx;
import com.peaknav.compatibility.NotificationManagerPeakNav;
import com.peaknav.database.MapSqlite;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.viewer.MapViewerSingleton;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.mapsforge.core.model.Tile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class PeakNavDownloadManager {

    private final ExecutorService downloadExecutor = new PeakNavThreadExecutor(2, "down-exec");
    private MapSqlite mapSqlite;
    private final PeakNavHttpCompressDownloader eleDown;

    public byte getZoomPoi() {
        return zoomPoi;
    }

    public int getRangePoi() {
        return rangePoi;
    }

    private final byte zoomPoi;
    private final byte zoomPoiCompressed = 6;
    private final int rangePoi;
    private final byte zoomHighways;
    private final byte zoomHighwaysCompressed = 8;
    private final byte zoomElevationCompressed = 6;
    private final int rangeHighways;
    private final String TAG = "PeakNavDownloadManager";

    public PeakNavDownloadManager(MapSqlite mapSqlite, PeakNavHttpCompressDownloader eleDown, byte zoomPoi, int rangePoi, byte zoomHighways, int rangeHighways) {
        this.mapSqlite = mapSqlite;
        this.eleDown = eleDown;
        this.zoomPoi = zoomPoi;
        this.rangePoi = rangePoi;
        this.zoomHighways = zoomHighways;
        this.rangeHighways = rangeHighways;
    }

    public void addDataToQueue(double lat, double lon) {
        addQueueElevations(lat, lon);
        addQueueHighways(lat, lon);
        addQueuePois(lat, lon);
    }

    public List<Tile> getQueueTilesEven(double lat, double lon, byte zoomLevel, int tileSpan) {
        int range = tileSpan / 2;
        List<Tile> queue = new ArrayList<>(tileSpan*tileSpan);
        Tile baseTile = getTileAtZoomLevel(lat, lon, zoomLevel);
        Tile smallerTile = getTileAtZoomLevel(lat, lon, (byte) (zoomLevel+1));
        int startX = baseTile.tileX - range + ((smallerTile.tileX % 2 == 0)? 0 : 1);
        int startY = baseTile.tileY - range + ((smallerTile.tileY % 2 == 0)? 0 : 1);

        int maxTileVal = 1 << zoomLevel;
        for (int tileX = startX; tileX < startX + tileSpan; tileX++) {
            for (int tileY = startY; tileY < startY + tileSpan; tileY++) {
                // TODO: deal with +- 180 degrees longitude correctly:
                if (tileX < 0 || tileY < 0 || tileX >= maxTileVal || tileY >= maxTileVal) {
                    continue;
                }
                Tile queueTile = new Tile(tileX, tileY, zoomLevel, MF_ZOOM);
                queue.add(queueTile);
            }
        }
        return queue;
    }

    public List<Tile> getQueueTilesOdd(double lat, double lon, byte zoomLevel, int tileSpan) {
        final int range = (tileSpan - 1) / 2;
        List<Tile> queue = new ArrayList<>(tileSpan * tileSpan);
        Tile baseTile = getTileAtZoomLevel(lat, lon, zoomLevel);

        int maxTileVal = 1 << zoomLevel;
        for (int tileX = baseTile.tileX - range; tileX <= baseTile.tileX + range; tileX++) {
            for (int tileY = baseTile.tileY - range; tileY <= baseTile.tileY + range; tileY++) {
                // TODO: deal with +- 180 degrees longitude correctly:
                if (tileX < 0 || tileY < 0 || tileX >= maxTileVal || tileY >= maxTileVal) {
                    continue;
                }
                Tile queueTile = new Tile(tileX, tileY, zoomLevel, MF_ZOOM);
                queue.add(queueTile);
            }
        }
        return queue;
    }

    private void addQueueElevations(double lat, double lon) {
        List<Tile> queue = getQueueMapData(lat, lon, zoomElevationCompressed, 2);
        for (Tile queueTile : queue) {
            // TODO: insert only if not exists? ==> RIGHT!
            mapSqlite.addToDownloadQueueElevationTile(queueTile);
        }
    }

    public List<Tile> getQueueMapData(double lat, double lon, byte zoom, int tileSpan) {
        if (tileSpan % 2 == 0) {
            return getQueueTilesEven(lat, lon, zoom, tileSpan);
        } else {
            return getQueueTilesOdd(lat, lon, zoom, tileSpan);
        }
    }

    private void addQueueMapData(double lat, double lon, byte zoom, int tileSpan, PbfLayer pbfLayer) {
        // TODO: do not add if already downloaded or too recently updated
        List<Tile> queue = getQueueMapData(lat, lon, zoom, tileSpan);
        for (Tile tile : queue) {
            int tX = tile.tileX;
            int tY = tile.tileY;
            getLogger().debug(TAG, "Adding queue " + pbfLayer + " " + tX + ", " + tY + " at zoomLevel " + zoom);
            mapSqlite.addToDownloadQueueMapData(tX, tY, zoom, pbfLayer);
            getLogger().debug(TAG, "Added queue " + pbfLayer + " into SQLite " + tX + ", " + tY + " at zoomLevel " + zoom);
        }
    }

    private void addQueueHighways(double lat, double lon) {
        addQueueMapData(lat, lon, zoomHighwaysCompressed, 2, PbfLayer.PBF_HIGHWAYS);
    }

    private void addQueuePois(double lat, double lon) {
        addQueueMapData(lat, lon, zoomPoiCompressed, 3, PbfLayer.PBF_POI);
    }

    private synchronized void updateProgressText(final int counterMapData, int downloadSize) {
        StringBuilder builder = new StringBuilder();

        if (downloadSize == 0) {
            return;
        }

        builder.append("Downloaded map data " + counterMapData + " / " + downloadSize);

        float progress = 1.f * counterMapData / downloadSize;
        getAppState().setMapDataDownloadProgressRatio(progress);
        // notificationManager.setText(builder.toString(), progress);
    }

    public void processQueue() {

        List<MapSqlite.QueuedTile> queuedTiles = mapSqlite.getDownloadQueue();

        Timestamp now = new Timestamp(Calendar.getInstance().getTimeInMillis());

        List<PeakNavHttpCompressDownloader.HfDownloadUrl> urls = eleDown.getHfDownloadUrlList(queuedTiles);

        final AtomicInteger counterMapData = new AtomicInteger(0);

        List<Future<?>> futures = new LinkedList<>();
        int downloadSize = urls.size();

        for (PeakNavHttpCompressDownloader.HfDownloadUrl hfDownloadUrl : urls) {
            Future<?> e = downloadExecutor.submit(
                    () -> {
                        boolean ok = false;
                        boolean okDownload = false;
                        File localFile = null;
                        try {
                            if (!P.isCollectDownloadInfo()) {
                                ok = true;
                                return;
                            }

                            localFile = Gdx.files.external("peaknav_downloads/" + hfDownloadUrl.objectKey).file();

                            if (!localFile.exists()) {
                                List<String> dirs = Arrays.asList(hfDownloadUrl.objectKey.split("/"));
                                dirs = dirs.subList(0, dirs.size() - 1);
                                createRecurrentPathsForOsmTilesInExternal(dirs);

                                URL url = new URL(hfDownloadUrl.getUrl());
                                URLConnection conn = url.openConnection();

                                InputStream s3is = conn.getInputStream();
                                File localFileDir = localFile.getParentFile();
                                if (!localFileDir.exists()) {
                                    localFile.getParentFile().mkdirs();
                                }
                                FileOutputStream fos = new FileOutputStream(localFile);

                                byte[] read_buf = new byte[1024];
                                int read_len = 0;
                                while ((read_len = s3is.read(read_buf)) > 0) {
                                    fos.write(read_buf, 0, read_len);
                                }

                                //
                                s3is.close();
                                fos.close();
                            }

                            okDownload = true;

                            unpackTarGz(localFile, Gdx.files.external(".").file());

                            ok = true;
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        } finally {
                            if (ok) {
                                mapSqlite.updateDownloadQueueMapDataTimestamp(hfDownloadUrl.queuedTile, now);
                            } else {
                                if (localFile != null && localFile.exists()) {
                                    localFile.delete();
                                }
                                mapSqlite.removeDownloadQueueMapData(hfDownloadUrl.queuedTile);
                            }
                            updateProgressText(counterMapData.incrementAndGet(), downloadSize);
                        }
                    }
            );
            futures.add(e);
        }

        for (Future<?> e : futures) {
            try {
                e.get();
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
            }
        }

        NotificationManagerPeakNav notificationManager = getC().getMapViewerScreen().mapApp.loadFactory.getPeakNavNotificationManager();
        if (notificationManager != null) {
            notificationManager.clear();
        }
    }

    public static void unpackTarGz(File inputFile, File outputDir) throws IOException {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        FileInputStream fis = new FileInputStream(inputFile);

        InputStream is;
        if (inputFile.getName().endsWith(".tar.gz")) {
            GzipCompressorInputStream gis = new GzipCompressorInputStream(fis);
            is = gis;
        } else if (inputFile.getName().endsWith(".tar")) {
            is = fis;
        } else {
            throw new RuntimeException("unknown tar/tar.gz extension: " + inputFile.getName());
        }

        TarArchiveInputStream tarInput = new TarArchiveInputStream(is);

        ArchiveEntry entry;
        while ((entry = tarInput.getNextEntry()) != null) {
            File outputFile = new File(outputDir, entry.getName());

            if (entry.isDirectory()) {
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + outputFile);
                }
            } else {
                File parent = outputFile.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                boolean success = false;
                try (
                    FileOutputStream fos = new FileOutputStream(outputFile)
                ) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = tarInput.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    success = true;
                } finally {
                    if (!success) {
                        outputFile.delete();
                    }
                }
            }
        }
    }

    public byte getZoomHighways() {
        return zoomHighways;
    }

    public int getRangeHighways() {
        return rangeHighways;
    }

}
