package com.peaknav.network;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.getTileAtZoomLevel;
import static com.peaknav.utils.PathUtils.createRecurrentPathsForOsmTilesInExternal;
import static com.peaknav.utils.PathUtils.getMapFolder;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLoadFactory;
import static com.peaknav.utils.PeakNavUtils.getLogger;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.tiles.MapTile.TILE_SIZE;

import com.badlogic.gdx.Gdx;
import com.peaknav.compatibility.NotificationManagerPeakNav;
import com.peaknav.database.MapSqlite;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.utils.TarReader;
import com.peaknav.viewer.MapViewerSingleton;

import com.peaknav.geo.Tile;

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
            addQueuePistes(lat, lon);
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
                Tile queueTile = new Tile(tileX, tileY, zoomLevel, TILE_SIZE);
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
                Tile queueTile = new Tile(tileX, tileY, zoomLevel, TILE_SIZE);
                queue.add(queueTile);
            }
        }
        return queue;
    }

    /** How many archives a side each kind of data is fetched in, around the point. */
    private static final int SPAN_ELEVATION = 2, SPAN_POI = 3, SPAN_HIGHWAYS = 2;

    /**
     * The ground a download around this point covers, one box for each kind of data the place
     * screen shades as downloaded: elevation, points of interest, and the roads and pistes,
     * which share their archives' cut. They differ several times over - the roads cover a
     * sixth of the width of the points of interest - so no single box is what a download
     * fetches. Area labels are left out: they are not shaded either.
     */
    public List<com.peaknav.geo.BoundingBox> downloadBlocks(double lat, double lon) {
        List<com.peaknav.geo.BoundingBox> blocks = new ArrayList<>();
        addBlock(blocks, getQueueMapData(lat, lon, zoomPoiCompressed, SPAN_POI));
        addBlock(blocks, getQueueMapData(lat, lon, zoomElevationCompressed, SPAN_ELEVATION));
        addBlock(blocks, getQueueMapData(lat, lon, zoomHighwaysCompressed, SPAN_HIGHWAYS));
        return blocks;
    }

    private static void addBlock(List<com.peaknav.geo.BoundingBox> blocks, List<Tile> tiles) {
        // None at all where the block would fall off the map's edge, at the poles.
        if (!tiles.isEmpty()) {
            blocks.add(com.peaknav.database.MissingDataDownloader.getBoundingBoxOfTargetTiles(tiles));
        }
    }

    private void addQueueElevations(double lat, double lon) {
        List<Tile> queue = getQueueMapData(lat, lon, zoomElevationCompressed, SPAN_ELEVATION);
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
        addQueueMapData(lat, lon, zoomHighwaysCompressed, SPAN_HIGHWAYS, PbfLayer.PBF_HIGHWAYS);
    }

    /**
     * The ski pistes, cut like the highways: a 2x2 block of zoom-8 archives, from the same
     * dataset ({@code map_folder/PBF_PISTES}). Queued whether or not the ski slopes are shown:
     * the archives are small, and a region downloaded with them off still has its runs when
     * they are switched on.
     */
    private void addQueuePistes(double lat, double lon) {
        addQueueMapData(lat, lon, zoomHighwaysCompressed, SPAN_HIGHWAYS, PbfLayer.PBF_PISTES);
    }

    private void addQueuePois(double lat, double lon) {
        addQueueMapData(lat, lon, zoomPoiCompressed, SPAN_POI, PbfLayer.PBF_POI);
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

        if (cancelled()) {
            // An archive that was being unpacked when the download was stopped: its data is
            // kept, but the bar that was taken away is not brought back for it.
            return;
        }

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
     * Counts the times the user stopped the download. A run through the queue is given the
     * figure it was asked for under, and is stopped once it has moved on: a flag set on
     * stopping and cleared on starting would lose a stop that came between the two.
     */
    private final AtomicInteger cancelGeneration = new AtomicInteger(0);
    /** The figure the run now going was asked for under; one run at a time. */
    private volatile int runningGeneration = 0;
    /** The connections now open, to be dropped by a stop rather than left to time out. */
    private final java.util.Set<URLConnection> openConnections = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<URLConnection, Boolean>());

    /** The download was stopped by the user, not by anything that went wrong. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("download stopped by the user");
        }
    }

    public int cancelGeneration() {
        return cancelGeneration.get();
    }

    private boolean cancelled() {
        return cancelGeneration.get() != runningGeneration;
    }

    /**
     * Stops the run now going, and any asked for before this and still waiting for it: no
     * further archive is fetched, and the ones being fetched are dropped where they are. What
     * has arrived stays. Not to be called from a platform's UI thread: it closes connections.
     */
    public void cancel() {
        cancelGeneration.incrementAndGet();
        for (URLConnection conn : openConnections) {
            if (conn instanceof java.net.HttpURLConnection) {
                try {
                    ((java.net.HttpURLConnection) conn).disconnect();
                } catch (RuntimeException ignored) {
                    // The read loop sees the stop at its next block anyway.
                }
            }
        }
    }

    /**
     * Tries each configured provider's URL in turn until the tile downloads, so extra
     * providers act as mirrors: if HuggingFace is unreachable, the next one is tried.
     *
     * @throws IOException when no provider is configured or every one failed: a
     *         {@link java.io.FileNotFoundException} only when every one said it has no such
     *         file, and the archive is taken not to exist. One that could not be reached
     *         does not say that, whichever of them was tried last.
     */
    private void downloadFromProviders(List<String> candidateUrls, File localFile) throws IOException {
        if (candidateUrls == null || candidateUrls.isEmpty()) {
            throw new IOException("No download provider is configured");
        }
        IOException lastFailure = null;
        IOException notFound = null;
        for (String url : candidateUrls) {
            try {
                downloadWithRetries(url, localFile);
                return;
            } catch (IOException ex) {
                if (ex instanceof Cancelled) {
                    throw ex;   // not a provider failing: the next one is not tried
                }
                if (ex instanceof java.io.FileNotFoundException) {
                    notFound = ex;
                } else {
                    lastFailure = ex;
                }
                if (candidateUrls.size() > 1) {
                    getLogger().debug(TAG, "provider failed, trying next: " + url + " -> " + ex);
                }
            }
        }
        throw lastFailure != null ? lastFailure : notFound;
    }

    /**
     * The length the server announced, or -1 when it announced none or an unreadable one. Read
     * from the header rather than with URLConnection.getContentLengthLong(), a Java 7 method
     * RoboVM's runtime lacks: on iOS it throws NoSuchMethodError, which no IOException handler
     * catches, on every download.
     */
    private static long contentLength(URLConnection conn) {
        String header = conn.getHeaderField("Content-Length");
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Downloads {@code urlString} to {@code localFile}, retrying transient failures.
     *
     * @throws IOException when every attempt failed; the caller then drops the tile as before.
     */
    private void downloadWithRetries(String urlString, File localFile) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            if (cancelled()) {
                // Also what a connection dropped by cancel() comes to: its "socket closed"
                // would otherwise be taken for a network error and tried again.
                throw new Cancelled();
            }
            URLConnection conn = null;
            try {
                URL url = new URL(urlString);
                conn = url.openConnection();
                openConnections.add(conn);
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
                long expected = contentLength(conn);
                long received = 0;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(partial)) {
                    byte[] readBuf = new byte[8192];
                    int readLen;
                    while ((readLen = in.read(readBuf)) > 0) {
                        if (cancelled()) {
                            throw new Cancelled();
                        }
                        fos.write(readBuf, 0, readLen);
                        received += readLen;
                    }
                } catch (IOException e) {
                    partial.delete();
                    throw e;
                }
                // Nothing, or less than the server announced, is no archive: an empty body
                // (a proxy, a mirror answering 200 with nothing) was renamed into place and,
                // for the .tar layers, unpacked as an archive with no entries - the tile
                // stamped downloaded with nothing on disk.
                if (received == 0 || (expected >= 0 && received != expected)) {
                    partial.delete();
                    throw new IOException("incomplete download of " + urlString + ": "
                            + received + " of " + (expected >= 0 ? expected : "?") + " bytes");
                }
                getLoadFactory().getFileMover().moveIntoPlace(partial, localFile);
                return;
            } catch (IOException ex) {
                if (ex instanceof Cancelled) {
                    throw ex;
                }
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
            } finally {
                if (conn != null) {
                    openConnections.remove(conn);
                }
            }
        }
        throw lastFailure;
    }

    /**
     * What a run through the queue came to. Until this was returned, a run in which every
     * archive failed ended as one that fetched them all: on a first run with no connection the
     * welcome screen said "Download complete!" and opened a map with nothing on it.
     */
    public static final class Outcome {
        /** Archives the queue held. */
        public final int wanted;
        /** Of those, the ones that could not be fetched or unpacked. Not the ones the server
         * does not have: a region without area labels has no archive for them. */
        public final int failed;
        /** The user stopped it: what was not fetched by then is counted as failed, but nothing
         * went wrong and nothing is to be said of it. */
        public final boolean cancelled;

        Outcome(int wanted, int failed) {
            this(wanted, failed, false);
        }

        Outcome(int wanted, int failed, boolean cancelled) {
            this.wanted = wanted;
            this.failed = failed;
            this.cancelled = cancelled;
        }

        /** A download stopped before it began: it was waiting for the one the user stopped. */
        public static Outcome cancelledBeforeStart() {
            return new Outcome(0, 0, true);
        }

        /** Every archive failed: nothing on the device has changed. */
        public boolean nothingFetched() {
            return wanted > 0 && failed >= wanted;
        }
    }

    /** The host was not found or did not answer: no connection, as far as one archive can tell. */
    private static boolean isUnreachable(IOException failure) {
        return failure instanceof java.net.UnknownHostException
                || failure instanceof java.net.ConnectException
                || failure instanceof java.net.NoRouteToHostException;
    }

    /**
     * @param generation {@link #cancelGeneration()} as it was when this run was asked for
     */
    public Outcome processQueue(int generation) {
        runningGeneration = generation;

        List<MapSqlite.QueuedTile> queuedTiles = new ArrayList<>();
        for (MapSqlite.QueuedTile queued : mapSqlite.getDownloadQueue()) {
            // A layer this version does not know - a row written by a newer one, or a branch -
            // has no archive it could fetch. Kept, it threw on every download before a single
            // tile was fetched, the start-up resume included, and stayed in the queue for good.
            if (!MapSqlite.LAYER_ELEV.equals(queued.layer) && queued.pbfLayer == null) {
                mapSqlite.removeDownloadQueueMapData(queued);
                continue;
            }
            queuedTiles.add(queued);
        }

        Timestamp now = new Timestamp(Calendar.getInstance().getTimeInMillis());

        List<PeakNavHttpCompressDownloader.DownloadTarget> targets = eleDown.getDownloadTargets(queuedTiles);

        // The archives fetched so far, which is what the progress shows: counting the ones that
        // failed too, a download with no connection ran up to 100% having fetched nothing.
        final AtomicInteger counterMapData = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        // Set by the first archive that finds no connection: the ones after it are not tried,
        // each of which would take its attempts and the waits between them to say the same.
        final java.util.concurrent.atomic.AtomicBoolean unreachable =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        List<Future<?>> futures = new LinkedList<>();
        int downloadSize = targets.size();

        for (PeakNavHttpCompressDownloader.DownloadTarget target : targets) {
            Future<?> e = downloadExecutor.submit(
                    () -> {
                        boolean ok = false;
                        // Not fetched for want of a connection: the row stays in the queue, to
                        // be taken up at the next start or the next download. It used to be
                        // dropped like any failure, and the queue forgot what it still owed.
                        boolean pending = false;
                        boolean okDownload = false;
                        File localFile = null;
                        try {
                            if (cancelled()) {
                                // Left to the clean-up at the end, which drops every row
                                // not fetched.
                                pending = true;
                                failed.incrementAndGet();
                                return;
                            }
                            if (!P.isCollectDownloadInfo()) {
                                // Respect the missing download consent, but never silently:
                                // this skip used to be invisible, so a download without the
                                // consent queued everything, showed progress and fetched
                                // nothing - indistinguishable from a network failure.
                                System.err.println("[Download] skipped " + target.objectKey
                                        + ": download consent not granted"
                                        + " (see Missing_download_info_consent)");
                                // Left pending, and counted as not fetched: it was counted as
                                // fetched, and its tile stamped downloaded with nothing on disk.
                                pending = true;
                                failed.incrementAndGet();
                                return;
                            }

                            localFile = Gdx.files.external("peaknav_downloads/" + target.objectKey).file();

                            if (!localFile.exists()) {
                                if (unreachable.get()) {
                                    pending = true;
                                    failed.incrementAndGet();
                                    return;
                                }
                                List<String> dirs = Arrays.asList(target.objectKey.split("/"));
                                dirs = dirs.subList(0, dirs.size() - 1);
                                createRecurrentPathsForOsmTilesInExternal(dirs);

                                File localFileDir = localFile.getParentFile();
                                if (!localFileDir.exists()) {
                                    localFile.getParentFile().mkdirs();
                                }

                                try {
                                    downloadFromProviders(target.candidateUrls, localFile);
                                } catch (java.io.FileNotFoundException notOnServer) {
                                    // Not a failure: there is no such archive - a tile of sea,
                                    // or of land with nothing of this layer on it. Recorded as
                                    // downloaded, with nothing to unpack: dropped unrecorded,
                                    // as it was, the place still looked never downloaded, and
                                    // the banner offered it again on every arrival.
                                    ok = true;
                                    return;
                                } catch (IOException notFetched) {
                                    pending = true;
                                    failed.incrementAndGet();
                                    if (isUnreachable(notFetched)) {
                                        unreachable.set(true);
                                    }
                                    getLogger().debug(TAG, "not fetched, left in the queue: "
                                            + target.objectKey + ": " + notFetched);
                                    return;
                                }
                            }

                            okDownload = true;

                            try {
                                unpackTarGz(localFile, unpackRootFor(target.queuedTile));
                            } catch (WriteFailed cannotWrite) {
                                // The device, not the archive: a full disk or a folder that
                                // cannot be written. Taken for a corrupt archive, the good one
                                // was deleted, fetched again whole, failed again, and its row
                                // dropped. Both are kept, and the tile left pending.
                                pending = true;
                                failed.incrementAndGet();
                                getLogger().debug(TAG, "cannot write what " + target.objectKey
                                        + " holds; left in the queue: " + cannotWrite);
                                return;
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
                        } catch (IOException | RuntimeException ex) {
                            // Counted, and said by the caller; thrown from here it went into
                            // the Future, which nothing read but to print it.
                            failed.incrementAndGet();
                            getLogger().debug(TAG, "failed: " + target.objectKey + ": " + ex);
                        } finally {
                            if (ok) {
                                mapSqlite.updateDownloadQueueMapDataTimestamp(target.queuedTile, now);
                                updateProgressText(counterMapData.incrementAndGet(), downloadSize);
                            } else if (!pending) {
                                if (localFile != null && localFile.exists()) {
                                    localFile.delete();
                                }
                                mapSqlite.removeDownloadQueueMapData(target.queuedTile);
                            }
                        }
                    }
            );
            futures.add(e);
        }

        // Every archive is waited for, as before; an interrupt meanwhile is kept for whoever
        // asked for it, which the swallowed exception lost.
        boolean interrupted = false;
        for (Future<?> e : futures) {
            while (true) {
                try {
                    e.get();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                } catch (ExecutionException ex) {
                    ex.printStackTrace();
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        boolean cancelled = cancelled();
        if (cancelled) {
            // What was not fetched is no longer wanted: left queued, it would be fetched by the
            // next download, or taken up at the next start, as one the app was closed during.
            mapSqlite.cleanQueue();
        }
        Outcome outcome = new Outcome(downloadSize, failed.get(), cancelled);
        // Finished, whatever the queue held. With nothing left to fetch - every tile already
        // downloaded - no worker ever reported progress, so the bar the download showed stayed
        // on screen at 0 for good, and anything waiting for it to fill waited forever. Full only
        // if something did arrive: one that fetched nothing ends at 0, not at a 100% that was
        // still what the next download showed until its first archive came in.
        getAppState().endMapDataDownloadProgress(!cancelled && !outcome.nothingFetched());

        NotificationManagerPeakNav notificationManager = getC().getMapViewerScreen().mapApp.loadFactory.getPeakNavNotificationManager();
        if (notificationManager != null) {
            notificationManager.clear();
        }
        return outcome;
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

    /** Unpacking failed on the writing side - the device's, not the archive's. */
    static final class WriteFailed extends IOException {
        WriteFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static void unpackTarGz(File inputFile, File outputDir) throws IOException {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // The reader (and through it the gzip and file streams) is closed on every path.
        // Bulk downloads unpack hundreds of archives; an unclosed descriptor per archive was
        // survivable where finalizers ran promptly, and is not on iOS's fd limit.
        try (TarReader tarInput = openTar(inputFile)) {
            unpackEntries(tarInput, outputDir);
        }
    }

    private static TarReader openTar(File inputFile) throws IOException {
        FileInputStream fis = new FileInputStream(inputFile);
        try {
            if (inputFile.getName().endsWith(".tar.gz")) {
                // java.util.zip, not commons-compress: this runs on iOS too, where the
                // library cannot be loaded at all. See TarReader for the whole story.
                return new TarReader(new java.util.zip.GZIPInputStream(fis, 32 * 1024));
            }
            if (inputFile.getName().endsWith(".tar")) {
                return new TarReader(fis);
            }
            throw new IOException("unknown tar/tar.gz extension: " + inputFile.getName());
        } catch (IOException | RuntimeException e) {
            // Nothing owns fis yet (a corrupt gzip header throws from the constructor).
            fis.close();
            throw e;
        }
    }

    /**
     * Where an archive's entry is written: inside {@code outputDir}, or nowhere. An entry's
     * name comes from whoever served the archive - a provider the user added, or a mirror
     * over plain http - and was joined to the folder as it came: "../" or an absolute name
     * wrote wherever the app can write. Such an archive is refused whole, as a corrupt one is.
     *
     * <p>The name is judged by itself, step by step, not by where the path finally resolves
     * to. An archive cannot make a link here - TarReader skips every entry that is neither a
     * file nor a folder - so a link on the way is one the user made, a data folder moved to
     * another disk, and is followed as before.
     */
    static File entryTarget(File outputDir, String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IOException("archive entry with no usable name");
        }
        String relative = name.replace('\\', '/');
        if (relative.startsWith("/") || (relative.length() > 1 && relative.charAt(1) == ':')) {
            throw new IOException("archive entry with an absolute name: " + name);
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (String part : relative.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    throw new IOException("archive entry outside the data folder: " + name);
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            throw new IOException("archive entry naming the data folder itself: " + name);
        }
        File target = outputDir;
        for (String part : parts) {
            target = new File(target, part);
        }
        return target;
    }

    private static void unpackEntries(TarReader tarInput, File outputDir) throws IOException {
        TarReader.Entry entry;
        int entries = 0;
        while ((entry = tarInput.next()) != null) {
            entries++;
            File outputFile = entryTarget(outputDir, entry.getName());

            if (entry.isDirectory()) {
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw new WriteFailed("Failed to create directory " + outputFile, null);
                }
            } else {
                File parent = outputFile.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) {
                    throw new WriteFailed("Failed to create directory " + parent, null);
                }

                // Unpacked the same way tiles are downloaded: to a private name, renamed
                // into place. Two processes can be unpacking the same archive - both were
                // told the region was missing before either finished - and a tile file that
                // one is reading while the other writes it directly would be part-written.
                File partialEntry = new File(outputFile.getPath()
                        + ".part-" + java.util.UUID.randomUUID());
                boolean success = false;
                FileOutputStream fos = null;
                try {
                    try {
                        fos = new FileOutputStream(partialEntry);
                    } catch (IOException cannotCreate) {
                        throw new WriteFailed("Cannot create " + partialEntry, cannotCreate);
                    }
                    byte[] buffer = new byte[8192];
                    int len;
                    // Reading is the archive's side - a failure there is a corrupt archive;
                    // writing is the device's (see WriteFailed).
                    while ((len = tarInput.read(buffer, 0, buffer.length)) != -1) {
                        try {
                            fos.write(buffer, 0, len);
                        } catch (IOException cannotWrite) {
                            throw new WriteFailed("Cannot write " + partialEntry, cannotWrite);
                        }
                    }
                    try {
                        fos.close();
                        fos = null;
                    } catch (IOException cannotWrite) {
                        throw new WriteFailed("Cannot write " + partialEntry, cannotWrite);
                    }
                    success = true;
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException ignored) {
                            // already failing
                        }
                    }
                    if (!success) {
                        partialEntry.delete();
                    }
                }
                try {
                    getLoadFactory().getFileMover().moveIntoPlace(partialEntry, outputFile);
                } catch (IOException cannotMove) {
                    partialEntry.delete();
                    throw new WriteFailed("Cannot move " + partialEntry + " into place", cannotMove);
                }
            }
        }
        if (entries == 0) {
            // An archive with nothing in it is no archive of ours: every one carries at least
            // its tile. Refused as a corrupt one is - fetched again once, then counted failed.
            throw new IOException("empty archive");
        }
    }

    public byte getZoomHighways() {
        return zoomHighways;
    }

    public int getRangeHighways() {
        return rangeHighways;
    }

}
