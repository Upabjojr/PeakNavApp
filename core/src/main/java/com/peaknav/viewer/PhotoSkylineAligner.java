package com.peaknav.viewer;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLogger;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.database.CheckMissingData;
import com.peaknav.gesture.MountainInputController;
import com.peaknav.skyline.ElevationSampler;
import com.peaknav.skyline.SkylineExtractor;
import com.peaknav.skyline.SkylineMatcher;
import com.peaknav.skyline.TerrainHorizon;
import com.peaknav.utils.ExifReader;
import com.peaknav.utils.Units;
import com.peaknav.viewer.screens.MapViewerScreen;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.Tile;

import java.nio.ByteBuffer;

/**
 * Offers to point the camera the way a photograph was taken.
 *
 * <p>When a picture is loaded behind the terrain and the app knows where it was taken -
 * from its EXIF coordinates, the photo library, or because it was shot just now - the
 * terrain's horizon around that point is matched against the skyline traced in the
 * picture (see {@link SkylineMatcher}). If the match is unambiguous, the user is asked
 * whether to turn the camera to it; on yes the camera swings to the bearing and pitch
 * found and takes the photo's field of view, so the mountains line up with the picture
 * overlaid on them.
 *
 * <p>The match has to wait for the terrain: the horizon is computed from the tiles the
 * viewer has loaded, so it runs once the app has arrived at the photo's location
 * ({@link #onLocationSettled}) and retries for a while until the loaded tiles cover the
 * ray march. Everything heavy happens on its own thread; only the camera change goes
 * back to the render thread.
 *
 * <p>The camera-control bar also has a button for doing it by hand ({@link #matchNow}):
 * at the viewer's current position, applied without asking, confident or not - for a
 * declined or never-offered automatic match, a moved view, or a photo with no location.
 *
 * <p>Static, like the background picture it belongs to: there is one photo behind the
 * terrain at a time, and {@link #clear} forgets it when that picture is removed.
 */
public final class PhotoSkylineAligner {

    private static final String TAG = "SKYLINE";
    /** A photo counts as taken "here" within this distance of the app's position. */
    private static final double SAME_PLACE_M = 500;
    private static final int HORIZON_BINS = 720;
    /** Fraction of the ray march that must find elevation data before matching. */
    private static final double MIN_COVERAGE = 0.9;
    private static final int COVERAGE_RETRIES = 12;
    private static final long RETRY_MILLIS = 2500;

    /** A loaded photograph waiting to be matched. */
    private static final class Pending {
        final int[] rgb;
        final int width;
        final int height;
        final int photoWidth;
        final int photoHeight;
        final float verticalFovDeg;
        /** The file as loaded, kept for {@link #saveSample}; null for a pixmap without one. */
        final byte[] bytes;
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        boolean started;
        volatile SkylineMatcher.Match lastMatch;

        Pending(int[] rgb, int width, int height, int photoWidth, int photoHeight, float verticalFovDeg,
                byte[] bytes) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.photoWidth = photoWidth;
            this.photoHeight = photoHeight;
            this.verticalFovDeg = verticalFovDeg;
            this.bytes = bytes;
        }

