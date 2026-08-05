package com.peaknav.network;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.getTileAtZoomLevel;
import static com.peaknav.utils.PathUtils.createRecurrentPathsForOsmTilesInExternal;
import static com.peaknav.utils.PathUtils.getMapFolder;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLogger;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import com.badlogic.gdx.Gdx;
import com.peaknav.compatibility.NotificationManagerPeakNav;
import com.peaknav.database.MapSqlite;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.utils.AtomicFileMove;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.utils.TarReader;
import com.peaknav.viewer.MapViewerSingleton;

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
        // Same order the queue is served in (see sqlQueryDownloadQueue): terrain first,
        // then the labels and paths on it, and AREAS - which not every region has - last.
        // One transaction around the lot: measured at ~2.8 s per tile when every insert
        // auto-committed, i.e. most of a minute before the first download could begin.
        mapSqlite.beginQueueBatch();
        try {
            addQueueElevations(lat, lon);
            addQueuePois(lat, lon);
            addQueueHighways(lat, lon);
            addQueueAreas(lat, lon);
        } finally {
            mapSqlite.endQueueBatch();
        }
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

    /**
     * Area labels, queued alongside the POI and highway extracts so a region arrives complete.
     *
     * <p>Their archives are cut one zoom coarser than the POI ones, so each covers four times the
     * ground and the span has to shrink to match: a 2×2 block at zoom 5 spans 22.5° of longitude
     * against the 16.9° a 3×3 block spanned at zoom 6, in four requests rather than nine. An even
     * span is deliberate — it takes the 2×2 nearest the position, so the neighbouring block is
     * always included and labels do not appear only once a boundary is crossed.
     */
    private void addQueueAreas(double lat, double lon) {
        addQueueMapData(lat, lon, PbfLayer.ZOOM_LEVEL_AREAS_ARCHIVE, 2, PbfLayer.AREAS);
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

    /**
     * A failed tile is dropped from the download queue and never fetched again, which leaves that
     * area permanently without data (and the app repeatedly offering to download it). Network
     * errors are often momentary, so retry a few times before giving up on a tile.
     */
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_BASE_MILLIS = 700L;
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 20_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MILLIS = 60_000;

    /**
     * Tries each configured provider's URL in turn until the tile downloads, so extra
     * providers act as mirrors: if HuggingFace is unreachable, the next one is tried.
     *
     * @throws IOException when no provider is configured or every one failed.
     */
    private void downloadFromProviders(List<String> candidateUrls, File localFile) throws IOException {
        if (candidateUrls == null || candidateUrls.isEmpty()) {
            throw new IOException("No download provider is configured");
        }
        IOException lastFailure = null;
        for (String url : candidateUrls) {
            try {
                downloadWithRetries(url, localFile);
                return;
            } catch (IOException ex) {
                lastFailure = ex;
                if (candidateUrls.size() > 1) {
                    getLogger().debug(TAG, "provider failed, trying next: " + url + " -> " + ex);
                }
            }
        }
        throw lastFailure;
    }

    /**
     * Downloads {@code urlString} to {@code localFile}, retrying transient failures.
     *
     * @throws IOException when every attempt failed; the caller then drops the tile as before.
     */
    private void downloadWithRetries(String urlString, File localFile) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                URL url = new URL(urlString);
                URLConnection conn = url.openConnection();
                conn.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MILLIS);
                conn.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MILLIS);

                // Streamed to a name only this process uses, then renamed into place. The
                // rename is atomic on the same filesystem, so any other process - another
                // renderer downloading the same region, or the app reading while a renderer
                // works - sees the file either absent or complete, never part-written. Two
                // processes fetching the same tile both finish; whichever renames last wins,
                // and both leave a whole file.
                File partial = new File(localFile.getPath()
                        + ".part-" + java.util.UUID.randomUUID());
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(partial)) {
                    byte[] readBuf = new byte[8192];
                    int readLen;
                    while ((readLen = in.read(readBuf)) > 0) {
                        fos.write(readBuf, 0, readLen);
                    }
                } catch (IOException e) {
                    partial.delete();
                    throw e;
                }
                AtomicFileMove.moveIntoPlace(partial, localFile);
                return;
            } catch (IOException ex) {
                lastFailure = ex;
                // No half-written archive to clean up: the stream went to the .part file,
                // which its own catch already removed, and nothing lands on the final name
                // except by the atomic rename. Deleting localFile here - as this used to -
                // would now be worse than needless: with several processes downloading, the
                // file at that name may be another process's completed archive.
                // 404: the file is not on the server, and it will not be there on the next
                // attempt either. Retrying with backoff here is what made a download over a
                // region without AREAS archives crawl - every absent tile burned all the
                // attempts plus the sleeps between them, on both download workers.
                if (ex instanceof java.io.FileNotFoundException) {
                    getLogger().debug(TAG, "not on server (no retry): " + urlString);
                    throw ex;
                }
                getLogger().debug(TAG, "download attempt " + attempt + "/" + DOWNLOAD_ATTEMPTS
                        + " failed for " + urlString + ": " + ex);
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(DOWNLOAD_RETRY_BASE_MILLIS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw lastFailure;
                    }
                }
            }
        }
        throw lastFailure;
    }

    public void processQueue() {

        List<MapSqlite.QueuedTile> queuedTiles = mapSqlite.getDownloadQueue();

        Timestamp now = new Timestamp(Calendar.getInstance().getTimeInMillis());

        List<PeakNavHttpCompressDownloader.DownloadTarget> targets = eleDown.getDownloadTargets(queuedTiles);

        final AtomicInteger counterMapData = new AtomicInteger(0);

        List<Future<?>> futures = new LinkedList<>();
        int downloadSize = targets.size();

        for (PeakNavHttpCompressDownloader.DownloadTarget target : targets) {
            Future<?> e = downloadExecutor.submit(
                    () -> {
                        boolean ok = false;
                        boolean okDownload = false;
                        File localFile = null;
                        try {
                            if (!P.isCollectDownloadInfo()) {
                                // Respect the missing download consent, but never silently:
                                // this skip used to be invisible, so a download without the
                                // consent queued everything, showed progress and fetched
                                // nothing - indistinguishable from a network failure.
                                System.err.println("[Download] skipped " + target.objectKey
                                        + ": download consent not granted"
                                        + " (see Missing_download_info_consent)");
                                ok = true;
                                return;
                            }

                            localFile = Gdx.files.external("peaknav_downloads/" + target.objectKey).file();

                            if (!localFile.exists()) {
                                List<String> dirs = Arrays.asList(target.objectKey.split("/"));
                                dirs = dirs.subList(0, dirs.size() - 1);
                                createRecurrentPathsForOsmTilesInExternal(dirs);

                                File localFileDir = localFile.getParentFile();
                                if (!localFileDir.exists()) {
                                    localFile.getParentFile().mkdirs();
                                }

                                downloadFromProviders(target.candidateUrls, localFile);
                            }

                            okDownload = true;

                            try {
                                unpackTarGz(localFile, unpackRootFor(target.queuedTile));
                            } catch (IOException | RuntimeException corrupt) {
                                // The archive on disk is not to be trusted just because it
                                // exists: a truncated or stale file (crashes and the
                                // pre-atomic-write era both produced them) fails to unpack
                                // here for ever, and the old handling then dropped the
                                // queue row - leaving the tile neither downloaded nor
                                // pending, unhealable by asking again. Discard the file
                                // and fetch it fresh, once; only a second failure counts.
                                getLogger().debug(TAG, "unpack failed for " + target.objectKey
                                        + "; refetching once: " + corrupt);
                                localFile.delete();
                                downloadFromProviders(target.candidateUrls, localFile);
                                unpackTarGz(localFile, unpackRootFor(target.queuedTile));
                            }

                            ok = true;
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        } finally {
                            if (ok) {
                                mapSqlite.updateDownloadQueueMapDataTimestamp(target.queuedTile, now);
                            } else {
                                if (localFile != null && localFile.exists()) {
                                    localFile.delete();
                                }
                                mapSqlite.removeDownloadQueueMapData(target.queuedTile);
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

    /**
     * Where an archive's contents are unpacked.
     *
     * <p>Every archive carries its full path inside it ({@code map_folder/…}, {@code elev_tiles/…}),
     * so they all unpack at the external root — the areas included, which is why there is no
     * per-layer case here to get wrong.
     */
    private static File unpackRootFor(MapSqlite.QueuedTile queuedTile) {
        return Gdx.files.external(".").file();
    }

    public static void unpackTarGz(File inputFile, File outputDir) throws IOException {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        FileInputStream fis = new FileInputStream(inputFile);

        InputStream is;
        if (inputFile.getName().endsWith(".tar.gz")) {
            // java.util.zip, not commons-compress: this runs on iOS too, where the library
            // cannot be loaded at all. See TarReader for the whole story.
            is = new java.util.zip.GZIPInputStream(fis, 32 * 1024);
        } else if (inputFile.getName().endsWith(".tar")) {
            is = fis;
        } else {
            fis.close();
            throw new RuntimeException("unknown tar/tar.gz extension: " + inputFile.getName());
        }

        TarReader tarInput = new TarReader(is);

        TarReader.Entry entry;
        while ((entry = tarInput.next()) != null) {
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

                // Unpacked the same way tiles are downloaded: to a private name, renamed
                // into place. Two processes can be unpacking the same archive - both were
                // told the region was missing before either finished - and a tile file that
                // one is reading while the other writes it directly would be part-written.
                File partialEntry = new File(outputFile.getPath()
                        + ".part-" + java.util.UUID.randomUUID());
                boolean success = false;
                try (
                    FileOutputStream fos = new FileOutputStream(partialEntry)
                ) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = tarInput.read(buffer, 0, buffer.length)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    success = true;
                } finally {
                    if (!success) {
                        partialEntry.delete();
                    }
                }
                AtomicFileMove.moveIntoPlace(partialEntry, outputFile);
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
