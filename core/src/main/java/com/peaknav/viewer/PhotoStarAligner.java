package com.peaknav.viewer;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLogger;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.gesture.MountainInputController;
import com.peaknav.sky.StarPositions;
import com.peaknav.stars.StarExtractor;
import com.peaknav.stars.StarMatcher;
import com.peaknav.utils.ExifReader;
import com.peaknav.viewer.screens.MapViewerScreen;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Offers to point the camera the way a photograph of the night sky was taken.
 *
 * <p>The counterpart of {@link PhotoSkylineAligner} for pictures with stars in them instead
 * of mountains. When a picture loaded behind the terrain looks like a night sky - dark,
 * with point sources - the sources {@link StarExtractor} finds are matched by
 * {@link StarMatcher} against the app's own sky: the catalogue stars and planets as they
 * stand at the viewer's position and at the photo's instant (its EXIF time; the sky's own
 * clock for a picture without one). The camera's current direction is the first place
 * looked - the phone held up at the sky with the gyroscope on points the app's camera
 * where the photo was shot - and the whole visible sky the second. A confident match is
 * offered; on yes the camera swings to the bearing, pitch and roll found, takes the
 * photo's field of view, turns the sky view on and, for an old picture, sets the sky's
 * clock to the photo's time, so the stars line up with the picture overlaid on them.
 *
 * <p>The photo bar's match button ({@link #matchNow}) does it by hand and applies the pose
 * confident or not; for a picture that is not a night sky it hands over to the skyline
 * matcher. Everything heavy runs on its own thread; only the camera change goes back to
 * the render thread.
 *
 * <p>Static, like the background picture it belongs to: one photo behind the terrain at a
 * time, forgotten by {@link #clear} when that picture is removed.
 */
public final class PhotoStarAligner {

    private static final String TAG = "STARS";
    /** Catalogue stars fainter than this are not looked for. */
    private static final float FAINTEST_MAG = 6.0f;
    /** A photo whose time differs from the sky's clock by more than this sets the clock. */
    private static final long SAME_TIME_MILLIS = 2 * 60 * 1000L;

    private static final class Pending {
        final int[] rgb;
        final int width;
        final int height;
        final int photoWidth;
        final int photoHeight;
        final float verticalFovDeg;
        final long timeMillis;
        boolean started;
        /** The sources in this photo: they depend on the picture alone, so found once. */
        volatile StarExtractor.Field field;
        volatile StarMatcher.Match lastMatch;

        Pending(int[] rgb, int width, int height, int photoWidth, int photoHeight, float verticalFovDeg,
                long timeMillis) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.photoWidth = photoWidth;
            this.photoHeight = photoHeight;
            this.verticalFovDeg = verticalFovDeg;
            this.timeMillis = timeMillis;
        }
    }

    private static final Object LOCK = new Object();
    private static Pending pending;
    /** Off, loading a photo does not start a match on its own. */
    private static volatile boolean automatic = true;

    private PhotoStarAligner() {
    }

    /**
     * Remembers a freshly loaded background photo: a reduced copy of its pixels, the field
     * of view from its EXIF focal length and the instant it was taken, then starts looking
     * for stars in it on a worker thread.
     *
     * @param photo the decoded, upright picture (not kept, not disposed)
     * @param jpeg  the file's bytes, for their EXIF tags; may be null
     */
    public static void onPhotoLoaded(Pixmap photo, byte[] jpeg) {
        if (photo == null) {
            return;
        }
        int[] size = new int[2];
        int[] rgb = reduce(photo, StarExtractor.DEFAULT_WIDTH, size);
        float vfov = Float.NaN;
        long time = ExifReader.NO_TIMESTAMP;
        if (jpeg != null) {
            vfov = ExifReader.extractCameraInfo(jpeg).verticalFovDeg(photo.getWidth(), photo.getHeight());
            time = ExifReader.extractTimestampMillis(jpeg);
        }
        Pending p = new Pending(rgb, size[0], size[1], photo.getWidth(), photo.getHeight(), vfov, time);
        synchronized (LOCK) {
            pending = p;
        }
        if (automatic) {
            start(p);
        }
    }

    public static void setAutomatic(boolean on) {
        automatic = on;
    }

    /** Whether a photo is loaded behind the terrain. */
    public static boolean hasPhoto() {
        synchronized (LOCK) {
            return pending != null;
        }
    }

    /** Forgets the pending photo (the background picture was removed). */
    public static void clear() {
        synchronized (LOCK) {
            pending = null;
        }
    }

    /**
     * Matches the current background photo right now and turns the camera to the best pose
     * whether or not the match is confident: the user pressed the button, so the choice is
     * theirs. For a picture that is not a night sky, {@code otherwise} runs instead (the
     * skyline matcher). Reports through the map's toast.
     */
    public static void matchNow(final Runnable otherwise) {
        final Pending p;
        synchronized (LOCK) {
            p = pending;
        }
        if (p == null || getC() == null || getC().L == null || getC().L.isCurrentLocationNotSet()) {
            if (otherwise != null) {
                otherwise.run();
            }
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!fieldOf(p).looksLikeNightSky()) {
                        if (otherwise != null) {
                            otherwise.run();
                        }
                        return;
                    }
                    toast(s("Match_stars_running"), true);
                    StarMatcher.Match m = match(p);
                    if (m == null) {
                        toast(s("Match_stars_failed"), false);
                        return;
                    }
                    apply(m, p);
                    toast(s("Match_photo_direction_applied") + " " + Math.round(m.bearingDeg) + "°"
                            + (m.isConfident() ? "" : " (" + s("Match_photo_direction_uncertain") + ")"), false);
                } catch (Throwable t) {
                    getLogger().error(TAG, "forced star match failed: " + t);
                    releaseToast();
                }
            }
        }, "star-match");
        worker.setDaemon(true);
        worker.start();
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
                    offer(p);
                } catch (Throwable t) {
                    getLogger().error(TAG, "star match failed: " + t);
                }
            }
        }, "star-match");
        worker.setDaemon(true);
        worker.start();
    }

    /** The automatic match: only a night sky, only a confident pose, and then a question. */
    private static void offer(Pending p) throws InterruptedException {
        if (!fieldOf(p).looksLikeNightSky()) {
            return;
        }
        if (getC() == null || getC().L == null || getC().L.isCurrentLocationNotSet()) {
            return;
        }
        final StarMatcher.Match m = match(p);
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
        caller.promptYesNo(s("Photo_stars_found"), s("Point_camera_to_stars_prompt"), new Runnable() {
            @Override
            public void run() {
                apply(m, photo);
            }
        });
    }

    private static StarExtractor.Field fieldOf(Pending p) {
        StarExtractor.Field field = p.field;
        if (field == null) {
            synchronized (p) {
                field = p.field;
                if (field == null) {
                    field = StarExtractor.extract(p.rgb, p.width, p.height);
                    p.field = field;
                }
            }
        }
        return field;
    }

    /**
     * The sky at the viewer's position and the photo's instant, matched against the
     * photo's sources, the camera's own direction first. Null when there is no catalogue.
     */
    private static StarMatcher.Match match(Pending p) throws InterruptedException {
        StarExtractor.Field field = fieldOf(p);
        double lat = getC().L.getCurrentLatitude(), lon = getC().L.getCurrentLongitude();
        long time = p.timeMillis != ExifReader.NO_TIMESTAMP ? p.timeMillis : getC().skyModel.currentTimeMillis();
        getC().skyModel.loadAssets();
        StarPositions sky = StarPositions.compute(getC().skyModel.getStars(), lat, lon, time, FAINTEST_MAG);
        if (sky.count < 3) {
            getLogger().info(TAG, "no star catalogue loaded, no star match");
            return null;
        }
        double[] prior = cameraPose();
        StarMatcher matcher = StarMatcher.of(field, sky.enu, sky.mag, sky.count);
        StarMatcher.Match m = matcher.match(prior == null ? Double.NaN : prior[0], prior == null ? Double.NaN : prior[1],
                StarMatcher.DEFAULT_PRIOR_RADIUS_DEG, p.verticalFovDeg);
        p.lastMatch = m;
        getLogger().info(TAG, String.format(java.util.Locale.ENGLISH,
                "photo stars at %.5f,%.5f, %d sources in %dx%d px (median %.0f), prior %s: %s",
                lat, lon, field.sources.length, field.width, field.height, field.medianLuminance,
                prior == null ? "none" : String.format(java.util.Locale.ENGLISH, "%.0f/%.0f", prior[0], prior[1]), m));
        return m;
    }

    /** {bearing, pitch} of the camera, read on the render thread; null when it cannot be had. */
    private static double[] cameraPose() throws InterruptedException {
        if (Gdx.app == null) {
            return null;
        }
        final double[] pose = new double[2];
        final CountDownLatch read = new CountDownLatch(1);
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    MapViewerScreen screen = getC().getMapViewerScreen();
                    if (screen != null) {
                        Vector3 dir = screen.cam.direction;
                        pose[0] = (Math.toDegrees(Math.atan2(dir.x, dir.y)) + 360) % 360;
                        pose[1] = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.z))));
                    } else {
                        pose[0] = Double.NaN;
                    }
                } finally {
                    read.countDown();
                }
            }
        });
        if (!read.await(2, TimeUnit.SECONDS) || Double.isNaN(pose[0])) {
            return null;
        }
        return pose;
    }

    /** Turns the camera to the matched pose, from any thread. */
    static void apply(final StarMatcher.Match m, final Pending p) {
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                applyOnRenderThread(m, p);
            }
        });
    }

    private static void applyOnRenderThread(StarMatcher.Match m, Pending p) {
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
        Vector3 up = cameraUp(direction, m.rollDeg);
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

        // The stars have to be drawn to line up with anything, and at the photo's instant.
        if (!P.isSkyView()) {
            P.setSkyView(true);
        }
        if (p.timeMillis != ExifReader.NO_TIMESTAMP
                && Math.abs(p.timeMillis - getC().skyModel.currentTimeMillis()) > SAME_TIME_MILLIS) {
            getC().skyModel.setCustomTimeMillis(p.timeMillis);
        }
    }

    /**
     * The camera's up vector for a direction and a roll: the level up (right = direction x
     * world up, up = right x direction, so the camera stays upright at any pitch) turned
     * about the direction by the roll. Positive roll tilts the horizon clockwise in the
     * picture, the matcher's convention.
     */
    static Vector3 cameraUp(Vector3 direction, float rollDeg) {
        Vector3 right = direction.cpy().crs(0f, 0f, 1f);
        if (right.len2() < 1e-6f) {
            right.set(1f, 0f, 0f);   // straight up or down
        }
        right.nor();
        Vector3 up = right.cpy().crs(direction).nor();
        double roll = Math.toRadians(rollDeg);
        return up.scl((float) Math.cos(roll)).mulAdd(right, (float) -Math.sin(roll)).nor();
    }

    /**
     * Reduction of a pixmap to at most {@code maxWidth} pixels wide by averaging whole
     * blocks - every pixel is read, unlike the skyline's sparse sample, because a star is
     * a few pixels that a sample would miss. Reads the pixel buffer directly for the
     * formats a decoded photo comes in.
     */
    static int[] reduce(Pixmap src, int maxWidth, int[] sizeOut) {
        int w = src.getWidth(), h = src.getHeight();
        int block = Math.max(1, (w + maxWidth - 1) / maxWidth);
        int nw = w / block, nh = h / block;
        sizeOut[0] = nw;
        sizeOut[1] = nh;
        Pixmap.Format format = src.getFormat();
        int bytesPerPixel = format == Pixmap.Format.RGB888 ? 3 : format == Pixmap.Format.RGBA8888 ? 4 : 0;
        ByteBuffer buffer = bytesPerPixel > 0 ? src.getPixels() : null;
        int[] out = new int[nw * nh];
        int count = block * block;
        for (int ty = 0; ty < nh; ty++) {
            for (int tx = 0; tx < nw; tx++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < block; dy++) {
                    int y = ty * block + dy;
                    for (int dx = 0; dx < block; dx++) {
                        int x = tx * block + dx;
                        if (buffer != null) {
                            int i = (y * w + x) * bytesPerPixel;
                            r += buffer.get(i) & 0xFF;
                            g += buffer.get(i + 1) & 0xFF;
                            b += buffer.get(i + 2) & 0xFF;
                        } else {
                            int p = src.getPixel(x, y); // RGBA8888
                            r += (p >>> 24) & 0xFF;
                            g += (p >>> 16) & 0xFF;
                            b += (p >>> 8) & 0xFF;
                        }
                    }
                }
                out[ty * nw + tx] = ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        return out;
    }

    private static void toast(final String text, final boolean hold) {
        if (Gdx.app == null) {
            return;
        }
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                MapViewerScreen screen = getC().getMapViewerScreen();
                if (screen != null) {
                    if (hold) {
                        screen.toastUntilReleased(" " + text + " ");
                    } else {
                        screen.toast(" " + text + " ");
                    }
                }
            }
        });
    }

    private static void releaseToast() {
        if (Gdx.app == null) {
            return;
        }
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                MapViewerScreen screen = getC().getMapViewerScreen();
                if (screen != null) {
                    screen.releaseToast();
                }
            }
        });
    }
}
