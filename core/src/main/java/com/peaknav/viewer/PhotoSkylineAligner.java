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
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        boolean started;

        Pending(int[] rgb, int width, int height, int photoWidth, int photoHeight, float verticalFovDeg) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.photoWidth = photoWidth;
            this.photoHeight = photoHeight;
            this.verticalFovDeg = verticalFovDeg;
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
        Pending p = new Pending(rgb, size[0], size[1], photo.getWidth(), photo.getHeight(), vfov);
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

    /** Forgets the pending photo (the background picture was removed). */
    public static void clear() {
        synchronized (LOCK) {
            pending = null;
        }
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
            if (mapTile == null || mapTile.isDisposed() || mapTile.elevationImage == null
                    || mapTile.getMapTileState() == MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED) {
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
     * Area-averaged reduction of a pixmap to {@code width} pixels wide, as packed RGB.
     * Reads the pixel buffer directly for the formats a decoded photo comes in.
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
        for (int y = 0; y < h; y++) {
            int ty = Math.min(nh - 1, (int) (y * (long) nh / h));
            for (int x = 0; x < w; x++) {
                int tx = Math.min(nw - 1, (int) (x * (long) nw / w));
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
                int t = ty * nw + tx;
                sumR[t] += r;
                sumG[t] += g;
                sumB[t] += b;
                count[t]++;
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