        boolean hasLocation() {
            return !Double.isNaN(latitude) && !Double.isNaN(longitude);
        }
    }

    private static final Object LOCK = new Object();
    private static Pending pending;

    private PhotoSkylineAligner() {
    }

    /**
     * Remembers a freshly loaded background photo. Reads what the EXIF block offers - the
     * field of view from the focal length, the place from the GPS tags - and keeps a small
     * copy of the pixels for the skyline. Cheap enough for the loading thread.
     *
     * @param photo the decoded, upright picture (not kept, not disposed)
     * @param jpeg  the file's bytes, for their EXIF tags; may be null
     */
    public static void onPhotoLoaded(Pixmap photo, byte[] jpeg) {
        if (photo == null) {
            return;
        }
        int[] size = new int[2];
        int[] rgb = downscale(photo, SkylineExtractor.DEFAULT_WIDTH, size);
        float vfov = Float.NaN;
        double[] latLon = null;
        if (jpeg != null) {
            ExifReader.CameraInfo info = ExifReader.extractCameraInfo(jpeg);
            vfov = info.verticalFovDeg(photo.getWidth(), photo.getHeight());
            latLon = ExifReader.extractLatLon(jpeg);
        }
        Pending p = new Pending(rgb, size[0], size[1], photo.getWidth(), photo.getHeight(), vfov, jpeg);
        synchronized (LOCK) {
            pending = p;
        }
        if (latLon != null && !(Math.abs(latLon[0]) < 0.001 && Math.abs(latLon[1]) < 0.001)) {
            setPendingLocation(latLon[0], latLon[1]);
        }
    }

    /**
     * Where the current background photo was taken, when known from somewhere other than
     * its EXIF block (iOS hands over the photo library's coordinates separately). If the
     * app is already there, the match starts at once; otherwise it waits for the arrival.
     */
    public static void setPendingLocation(double latitude, double longitude) {
        Pending p;
        synchronized (LOCK) {
            p = pending;
            if (p == null) {
                return;
            }
            p.latitude = latitude;
            p.longitude = longitude;
        }
        if (getC() != null && getC().L != null && !getC().L.isCurrentLocationNotSet()
                && isNear(p, getC().L.getCurrentLatitude(), getC().L.getCurrentLongitude())) {
            start(p);
        }
    }

    /** The current background photo was shot just now, at the app's own position. */
    public static void photoTakenHere() {
        if (getC() == null || getC().L == null || getC().L.isCurrentLocationNotSet()) {
            return;
        }
        setPendingLocation(getC().L.getCurrentLatitude(), getC().L.getCurrentLongitude());
    }

    /**
     * The viewer has landed at a location (a tile holding it finished loading). Starts the
     * match if that is where the pending photo was taken.
     */
    public static void onLocationSettled(double latitude, double longitude) {
        Pending p;
        synchronized (LOCK) {
            p = pending;
        }
        if (p != null && p.hasLocation() && isNear(p, latitude, longitude)) {
            start(p);
        }
    }

    /**
     * Matches the current background photo right now, at the viewer's own position, and
     * turns the camera to the best pose whether or not the match is confident: the user
     * pressed the button, so the choice is theirs. Repeats freely - after the automatic
     * match was declined or never offered, after the view was moved, or for a photo that
     * carries no location at all (it is then assumed to have been taken where the viewer
     * stands). Reports through the map's toast.
     */
    public static void matchNow() {
        final Pending p;
        synchronized (LOCK) {
            p = pending;
        }
        if (p == null || getC() == null || getC().L == null || getC().L.isCurrentLocationNotSet()) {
            return;
        }
        toast(s("Match_photo_direction_running"));
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SkylineMatcher.Match m = match(p, getC().L.getCurrentLatitude(),
                            getC().L.getCurrentLongitude(), 1);
                    if (m == null) {
                        toast(s("Match_photo_direction_failed"));
                        return;
                    }
                    apply(m, p);
                    toast(s("Match_photo_direction_applied") + " " + Math.round(m.bearingDeg) + "\u00b0"
                            + (m.isConfident() ? "" : " (" + s("Match_photo_direction_uncertain") + ")"));
                } catch (Throwable t) {
                    getLogger().error(TAG, "forced skyline match failed: " + t);
                }
            }
        }, "skyline-match");
        worker.setDaemon(true);
        worker.start();
    }

    private static void toast(final String text) {
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                MapViewerScreen screen = getC().getMapViewerScreen();
                if (screen != null) {
                    screen.toast(" " + text + " ");
                }
            }
        });
    }

    /**
     * Debug builds only: saves the current photo together with the camera's pose and the
     * terrain overlay it is shown against, as one more sample for the skyline dataset. The
     * idea is that the user lines the picture up by hand (or accepts a match and corrects
     * it), then presses the button: the pose the camera has at that moment is the truth
     * for that photo. Written under {@code LoadFactory.getDebugSamplesDir()}:
     *
     * <pre>
     * skyline_samples/
     *   manifest.json                 what tools/skyline_dataset.py writes: file, lat, lon,
     *                                 heading, pitch, roll, fov/vfov, focal35 - so the
     *                                 benchmark and TestSkylineDataset read it as is
     *   20260904_213012/photo.jpg     the file as it was loaded, EXIF and all
     *   20260904_213012/view.png      the view as the app drew it: the photo with the
     *                                 terrain outlines over it
     *   20260904_213012/sample.json   the pose in full, the horizon, the projected ridge,
     *                                 the extracted skyline and the last automatic match
     * </pre>
     *
     * The pose is read on the render thread; the files are written on a worker.
     */
    public static void saveSample() {
        final Pending p;
        synchronized (LOCK) {
            p = pending;
        }
        if (p == null || getC() == null || getC().getMapViewerScreen() == null) {
            return;
        }
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                final MapViewerScreen screen = getC().getMapViewerScreen();
                final Vector3 direction = new Vector3(screen.cam.direction);
                final float cameraFov = screen.cam.fieldOfView;
                final double lat = getC().L.getCurrentLatitude();
                final double lon = getC().L.getCurrentLongitude();
                final double altitude = Units.convertLatitsToMeters(screen.cam.position.z);
                final double aboveGround = MapViewerScreen.GROUND_CLEARANCE_METERS
                        + Math.max(0, screen.getCameraElevationMeters());
                final int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
                // The view as the app draws it - photo with the terrain outlines over it - is
                // captured by the next frame and written beside the photo.
                final java.util.concurrent.atomic.AtomicReference<Pixmap> frame = new java.util.concurrent.atomic.AtomicReference<Pixmap>();
                final java.util.concurrent.CountDownLatch frameReady = new java.util.concurrent.CountDownLatch(1);
                screen.captureFrame(new MapViewerScreen.FrameCapture() {
                    @Override
                    public void onFrame(Pixmap f) {
                        frame.set(f);
                        frameReady.countDown();
                    }
                });
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            java.io.File dir = writeSample(p, direction, cameraFov, lat, lon, altitude, aboveGround, sw, sh);
                            if (frameReady.await(5, java.util.concurrent.TimeUnit.SECONDS) && frame.get() != null) {
                                Pixmap f = frame.get();
                                try {
                                    com.badlogic.gdx.graphics.PixmapIO.writePNG(
                                            Gdx.files.absolute(new java.io.File(dir, "view.png").getAbsolutePath()), f);
                                } finally {
                                    Gdx.app.postRunnable(new Runnable() {
                                        @Override
                                        public void run() {
                                            f.dispose();
                                        }
                                    });
                                }
                            }
                            toast(s("Sample_saved") + " " + dir.getName());
                        } catch (Throwable t) {
                            getLogger().error(TAG, "saving the sample failed: " + t);
                            toast(s("Sample_save_failed"));
                        }
                    }
                }, "skyline-sample");
                worker.setDaemon(true);
                worker.start();
            }
        });
    }

    private static java.io.File writeSample(Pending p, Vector3 dir, float cameraFov, double lat, double lon,
                                            double altitude, double aboveGround, int sw, int sh)
            throws java.io.IOException {
        double bearing = (Math.toDegrees(Math.atan2(dir.x, dir.y)) + 360) % 360;
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.z))));
        // The photo's own vertical field of view: the inverse of apply()'s screen fit.
        double drawnHeight = sw > sh ? sh : sw * (double) p.photoHeight / Math.max(1, p.photoWidth);
        double vfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(cameraFov) / 2) * drawnHeight / sh));
        double fovWide = p.photoWidth >= p.photoHeight
                ? Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vfov) / 2) * p.photoWidth / (double) p.photoHeight))
                : vfov;

        java.io.File root = com.peaknav.utils.PeakNavUtils.getLoadFactory().getDebugSamplesDir();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ENGLISH)
                .format(new java.util.Date());
        java.io.File dirOut = new java.io.File(root, stamp);
        for (int n = 1; dirOut.exists(); n++) {
            dirOut = new java.io.File(root, stamp + "_" + n);
        }
        if (!dirOut.mkdirs()) {
            throw new java.io.IOException("cannot create " + dirOut);
        }
        String photoName = p.bytes != null && p.bytes.length > 8
                && (p.bytes[0] & 0xFF) == 0x89 && p.bytes[1] == 'P' ? "photo.png" : "photo.jpg";
        if (p.bytes != null) {
            java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dirOut, photoName));
            try {
                out.write(p.bytes);
            } finally {
                out.close();
            }
        }

        TerrainHorizon horizon = TerrainHorizon.compute(LOADED_TERRAIN, lat, lon, aboveGround, HORIZON_BINS);
        SkylineExtractor.Skyline skyline = SkylineExtractor.extract(p.rgb, p.width, p.height);
        SkylineMatcher matcher = new SkylineMatcher(horizon, skyline.rows, skyline.confidence, p.width, p.height);
        float[] ridge = matcher.projectHorizon(bearing, pitch, vfov, 0);

        com.google.gson.JsonObject sample = new com.google.gson.JsonObject();
        sample.addProperty("photo", photoName);
        sample.addProperty("photoWidth", p.photoWidth);
        sample.addProperty("photoHeight", p.photoHeight);
        com.google.gson.JsonObject camera = new com.google.gson.JsonObject();
        camera.addProperty("lat", lat);
        camera.addProperty("lon", lon);
        camera.addProperty("altitudeMeters", altitude);
        camera.addProperty("aboveGroundMeters", aboveGround);
        camera.addProperty("bearingDeg", bearing);
        camera.addProperty("pitchDeg", pitch);
        camera.addProperty("rollDeg", 0.0);
        camera.addProperty("photoVerticalFovDeg", vfov);
        camera.addProperty("photoWideSideFovDeg", fovWide);
        camera.addProperty("viewerFovDeg", cameraFov);
        camera.addProperty("viewerWidth", sw);
        camera.addProperty("viewerHeight", sh);
        sample.add("camera", camera);
        com.google.gson.JsonObject exif = new com.google.gson.JsonObject();
        if (p.hasLocation()) {
            exif.addProperty("lat", p.latitude);
            exif.addProperty("lon", p.longitude);
        }
        if (!Float.isNaN(p.verticalFovDeg)) {
            exif.addProperty("verticalFovDeg", p.verticalFovDeg);
        }
        sample.add("exif", exif);
        com.google.gson.JsonObject hz = new com.google.gson.JsonObject();
        hz.addProperty("bins", horizon.bins);
        hz.addProperty("eyeMeters", horizon.eyeMeters);
        hz.addProperty("coverage", horizon.coverage);
        hz.add("angleDeg", floats(horizon.angleDeg));
        hz.add("distanceM", floats(horizon.distanceM));
        sample.add("horizon", hz);
        com.google.gson.JsonObject overlay = new com.google.gson.JsonObject();
        overlay.addProperty("width", p.width);
        overlay.addProperty("height", p.height);
        overlay.add("ridgeRows", floats(ridge));
        overlay.add("skylineRows", floats(skyline.rows));
        overlay.add("skylineConfidence", floats(skyline.confidence));
        sample.add("overlay", overlay);
        if (p.lastMatch != null) {
            com.google.gson.JsonObject m = new com.google.gson.JsonObject();
            m.addProperty("bearingDeg", p.lastMatch.bearingDeg);
            m.addProperty("pitchDeg", p.lastMatch.pitchDeg);
            m.addProperty("verticalFovDeg", p.lastMatch.verticalFovDeg);
            m.addProperty("rollDeg", p.lastMatch.rollDeg);
            m.addProperty("cost", p.lastMatch.cost);
            m.addProperty("ratio", p.lastMatch.ratio());
            m.addProperty("confident", p.lastMatch.isConfident());
            sample.add("lastMatch", m);
        }
        writeText(new java.io.File(dirOut, "sample.json"), new com.google.gson.GsonBuilder().create().toJson(sample));

        // One more entry in the manifest the dataset tools read.
        java.io.File manifestFile = new java.io.File(root, "manifest.json");
        com.google.gson.JsonArray manifest = new com.google.gson.JsonArray();
        if (manifestFile.exists()) {
            try {
                manifest = new com.google.gson.JsonParser().parse(readText(manifestFile)).getAsJsonArray();
            } catch (RuntimeException e) {
                getLogger().warn(TAG, "manifest.json unreadable, starting a new one: " + e);
            }
        }
        com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        entry.addProperty("file", dirOut.getName() + "/" + photoName);
        entry.addProperty("lat", lat);
        entry.addProperty("lon", lon);
        entry.addProperty("heading", bearing);
        entry.addProperty("pitch", pitch);
        entry.addProperty("roll", 0.0);
        entry.addProperty("vfov", vfov);
        entry.addProperty("fov", fovWide);
        entry.addProperty("elevation", altitude);
        entry.addProperty("source", "app");
        entry.addProperty("page", dirOut.getName());
        manifest.add(entry);
        writeText(manifestFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        getLogger().info(TAG, "sample saved to " + dirOut);
        return dirOut;
    }

    private static com.google.gson.JsonArray floats(float[] values) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (float v : values) {
            a.add(Float.isNaN(v) ? null : Float.valueOf(v));
        }
        return a;
    }

    private static void writeText(java.io.File file, String text) throws java.io.IOException {
        java.io.OutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String readText(java.io.File file) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Forgets the pending photo (the background picture was removed). */
    public static void clear() {
        synchronized (LOCK) {
            pending = null;
        }
        com.peaknav.gesture.PhotoPin.clear();
    }

    private static boolean isNear(Pending p, double latitude, double longitude) {
        double dLat = (latitude - p.latitude) * 111195.0;
        double dLon = (longitude - p.longitude) * 111195.0 * Math.cos(Math.toRadians(latitude));
        return Math.hypot(dLat, dLon) <= SAME_PLACE_M;
    }

    private static void start(final Pending p) {
        synchronized (LOCK) {
            if (p.started || pending != p) {
                return;
            }
            p.started = true;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    match(p);
                } catch (Throwable t) {
                    getLogger().error(TAG, "skyline match failed: " + t);
                }
            }
        }, "skyline-match");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The terrain as the viewer has it loaded: NaN where no tile with elevation is in memory.
     *
     * <p>Not {@code ElevationUtils.getElevationLatitsFromMaxCoords}: that goes through
     * {@code CheckMissingData.getMaxZoomTile}, which builds its finest-zoom index from
     * zoom-8 column and row numbers, so the walk up the tile pyramid starts from a tile
     * that never exists and the lookup always comes back empty. Fixing it there changes
     * what the label loader does with every POI lacking an {@code ele} tag (they are
     * dropped today), so it is left alone here and the index is built properly instead.
     */
    private static final ElevationSampler LOADED_TERRAIN = new ElevationSampler() {
        @Override
        public float elevationMeters(double latitude, double longitude) {
            Tile index = CheckMissingData.getTileAtZoomLevel(latitude, longitude, MapTile.ZOOM_LEVEL_MAX);
            MapTile mapTile = getC().mapTileStorage.getFromMapIndexLessEq(index);
            if (mapTile == null || mapTile.isDisposed() || mapTile.elevationImage == null) {
                return Float.NaN;
            }
            // Only tiles whose elevations are already decoded: a tile still loading would
            // have this thread decode its pixmaps, which the render thread may be about to
            // dispose - reading freed native memory is a hang or a crash, not an exception.
            MapTile.MapTileState state = mapTile.getMapTileState();
            if (state != MapTile.MapTileState.CAN_DRAW && state != MapTile.MapTileState.IS_DRAWN) {
                return Float.NaN;
            }
            return Units.convertLatitsToMeters(
                    mapTile.elevationImage.getTileElevationLatitsFromMaxCoords(longitude, latitude));
        }
    };

    /**
     * The elevation model the match runs on: the tiles the viewer currently holds, at
     * whatever zoom each area is loaded at. Exposed so the headless tests can check the
     * horizon the app would compute against known terrain.
     */
    public static ElevationSampler loadedTerrain() {
        return LOADED_TERRAIN;
    }

    private static void match(Pending p) throws InterruptedException {
        final SkylineMatcher.Match m = match(p, p.latitude, p.longitude, COVERAGE_RETRIES);
        if (m == null || !m.isConfident()) {
            return;
        }
        synchronized (LOCK) {
            if (pending != p) {
                return;
            }
        }
        NativeScreenCaller caller = getNativeScreenCaller();
        if (caller == null) {
            return;
        }
        final Pending photo = p;
        caller.promptYesNo(s("Photo_direction_found"), s("Point_camera_to_photo_prompt"), new Runnable() {
            @Override
            public void run() {
                apply(m, photo);
            }
        });
    }

    /**
     * The horizon at a position from the loaded tiles, waiting for them to cover the ray
     * march, then the extracted skyline matched against it. Null when the terrain never
     * loaded far enough (or the photo was replaced meanwhile); the match otherwise, whether
     * confident or not.
     */
    private static SkylineMatcher.Match match(Pending p, double latitude, double longitude, int attempts)
            throws InterruptedException {
        MapViewerScreen screen = getC().getMapViewerScreen();
        if (screen == null) {
            return null;
        }
        // The camera's own height: the ground clearance plus whatever the elevation bar adds.
        double eye = MapViewerScreen.GROUND_CLEARANCE_METERS + Math.max(0, screen.getCameraElevationMeters());
        TerrainHorizon horizon = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            synchronized (LOCK) {
                if (pending != p) {
                    return null; // another photo took over, or the picture was closed
                }
            }
            horizon = TerrainHorizon.compute(LOADED_TERRAIN, latitude, longitude, eye, HORIZON_BINS);
            if (horizon.coverage >= MIN_COVERAGE) {
                break;
            }
            if (attempt + 1 < attempts) {
                Thread.sleep(RETRY_MILLIS);
            }
        }
        if (horizon == null || horizon.coverage < MIN_COVERAGE) {
            getLogger().info(TAG, "terrain never loaded far enough for a skyline match");
            return null;
        }
        SkylineExtractor.Skyline skyline = SkylineExtractor.extract(p.rgb, p.width, p.height);
        SkylineMatcher matcher = new SkylineMatcher(horizon, skyline.rows, skyline.confidence, p.width, p.height);
        SkylineMatcher.Match m = Float.isNaN(p.verticalFovDeg) ? matcher.match() : matcher.match(p.verticalFovDeg);
        p.lastMatch = m;
        getLogger().info(TAG, String.format(java.util.Locale.ENGLISH,
                "photo skyline at %.5f,%.5f eye %.0f m, horizon coverage %.2f, %dx%d px: %s",
                latitude, longitude, eye, horizon.coverage, p.width, p.height, m));
        return m;
    }

    /** Turns the camera to the matched pose, from any thread. */
    static void apply(final SkylineMatcher.Match m, final Pending p) {
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                applyOnRenderThread(m, p);
            }
        });
    }

    private static void applyOnRenderThread(SkylineMatcher.Match m, Pending p) {
        MapViewerScreen screen = getC().getMapViewerScreen();
        if (screen == null) {
            return;
        }
        com.peaknav.gesture.PhotoPin.clear();   // the pose is replaced wholesale
        // World axes are east, north, up (see PeakNavRenderer.aim for the same construction).
        double bearing = Math.toRadians(m.bearingDeg);
        double pitch = Math.toRadians(m.pitchDeg);
        Vector3 direction = new Vector3(
                (float) (Math.sin(bearing) * Math.cos(pitch)),
                (float) (Math.cos(bearing) * Math.cos(pitch)),
                (float) Math.sin(pitch)).nor();
        Vector3 right = direction.cpy().crs(0f, 0f, 1f).nor();
        Vector3 up = right.cpy().crs(direction).nor();
        screen.moveCameraAction.clearSteps();
        screen.moveCameraAction.setCameraVectors(null, direction, up, false);

        // The photo is drawn scaled to fit the window: on a landscape window it spans the
        // full height, on a portrait one only part of it. The camera's (vertical) field of
        // view has to cover the window, so it is the photo's widened by that ratio.
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        double drawnHeight = sw > sh ? sh : sw * (double) p.photoHeight / Math.max(1, p.photoWidth);
        double half = Math.tan(Math.toRadians(m.verticalFovDeg) / 2) * sh / Math.max(1.0, drawnHeight);
        float fov = (float) Math.toDegrees(2 * Math.atan(half));
        screen.cam.fieldOfView = Math.max(MountainInputController.FIELD_OF_VIEW_MIN,
                Math.min(MountainInputController.FIELD_OF_VIEW_MAX, fov));
        screen.cam.update();
    }

    /**
     * Reduction of a pixmap to {@code width} pixels wide, as packed RGB. Reads the pixel
     * buffer directly for the formats a decoded photo comes in, and touches only a few
     * pixels per output pixel (a 2x2 patch at the block's centre) rather than every one
     * of them: this runs on whatever thread loaded the photo, which on Android can be
     * the interface thread, and a 12-megapixel walk there is a visible stall.
     */
    static int[] downscale(Pixmap src, int width, int[] sizeOut) {
        int w = src.getWidth(), h = src.getHeight();
        int nw = Math.min(width, w);
        int nh = Math.max(1, (int) Math.round(h * (double) nw / w));
        sizeOut[0] = nw;
        sizeOut[1] = nh;
        Pixmap.Format format = src.getFormat();
        int bytesPerPixel = format == Pixmap.Format.RGB888 ? 3 : format == Pixmap.Format.RGBA8888 ? 4 : 0;
        ByteBuffer buffer = bytesPerPixel > 0 ? src.getPixels() : null;
        long[] sumR = new long[nw * nh], sumG = new long[nw * nh], sumB = new long[nw * nh];
        int[] count = new int[nw * nh];
        double sx = w / (double) nw, sy = h / (double) nh;
        for (int ty = 0; ty < nh; ty++) {
            int y0 = Math.min(h - 2, (int) ((ty + 0.5) * sy) - 1);
            for (int tx = 0; tx < nw; tx++) {
                int x0 = Math.min(w - 2, (int) ((tx + 0.5) * sx) - 1);
                int t = ty * nw + tx;
                for (int dy = 0; dy < 2; dy++) {
                    int y = Math.max(0, y0 + dy);
                    for (int dx = 0; dx < 2; dx++) {
                        int x = Math.max(0, x0 + dx);
                        int r, g, b;
                        if (buffer != null) {
                            int i = (y * w + x) * bytesPerPixel;
                            r = buffer.get(i) & 0xFF;
                            g = buffer.get(i + 1) & 0xFF;
                            b = buffer.get(i + 2) & 0xFF;
                        } else {
                            int p = src.getPixel(x, y); // RGBA8888
                            r = (p >>> 24) & 0xFF;
                            g = (p >>> 16) & 0xFF;
                            b = (p >>> 8) & 0xFF;
                        }
                        sumR[t] += r;
                        sumG[t] += g;
                        sumB[t] += b;
                        count[t]++;
                    }
                }
            }
        }
        int[] out = new int[nw * nh];
        for (int i = 0; i < out.length; i++) {
            int c = Math.max(1, count[i]);
            out[i] = ((int) (sumR[i] / c) << 16) | ((int) (sumG[i] / c) << 8) | (int) (sumB[i] / c);
        }
        return out;
    }
}
