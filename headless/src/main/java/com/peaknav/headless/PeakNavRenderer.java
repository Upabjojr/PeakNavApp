package com.peaknav.headless;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;

import com.peaknav.utils.PreferencesManager;
import com.peaknav.viewer.I18NWrapper;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import com.peaknav.areas.MapArea;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.labels.DrawLabel;
import com.peaknav.viewer.renderer_gdx.LabelRenderer;
import com.peaknav.viewer.screens.MapViewerScreen;
import com.peaknav.viewer.desktop.DesktopFiles;
import com.peaknav.viewer.desktop.MapViewerDesktopSingleton;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the real PeakNav renderer with no visible window, so views can be produced from
 * code instead of from a user at a keyboard.
 *
 * <pre>
 * try (PeakNavRenderer r = PeakNavRenderer.start(1600, 1000)) {
 *     r.moveTo(46.0207, 7.7491);      // Zermatt
 *     r.aim(230f, -4f);               // look south-west, slightly down
 *     r.settle(20_000);
 *     r.capture(new File("matterhorn.png"));
 * }
 * </pre>
 *
 * <h2>Why not libGDX's headless backend</h2>
 * {@code gdx-backend-headless} stubs graphics out completely - there is no GL context, so
 * it cannot draw a single terrain pixel. What this class uses instead is the ordinary
 * LWJGL3 backend with {@code setInitialVisible(false)}, which creates a real GL context
 * whose frames can be read back while the window is never mapped onto a display. A display
 * connection is still required (GLFW needs one to create the context); on a headless
 * machine, run it against {@code Xvfb}.
 *
 * <h2>Threading</h2>
 * {@link Lwjgl3Application}'s constructor does not return until the app exits, so it is run
 * on its own thread. Every method here is called from the caller's thread and hops onto the
 * render thread internally, blocking until that work is done - so callers can write
 * straight-line code without knowing about the frame loop.
 */
public final class PeakNavRenderer implements AutoCloseable {

    /** Longest any single render-thread action may take before it is treated as a hang. */
    private static final long ACTION_TIMEOUT_SECONDS = 120;

    /** The offscreen buffer every frame is drawn into; null only until start() installs it. */
    private com.badlogic.gdx.graphics.glutils.FrameBuffer offscreen;

    /**
     * From here on, the app draws into an offscreen framebuffer object instead of the
     * hidden window's surface - headless only; the interactive app is untouched.
     *
     * <p>Why: snapshots read pixels back from whatever framebuffer is bound, and for a
     * HIDDEN window the window-system framebuffer's content is undefined - X11 only
     * defines pixel ownership for visible surfaces. In practice that was survivable
     * with one renderer and catastrophic with two: readbacks could return memory
     * holding the OTHER process's frame, and parallel video runs interleaved each
     * other's pictures. An FBO's pixels are plain GPU memory owned by this context -
     * defined always, visible to nobody else.
     *
     * <p>How: libGDX's nested render-to-texture passes (sobel, pseudodistances, the
     * texture joiner) all return to {@code GLFrameBuffer.defaultFramebufferHandle}
     * when they end. That static exists precisely because a platform's "screen" may
     * itself be an FBO - iOS runs this way permanently. Pointing it at our FBO makes
     * every pass in the app come home to the offscreen buffer with no change to any
     * of them; the app has no direct glBindFramebuffer(0) calls (verified), so there
     * is no other way back to the window. The window still swaps undefined garbage
     * nobody sees, at the wall-clock pace of a swap, which is why vsync stays off.
     */
    private void renderIntoOffscreenBuffer() {
        offscreen = new com.badlogic.gdx.graphics.glutils.FrameBuffer(
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, width, height, true);
        try {
            java.lang.reflect.Field handle = com.badlogic.gdx.graphics.glutils.GLFrameBuffer.class
                    .getDeclaredField("defaultFramebufferHandle");
            java.lang.reflect.Field initialized = com.badlogic.gdx.graphics.glutils.GLFrameBuffer.class
                    .getDeclaredField("defaultFramebufferHandleInitialized");
            handle.setAccessible(true);
            initialized.setAccessible(true);
            handle.setInt(null, offscreen.getFramebufferHandle());
            initialized.setBoolean(null, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "cannot redirect the default framebuffer; libGDX internals moved", e);
        }
        Gdx.gl.glBindFramebuffer(com.badlogic.gdx.graphics.GL20.GL_FRAMEBUFFER,
                offscreen.getFramebufferHandle());
    }

    private final int width;
    private final int height;
    private final Thread appThread;
    private final MapApp mapApp;
    private final FileSnapshotWriter snapshotWriter = new FileSnapshotWriter();

    private PeakNavRenderer(int width, int height, Thread appThread, MapApp mapApp) {
        this.width = width;
        this.height = height;
        this.appThread = appThread;
        this.mapApp = mapApp;
    }

    /**
     * Boots the app off-screen at the given framebuffer size and returns once the render
     * loop is running. The size is fixed for the lifetime of the renderer, because it is
     * the window's size and the app lays its interface out against it.
     */
    public static PeakNavRenderer start(int width, int height) {
        return start(width, height, Locale.ENGLISH);
    }

    /**
     * Boots the renderer with its text in a chosen language.
     *
     * <p>English by default, deliberately: without a choice the app follows the system
     * language, so the same shot rendered on an Italian desktop came back with the planets
     * labelled Mercurio and Venere. A render should not depend on whose machine it ran on.
     *
     * @param locale the language for labels and interface text
     */
    public static PeakNavRenderer start(int width, int height, Locale locale) {
        I18NWrapper.setLocaleOverride(locale);

        // Settings changed by a render stay in this process. The data directory is shared with
        // the desktop app on purpose - downloaded map data should be downloaded once - but that
        // directory also holds the preferences file, so without this a scripted render left the
        // app with its sky switched off, its labels rearranged and its viewpoint moved to
        // wherever the last shot was taken. Map data is unaffected; only settings are isolated.
        PreferencesManager.setEphemeral(true);

        MapViewerDesktopSingleton.initializeDesktopGraphicFactory();
        final MapApp app = MapViewerDesktopSingleton.getAppInstance();

        final CountDownLatch running = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            try {
                Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
                config.setWindowedMode(width, height);
                // The whole point: a real context, never shown to anyone.
                config.setInitialVisible(false);
                config.setTitle("PeakNav (headless)");
                config.setForegroundFPS(60);
                // Never pace an off-screen renderer by display vsync: the window is
                // hidden, and a hidden surface's vsync under Mesa can degrade to a
                // one-hertz fallback clock depending on compositor state - measured
                // as exactly 1000 ms per render frame, turning every render-thread
                // hop into a one-second wait. The foreground FPS cap above is the
                // pacing; the display has nothing to do with it.
                config.useVsync(false);
                config.setPreferencesConfig(
                        DesktopFiles.getGdxFilesExternalRootFolderName(), Files.FileType.External);
                new Lwjgl3Application(app, config) {
                    @Override
                    protected com.badlogic.gdx.Files createFiles() {
                        return new DesktopFiles();
                    }
                };
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                running.countDown();
            }
        }, "peaknav-headless-render");
        thread.setDaemon(true);
        thread.start();

        // Gdx.app is assigned early in the Lwjgl3Application constructor, but the loop that
        // drains posted runnables starts later; waiting for a runnable to actually execute
        // is the only signal that the app is live rather than merely constructed.
        waitForLoop(running, failure);

        PeakNavRenderer renderer = new PeakNavRenderer(width, height, thread, app);
        // MapApp opens on the intro screen, which is a menu, not the map.
        renderer.onRenderThread(() -> {
            app.setScreen(app.mapViewerScreen);
            // Redirects finished snapshots to a file instead of the desktop save dialog.
            app.nativeScreenCaller = renderer.snapshotWriter;
            renderer.renderIntoOffscreenBuffer();
        });
        return renderer;
    }

    private static void waitForLoop(CountDownLatch running, AtomicReference<Throwable> failure) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ACTION_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (failure.get() != null) {
                throw new IllegalStateException("headless app failed to start", failure.get());
            }
            if (running.getCount() == 0) {
                throw new IllegalStateException("headless app exited during start-up", failure.get());
            }
            if (Gdx.app != null) {
                CountDownLatch alive = new CountDownLatch(1);
                Gdx.app.postRunnable(alive::countDown);
                try {
                    if (alive.await(2, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while starting", e);
                }
            }
            sleep(100);
        }
        throw new IllegalStateException("headless app did not start within "
                + ACTION_TIMEOUT_SECONDS + "s");
    }

    /**
     * Puts the viewpoint at these coordinates.
     *
     * <p>The two-argument {@code setCurrentTargetCoords} defaults to checking for missing map
     * data, which raises the "open the download window?" prompt. Passing {@code false} keeps a
     * move silent; fetching data is an explicit step here, {@link #downloadMissingData}.
     */
    public PeakNavRenderer moveTo(final double latitude, final double longitude) {
        // Somewhere new: whatever the wait concluded about the road layer here does not apply
        // there, so it gets to reach its own conclusion.
        roadBacklogGivenUp = -1;
        onRenderThread(() -> {
            getC().L.setCurrentTargetCoords(latitude, longitude, false);
            // Any camera animation dies here, every frame. The boot's arrival callback
            // fires whenever the target tile finishes loading - which can be minutes
            // late, mid-chunk - and starts an animated fly toward wherever the target
            // was at boot. One acted step of that animation yanked the camera off the
            // ring for exactly one frame: a single rogue frame from a low, wrong
            // perspective every ~120 frames, at whatever moment the tile load chose.
            mapApp.mapViewerScreen.moveCameraAction.clearSteps();
            {
                // Land NOW, surgically, and UNCONDITIONALLY - even when a late
                // arrival callback claims the target is already reached, its idea of
                // "reached" may be an animation aimed somewhere stale; this assert is
                // what the frame actually captures. The full story: the async arrival
                // only fires when an elevation image LOADS, so a target moving within
                // loaded terrain never lands (30-second timeouts); the interactive
                // arrival ritual costs seconds and moves the camera on its own. What
                // a frame needs is the INVARIANT: current coords == target coords ==
                // camera position, elevation reference best-effort, sky updated on
                // the refresh cadence. Height and direction are the frame's own
                // applyHeight and aim calls, as always.
                Float resident = com.peaknav.elevation.ElevationUtils
                        .getElevationLatitsFromMaxCoords(longitude, latitude, false);
                if (resident != null) {
                    getC().L.setCurrentTerrainEleQuiet(resident);
                }
                getC().L.landOnTargetQuiet();
                com.badlogic.gdx.graphics.Camera cam = mapApp.mapViewerScreen.cam;
                cam.position.x = (float) com.peaknav.utils.Units
                        .convertLonitsToLatits(longitude, latitude);
                cam.position.y = (float) latitude;
                // Deliberately NO skyModel.invalidate() here: rebuilding the sky is a
                // full pass over thousands of stars, and doing it per video frame
                // dragged the whole render loop to ~1 fps - every render-thread hop
                // then cost a second. A 115 m step moves the sky a ten-thousandth of
                // a degree; the sky is refreshed with the labels instead, every half
                // second of video (see refreshLabels).
            }
        });
        return this;
    }

    /**
     * Points the camera, through the app's own camera-movement API.
     *
     * <p>Never write {@code cam.direction} directly: the camera is owned by
     * {@code MapViewerScreen.moveCameraAction}, a scene2d action the render loop advances
     * every frame, and a direct write is overwritten by whatever step that action has queued
     * — a fly-to from {@code setCurrentTargetCoords}, a GPX tour, anything. (That is exactly
     * how this method used to fail: the aim held on some runs and vanished on others,
     * depending on whether a move was in flight.) Going through
     * {@code setCameraVectors(..., immediate=true)} both applies the pose and clears the
     * queue, the same way the app's own gyroscope pointing does — see
     * {@code MapViewerScreen.pointCameraForGyroscope}.
     *
     * @param bearingDegrees compass bearing, 0 = north, 90 = east
     * @param pitchDegrees   positive looks up, negative looks down; 0 is the horizon
     */
    public PeakNavRenderer aim(final float bearingDegrees, final float pitchDegrees) {
        onRenderThread(() -> {
            // World axes are ENU: +X east, +Y north, +Z up. That is what lets the app derive
            // its compass heading as atan2(dir.y, dir.x) (see DataRetrieveThreadManager), and
            // it is the same convention as the lastCameraDirection* preferences.
            double bearing = Math.toRadians(bearingDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            double horizontal = Math.cos(pitch);

            Vector3 direction = new Vector3(
                    (float) (Math.sin(bearing) * horizontal),
                    (float) (Math.cos(bearing) * horizontal),
                    (float) Math.sin(pitch)).nor();

            // right = direction x worldUp, up = right x direction. Derived rather than
            // hardcoded so the camera stays upright at any pitch.
            Vector3 right = direction.cpy().crs(0f, 0f, 1f).nor();
            Vector3 up = right.cpy().crs(direction).nor();

            // null position = keep where the camera is; immediate = apply now and drop
            // any queued move that would otherwise overwrite this pose next frame.
            mapApp.mapViewerScreen.moveCameraAction.setCameraVectors(null, direction, up, true);
        });
        return this;
    }

    /**
     * Sets the viewpoint's height, as a fraction of the app's elevation bar.
     *
     * <p>The bar is deliberately non-linear - fine control near the ground, kilometres per
     * drag high up - so a fraction is a poor way to ask for a height: 0.45 is about 2.5 km
     * above the ground, not 450 m. Prefer {@link #setElevationMeters(double)} unless you are
     * specifically exercising the slider.
     *
     * @param fraction 0 puts the camera at the bottom of the bar, 1 at the top; this is the
     *                 same control the Page Up / Page Down keys nudge
     */
    public PeakNavRenderer setElevation(final float fraction) {
        onRenderThread(() -> mapApp.mapViewerScreen.setCameraElevationBar(
                Math.max(0f, Math.min(1f, fraction))));
        return this;
    }

    /**
     * Sets the viewpoint's height in metres above the terrain beneath it.
     *
     * @param metersAboveGround height above the ground; clamped to the app's ceiling
     */
    public PeakNavRenderer setElevationMeters(final double metersAboveGround) {
        onRenderThread(() -> mapApp.mapViewerScreen.setCameraElevationMeters(metersAboveGround));
        return this;
    }

    /**
     * Sets the viewpoint's absolute altitude, in metres above sea level.
     *
     * <p>What a moving camera wants: held at a constant height above the <em>ground</em>, a
     * camera rides up over ridges and down into valleys, and its subject bobs in the frame.
     *
     * @param metersAboveSeaLevel absolute altitude; raised if it would be underground
     */
    /**
     * Adds the paths of a GPX document to the map, drawn on the terrain like the app draws a
     * loaded track. Unlike the app's file picker this does not fly the camera to frame the track:
     * the caller is placing the camera itself. Returns how many paths the document held.
     */
    public int loadGpx(final String gpxXml) {
        final int[] loaded = new int[1];
        onRenderThread(() -> loaded[0] = getC().gpxManager.loadFromXml(gpxXml, false));
        return loaded[0];
    }

    /** Reads a GPX file and {@link #loadGpx(String) loads} it. */
    public int loadGpx(File gpxFile) throws java.io.IOException {
        return loadGpx(new String(java.nio.file.Files.readAllBytes(gpxFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The camera's vertical field of view in degrees. The app's map uses 30 - a long lens that
     * crops to what is straight ahead; its GPX tour widens to 62 so the mountains either side of
     * the track stay in shot. Set once, it holds: the app only re-fits the lens on a resize.
     */
    public PeakNavRenderer setFieldOfView(final float degrees) {
        if (!(degrees > 1f && degrees < 170f)) {
            throw new IllegalArgumentException("field of view must be between 1 and 170 degrees");
        }
        onRenderThread(() -> {
            mapApp.mapViewerScreen.cam.fieldOfView = degrees;
            mapApp.mapViewerScreen.cam.update();
        });
        return this;
    }

    public PeakNavRenderer setAltitudeMeters(final double metersAboveSeaLevel) {
        onRenderThread(() ->
                mapApp.mapViewerScreen.setCameraAltitudeMeters(metersAboveSeaLevel));
        return this;
    }

    /**
     * Starts circling a world point, keeping it in view - the app's orbit mode, the
     * alternative to flying to a clicked point.
     *
     * <p>The camera's current distance from the point becomes the radius and its current
     * height is held, so the orbit begins from wherever the view already is.
     */
    public PeakNavRenderer startOrbit(final Vector3 centre) {
        onRenderThread(() -> mapApp.mapViewerScreen.startOrbit(centre));
        return this;
    }

    /** Stops an orbit; the camera stays where it has got to. */
    public PeakNavRenderer stopOrbit() {
        onRenderThread(() -> mapApp.mapViewerScreen.stopOrbit());
        return this;
    }

    /** The equatorial grid over the sky. */
    public PeakNavRenderer setSkyGrid(final boolean on) {
        onRenderThread(() -> P.setSkyGrid(on));
        return this;
    }

    /** Names drawn beside the brightest stars. Off makes for a clean sky in a render. */
    public PeakNavRenderer setStarNames(final boolean on) {
        onRenderThread(() -> P.setSkyStarNames(on));
        return this;
    }

    /**
     * Every caption on the sky at once - constellation names, star names, and the names of the
     * Sun, Moon and planets. One call for a sky with nothing written on it, rather than having
     * to find and switch off each kind separately.
     */
    public PeakNavRenderer setSkyLabels(final boolean on) {
        onRenderThread(() -> P.setSkyLabels(on));
        return this;
    }

    /**
     * The date-and-time pill that appears whenever the sky is frozen at a chosen instant. It
     * marks a sky that is not the live one, which is useful on screen and unwanted in a render.
     */
    public PeakNavRenderer setSkyTimeLabel(final boolean on) {
        onRenderThread(() -> P.setSkyTimeLabel(on));
        return this;
    }

    /**
     * Stops camera movement from triggering the label-visibility passes. With updates
     * held, moving or turning the camera reprojects the labels already chosen but never
     * reshuffles WHICH labels show - no overlap pass, no relevance re-sort. A video
     * renders one frame after another with the camera moving every time, and left to
     * its own triggers the label set reshuffles several times a second of output, which
     * plays back as flicker. Call {@link #refreshLabels()} on the cadence the video can
     * tolerate - every half second, say.
     */
    public PeakNavRenderer setLabelAutoUpdate(final boolean automatic) {
        onRenderThread(() ->
                getC().dataRetrieveThreadManager.setLabelUpdatesHeld(!automatic));
        return this;
    }

    /**
     * Blocks until the last {@link #moveTo} has fully landed - target elevation
     * measured, camera placed, current coordinates equal to the target's. This is the
     * one wait a video frame may NEVER skip: every other signal (tiles, imagery,
     * settle) affects how the frame looks, but this one decides WHERE the frame is
     * taken from. Returns false if the timeout passes first.
     */
    public boolean awaitTargetReached(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] reached = new boolean[1];
            onRenderThread(() -> reached[0] = getC().L.isTargetReached());
            if (reached[0]) {
                return true;
            }
            sleep(10);
        }
        return false;
    }

    /** One full label pass now - overlap, occlusion, sort - hold or no hold. Also the
     * moment the sky learns the observer moved: held moves skip sky invalidation (a
     * full rebuild per frame ran the render loop at ~1 fps), so the accumulated
     * drift - fractions of a degree per half second of video - is paid here. */
    public PeakNavRenderer refreshLabels() {
        requestLabelPass();
        return this;
    }

    /** Asks for a label pass; returns its sequence number (see {@link #refreshLabelsAndWait}). */
    private long requestLabelPass() {
        final long[] sequence = new long[1];
        onRenderThread(() -> {
            getC().skyModel.invalidate();
            sequence[0] = getC().dataRetrieveThreadManager.forceLabelUpdateNow();
        });
        return sequence[0];
    }

    /**
     * Runs a label pass and blocks until it has COMPLETED - published its final list -
     * or the timeout passes. A refresh frame captured while the pass was mid-flight
     * (it sleeps internally between its stages) showed whatever half-state the pass
     * had reached; frames between refreshes then held that half-state for the whole
     * window. Waiting for completion makes the refresh frame the frame where the new
     * label set arrives whole.
     *
     * <p>It waits for THIS pass, by sequence number, not for the next completion. Passes
     * queue: the one requested at the start of a chunk, from the boot's ground-level
     * camera, was often still running when the first frame - now at altitude - asked
     * for its own, and "any completion" returned on the stale one. The frame was then
     * captured with that pass's labels and depth map, and the area plates it should have
     * carried arrived a refresh late: fifteen blank frames at the head of a chunk.
     */
    public boolean refreshLabelsAndWait(long timeoutMillis) {
        long sequence = requestLabelPass();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (com.peaknav.utils.ResourceStats.labelVisibilityCompletedSequence.get()
                    >= sequence) {
                return true;
            }
            sleep(20);
        }
        return false;
    }

    /**
     * Places the CAMERA at a coordinate without touching the target - the video
     * pipeline's per-frame move. The target is the world's anchor: tiles bake their
     * vertices against its latitude's cosine, and they re-bake once it moves ~200 m.
     * Moving the target with the camera (as moveTo does) made the world re-anchor
     * every couple of frames: between re-bakes the camera's x used a fresher
     * reference than the terrain's, and the whole view sawtoothed left-right by the
     * growing-then-snapping mismatch. With the target frozen per chunk and only the
     * camera moving - in the FROZEN reference - camera, terrain and labels stay in
     * one consistent frame for the whole chunk. A chunk's travel (a few km) is far
     * inside the tile coverage streamed at boot, so nothing is lost by not
     * re-anchoring.
     */
    public PeakNavRenderer placeCamera(final double latitude, final double longitude) {
        onRenderThread(() -> {
            mapApp.mapViewerScreen.moveCameraAction.clearSteps();
            com.badlogic.gdx.graphics.Camera cam = mapApp.mapViewerScreen.cam;
            cam.position.x = (float) com.peaknav.utils.Units.convertLonitsToLatits(
                    longitude, getC().L.getTargetLatitude());
            cam.position.y = (float) latitude;
        });
        return this;
    }

    /** The quiet condition, term by term - which one is refusing. */
    public String quietDiagnostics() {
        final String[] out = new String[1];
        onRenderThread(() -> {
            long since = System.currentTimeMillis()
                    - getAppState().getLastAnyMapTileUpdateTime();
            out[0] = "\"loading\":" + getAppState().isLoadingMapData()
                    + ",\"satWork\":" + getAppState().getPendingSatelliteWork()
                    + ",\"drawWork\":" + com.peaknav.viewer.tiles.PixmapLayers.pendingDrawWork()
                    + ",\"roadWork\":" + getC().tileManager.tileRenderer.pendingRoadWork()
                    + ",\"roadIdle\":" + getC().tileManager.tileRenderer.isRoadRendererIdle()
                    + ",\"textureJoins\":"
                    + getC().mapTilePixmapToTexturesHandler.pendingTextureJoins()
                    + ",\"areasLoaded\":" + getC().areaRegistry.isNeighbourhoodLoaded(
                            getC().L.getTargetLatitude(), getC().L.getTargetLongitude())
                    + ",\"poiRetrieves\":" + com.peaknav.utils.ResourceStats.poiRetrievesInFlight.get()
                    + ",\"sinceTileMs\":" + since;
        });
        return out[0];
    }

    /**
     * Chooses the satellite imagery source by id, from the ones the app knows.
     *
     * <p>The ids are those in {@code imagery_providers.json} plus any custom source added
     * with {@link #setCustomSatelliteProvider}. Changing this re-fetches the underlay for
     * every tile, so follow it with a wait before capturing.
     *
     * @return true if a provider with that id exists and was selected
     */
    public boolean setSatelliteProvider(final String id) {
        final boolean[] found = new boolean[1];
        onRenderThread(() -> {
            com.peaknav.viewer.imgmapprovider.SatelliteImageProvider provider =
                    P.getSatelliteProviderRegistry().findById(id);
            if (provider == null) {
                return;
            }
            P.setUnderlayImageProvider(provider);
            found[0] = true;
            // The layer is only redrawn when something asks; without this the change shows
            // up whenever the next tile update happens to come round, which from a script
            // looks like the setting having been ignored.
            getC().tileManager.startAerialAndDataRenderExecutors();
        });
        return found[0];
    }

    /**
     * Adds a source of the caller's own and selects it - any XYZ tile server, named by a
     * URL template with {@code {x}}, {@code {y}} and {@code {z}} placeholders.
     *
     * <p>This is how a renderer is pointed at something the app has never heard of:
     * OpenStreetMap's own tiles, a national mapping agency, an internal server. The
     * template is validated the same way the interface validates one typed by hand, so a
     * malformed one is refused rather than silently drawing nothing.
     *
     * @return null when the source was added and selected, otherwise why it was refused
     */
    public String setCustomSatelliteProvider(final String urlTemplate, final String name,
                                             final String attribution) {
        final String[] problem = new String[1];
        onRenderThread(() -> {
            com.peaknav.viewer.imgmapprovider.SatelliteProviderRegistry registry =
                    P.getSatelliteProviderRegistry();
            String rejected = registry.addCustomProvider(urlTemplate, name, attribution);
            // "Already added" is not a failure to select it: find the existing one by its
            // template and use that, so calling this twice is harmless.
            com.peaknav.viewer.imgmapprovider.SatelliteImageProvider chosen = null;
            for (com.peaknav.viewer.imgmapprovider.SatelliteImageProvider provider
                    : registry.getAllProviders()) {
                if (urlTemplate != null && urlTemplate.trim().equals(provider.getUrlTemplate())) {
                    chosen = provider;
                    break;
                }
            }
            if (chosen == null) {
                problem[0] = rejected == null ? "the provider was not added" : rejected;
                return;
            }
            P.setUnderlayImageProvider(chosen);
            getC().tileManager.startAerialAndDataRenderExecutors();
        });
        return problem[0];
    }

    /** The imagery sources this renderer can be pointed at, as "id\tname" lines. */
    public String satelliteProviders() {
        final StringBuilder out = new StringBuilder();
        onRenderThread(() -> {
            for (com.peaknav.viewer.imgmapprovider.SatelliteImageProvider provider
                    : P.getSatelliteProviderRegistry().getAllProviders()) {
                out.append(provider.getId()).append('\t').append(provider.getProviderName()).append('\n');
            }
        });
        return out.toString();
    }

    /**
     * Road and path drawing still owed to the tiles around the viewer - zero once the map is
     * complete. {@link #awaitTilesLoaded} does not report quiet while this is outstanding, which
     * is what stops a frame being captured as bare terrain.
     */
    public int pendingRoadWork() {
        final int[] out = new int[1];
        onRenderThread(() -> out[0] = getC().tileManager.tileRenderer.pendingRoadWork());
        return out[0];
    }

    /**
     * Whether every area name around the viewer has been read from disk. They arrive a few tiles
     * per frame, so this is false for the first frames after a move until {@link #awaitTilesLoaded}
     * has let them catch up.
     */
    public boolean areaNamesLoaded() {
        final boolean[] out = new boolean[1];
        onRenderThread(() -> out[0] = getC().areaRegistry.isNeighbourhoodLoaded(
                getC().L.getTargetLatitude(), getC().L.getTargetLongitude()));
        return out[0];
    }

    /** Flag tallies over the displayable labels - which gate is blanking them and why. */
    public String labelDiagnostics() {
        final int[] c = new int[5];   // displayable, byMountains, behind, byLabel, visible
        onRenderThread(() -> getC().O.iterateOverDisplayablePois(poi -> {
            c[0]++;
            if (poi.drawLabel.hiddenByMountains) c[1]++;
            if (poi.drawLabel.hiddenBehind) c[2]++;
            if (poi.drawLabel.hiddenByLabel) c[3]++;
            if (poi.drawLabel.isVisible()) c[4]++;
        }));
        return "\"displayable\":" + c[0] + ",\"hiddenByMountains\":" + c[1]
                + ",\"hiddenBehind\":" + c[2] + ",\"hiddenByLabel\":" + c[3]
                + ",\"drawn\":" + c[4];
    }

    /**
     * The objects the app has loaded around the viewer - peaks, places, alpine huts, pistes
     * and area names (mountain ranges, islands, lakes, towns) - as a JSON document.
     *
     * <p>{@code scope} picks the POI set: {@code "displayable"} is the list the app chose as
     * candidates for labelling in the current view (the same list the label pass works from),
     * {@code "all"} is every POI loaded from the tiles in memory, which can run to tens of
     * thousands. Area names are always the ones the registry holds around the target; with
     * {@code drawnOnly} only objects whose label was actually drawn in the last frame are
     * returned, for POIs and areas alike.
     *
     * <p>Screen positions are in pixels of the rendered frame, origin top-left and y down -
     * the convention of the image {@link #capture} writes, so a caller can annotate a frame
     * directly. POIs carry {@code screen}, the anchor point their label line points at
     * (which may lie outside the frame when the summit is above the view), and
     * {@code label}, the bottom-left of the drawn name, always on the frame; areas carry
     * the label plate rectangle.
     */
    public String objectsJson(final String scope, final boolean drawnOnly) {
        final boolean all;
        if ("all".equals(scope)) {
            all = true;
        } else if (scope == null || "displayable".equals(scope)) {
            all = false;
        } else {
            throw new IllegalArgumentException("scope wants displayable or all, not " + scope);
        }
        final JsonValue root = new JsonValue(JsonValue.ValueType.object);
        final JsonValue list = new JsonValue(JsonValue.ValueType.array);
        root.addChild("objects", list);
        onRenderThread(() -> {
            final com.peaknav.viewer.controller.ObjectManager.RunOnPoiObject add = poi -> {
                DrawLabel label = poi.drawLabel;
                // The app's own test passes labels it then draws outside the frame (a
                // summit far above the view gets a name the GPU clips away). Those are
                // not on the picture, so they are not "drawn" here.
                boolean offFrame = label != null && (label.getScreenPoiX() < 0
                        || label.getScreenPoiX() > width
                        || label.getScreenLabelY() < 0 || label.getScreenLabelY() > height);
                boolean drawn = label != null && label.isVisible() && !offFrame;
                if (drawnOnly && !drawn) {
                    return;
                }
                JsonValue o = new JsonValue(JsonValue.ValueType.object);
                o.addChild("kind", new JsonValue(
                        poi.drawLabelCategory.name().toLowerCase(Locale.ROOT)));
                o.addChild("name", new JsonValue(poi.name));
                o.addChild("lat", new JsonValue(poi.lat));
                o.addChild("lon", new JsonValue(poi.lon));
                o.addChild("elevation_m", new JsonValue(poi.elevation));
                if (poi.prominence > 0) {
                    o.addChild("prominence_m", new JsonValue(poi.prominence));
                }
                JsonValue tags = new JsonValue(JsonValue.ValueType.object);
                for (java.util.Map.Entry<String, String> e : poi.getTags().entrySet()) {
                    tags.addChild(e.getKey(), new JsonValue(e.getValue()));
                }
                o.addChild("tags", tags);
                o.addChild("drawn", new JsonValue(drawn));
                if (label != null) {
                    JsonValue screen = new JsonValue(JsonValue.ValueType.object);
                    screen.addChild("x", new JsonValue(label.getScreenPoiX()));
                    screen.addChild("y", new JsonValue(height - label.getScreenPoiY()));
                    o.addChild("screen", screen);
                    // Where the name is drawn. It is pushed up from the anchor, and pulled
                    // back down onto the frame when the anchor is above it - a peak higher
                    // than the view keeps its label, with the line running off the top.
                    JsonValue text = new JsonValue(JsonValue.ValueType.object);
                    text.addChild("x", new JsonValue(label.getScreenPoiX()));
                    text.addChild("y", new JsonValue(height - label.getScreenLabelY()));
                    o.addChild("label", text);
                    JsonValue hidden = new JsonValue(JsonValue.ValueType.object);
                    hidden.addChild("by_mountains", new JsonValue(label.hiddenByMountains));
                    hidden.addChild("behind", new JsonValue(label.hiddenBehind));
                    hidden.addChild("by_label", new JsonValue(label.hiddenByLabel));
                    hidden.addChild("off_frame", new JsonValue(offFrame));
                    o.addChild("hidden", hidden);
                }
                list.addChild(o);
            };
            if (all) {
                getC().O.iterateOverAllLists(add);
            } else {
                getC().O.iterateOverDisplayablePois(add);
            }

            // Area names. Everything the registry holds around the target, flagged with
            // whether the label pass drew it; the drawn ones carry their plate on screen.
            java.util.Map<MapArea, LabelRenderer.DrawnArea> drawnAreas =
                    new java.util.IdentityHashMap<>();
            java.util.Set<MapArea> candidates = java.util.Collections.emptySet();
            MapViewerScreen viewer = MapViewerSingleton.getViewerInstance();
            LabelRenderer labelRenderer = viewer == null ? null : viewer.labelRenderer;
            if (labelRenderer != null) {
                for (LabelRenderer.DrawnArea d : labelRenderer.drawnAreas()) {
                    drawnAreas.put(d.area, d);
                }
                candidates = labelRenderer.candidateAreas();
            }
            java.util.List<MapArea> areas = getC().areaRegistry.getAreasNear(
                    getC().L.getTargetLatitude(), getC().L.getTargetLongitude());
            for (MapArea area : areas) {
                LabelRenderer.DrawnArea d = drawnAreas.get(area);
                if (drawnOnly && d == null) {
                    continue;
                }
                JsonValue o = new JsonValue(JsonValue.ValueType.object);
                o.addChild("kind", new JsonValue("area"));
                o.addChild("type", new JsonValue(area.type));
                o.addChild("name", new JsonValue(area.name));
                o.addChild("lat", new JsonValue(area.lat));
                o.addChild("lon", new JsonValue(area.lon));
                o.addChild("elevation_m", new JsonValue(area.peakMeters));
                o.addChild("semi_major_km", new JsonValue(area.semiMajorKm));
                o.addChild("semi_minor_km", new JsonValue(area.semiMinorKm));
                o.addChild("rotation_deg", new JsonValue(area.rotationDeg));
                o.addChild("visible_range_km", new JsonValue(area.visibleRangeKm));
                o.addChild("population", new JsonValue(area.population));
                // Where in the label pipeline it stands: a candidate survived the geometric
                // culls (range, frustum, summit in view, terrain) and only the de-overlap
                // round or a held selection kept it off the picture. The terrain verdict is
                // the one cached across decision frames, so it reads true for areas not yet
                // tested as well as for those actually behind nearer mountains.
                o.addChild("candidate", new JsonValue(candidates.contains(area)));
                JsonValue hidden = new JsonValue(JsonValue.ValueType.object);
                hidden.addChild("by_mountains", new JsonValue(labelRenderer != null
                        && labelRenderer.isAreaHiddenByTerrain(area)));
                o.addChild("hidden", hidden);
                o.addChild("drawn", new JsonValue(d != null));
                if (d != null) {
                    JsonValue screen = new JsonValue(JsonValue.ValueType.object);
                    screen.addChild("x", new JsonValue(d.x));
                    screen.addChild("y", new JsonValue(height - d.y - d.height));
                    screen.addChild("width", new JsonValue(d.width));
                    screen.addChild("height", new JsonValue(d.height));
                    o.addChild("screen", screen);
                }
                list.addChild(o);
            }
        });
        return root.toJson(JsonWriter.OutputType.json);
    }

    /** How many label-visibility passes have run; what tests watch to prove the hold holds. */
    public long labelVisibilityRuns() {
        return com.peaknav.utils.ResourceStats.labelVisibilityRuns.get();
    }

    /**
     * Freezes the sky and the sunlight at one instant, given as UTC milliseconds since the
     * epoch. Without it the Sun is placed from the wall clock, which makes a render depend on
     * when it was run: a long camera path split over several app boots picks up a different Sun
     * in each, and a run interrupted for hours comes back with the light from another part of
     * the day. In a still that is merely unreproducible; in a video it is a seam.
     */
    public PeakNavRenderer setSkyTimeMillis(final long utcMillis) {
        onRenderThread(() -> {
            com.peaknav.sky.SkyModel sky = getC().skyModel;
            if (sky != null) {
                sky.setCustomTimeMillis(utcMillis);
            }
        });
        return this;
    }

    /** The ecliptic, the lane the Sun, Moon and planets travel along. */
    public PeakNavRenderer setSkyEcliptic(final boolean on) {
        onRenderThread(() -> P.setSkyEcliptic(on));
        return this;
    }

    /** Whether the camera is currently circling a point. */
    public boolean isOrbiting() {
        final boolean[] out = new boolean[1];
        onRenderThread(() -> out[0] = mapApp.mapViewerScreen.isOrbiting());
        return out[0];
    }

    /** Where the camera is, in the app's world frame. */
    public Vector3 cameraPosition() {
        final Vector3 out = new Vector3();
        onRenderThread(() -> out.set(mapApp.mapViewerScreen.cam.position));
        return out;
    }

    /** The viewpoint's current altitude in metres above sea level. */
    public double altitudeMeters() {
        final double[] out = new double[1];
        onRenderThread(() -> out[0] = mapApp.mapViewerScreen.getCameraAltitudeMeters());
        return out[0];
    }

    /**
     * Forces the file format of later captures, instead of taking it from the file name.
     *
     * @param format {@code "png"}, {@code "jpg"}, or null to go by the file's extension
     */
    public PeakNavRenderer setImageFormat(String format) {
        snapshotWriter.setFormat(format);
        return this;
    }

    /** The viewpoint's current height in metres above the terrain beneath it. */
    public double elevationMeters() {
        final double[] out = new double[1];
        onRenderThread(() -> out[0] = mapApp.mapViewerScreen.getCameraElevationMeters());
        return out[0];
    }

    /**
     * Downloads whatever map data this location is missing, and waits for it.
     *
     * <p>Covers a region never fetched and one fetched only in part: the app checks elevation,
     * POI and highway coverage separately, and downloads only what is absent, so calling this
     * for an area that is already complete does nothing and returns immediately.
     *
     * <p>This deliberately calls {@code CheckMissingData.downloadMissingData} rather than
     * going through the path the app itself uses when the camera lands on empty terrain.
     * That path calls {@code NativeScreenCaller.askForDownloadScreen}, which on desktop puts
     * up a modal {@code JOptionPane}. With no one to answer it, the dialog would sit on the
     * virtual display forever and the download would never start - a silent hang.
     *
     * @return true if the download finished (or nothing was needed), false on timeout
     */
    public boolean downloadMissingData(final double latitude, final double longitude,
                                       long timeoutMillis) {
        // Ask first whether anything is missing at all. When the area is already complete
        // the download never starts, so the progress ratio below never moves off 0 - and
        // waiting for it to reach 1 would burn the entire timeout doing nothing. (That is
        // exactly what happened: locations with data on disk sat here for the full
        // download budget before rendering a single frame.)
        final boolean[] missing = new boolean[1];
        onRenderThread(() -> missing[0] =
                getC().checkMissingData.checkMissingDataForCoord(latitude, longitude));
        if (!missing[0]) {
            return true;
        }

        // The download workers honour the privacy consent for downloading files and skip
        // every fetch without it (PeakNavDownloadManager). In the apps that consent comes
        // from a dialog; here the caller invoking this method IS the explicit request, so
        // grant it - otherwise a fresh machine would "download" nothing, silently.
        onRenderThread(() -> P.setCollectDownloadInfo(true));

        onRenderThread(() -> getC().checkMissingData.downloadMissingData(latitude, longitude));

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final float[] progress = new float[1];
            final boolean[] loading = new boolean[1];
            onRenderThread(() -> {
                progress[0] = getAppState().getMapDataDownloadProgressRatio();
                loading[0] = getAppState().isLoadingMapData();
            });
            // The progress bar hides itself above 0.999 (see setMapDataDownloadProgressRatio),
            // which is the app's own definition of "finished".
            if (!loading[0] && progress[0] > 0.999f) {
                // Downloaded tiles still have to be read back in and re-textured.
                return awaitTilesLoaded(Math.max(0, deadline - System.currentTimeMillis()));
            }
            sleep(500);
        }
        return false;
    }

    /**
     * How many UI prompts the app tried to raise and were intercepted. Always safe to be
     * non-zero - the point is that they were suppressed rather than shown - but a test can
     * assert that a normal render provokes none at all.
     */
    public int suppressedPrompts() {
        return snapshotWriter.suppressedPromptCount();
    }

    /**
     * Resizes the hidden window. The app reacts exactly as to a user resize: camera
     * aspect, stage viewport and depth pixmaps all follow.
     */
    public PeakNavRenderer resizeWindow(final int newWidth, final int newHeight) {
        onRenderThread(() -> com.badlogic.gdx.Gdx.graphics.setWindowedMode(newWidth, newHeight));
        return this;
    }

    /** The camera's current direction, for tests and for callers that want to check aim. */
    public Vector3 cameraDirection() {
        final Vector3 out = new Vector3();
        onRenderThread(() -> out.set(mapApp.mapViewerScreen.cam.direction));
        return out;
    }

    /** The camera's current up vector. */
    public Vector3 cameraUp() {
        final Vector3 out = new Vector3();
        onRenderThread(() -> out.set(mapApp.mapViewerScreen.cam.up));
        return out;
    }

    /** Which label categories are currently on, read back from the preferences. */
    public java.util.Map<Label, Boolean> labelStates() {
        final java.util.EnumMap<Label, Boolean> states = new java.util.EnumMap<>(Label.class);
        onRenderThread(() -> {
            states.put(Label.PEAKS, P.isPeakVisible());
            states.put(Label.PLACE_NAMES, P.isVisiblePlaceNames());
            states.put(Label.CITIES, P.isVisibleCities());
            states.put(Label.MOUNTAIN_RANGES, P.isVisibleMountainRanges());
            states.put(Label.ISLANDS, P.isVisibleIslands());
            states.put(Label.LAKES, P.isVisibleLakes());
            states.put(Label.ALPINE_HUTS, P.isVisibleAlpineHuts());
            states.put(Label.ROADS, P.isViewerLayerVisibleBaseRoads());
            states.put(Label.PISTES, P.getPisteVisible());
            states.put(Label.NAVIGATION, P.getLayerVisibleNavigation());
        });
        return states;
    }

    /** The label categories the viewer can show, each backed by its own preference. */
    public enum Label {
        PEAKS, PLACE_NAMES, CITIES, MOUNTAIN_RANGES, ISLANDS, LAKES, ALPINE_HUTS,
        ROADS, PISTES, NAVIGATION
    }

    /** Turns one category of label on or off. */
    public PeakNavRenderer setLabel(final Label label, final boolean visible) {
        onRenderThread(() -> {
            switch (label) {
                case PEAKS:           P.setPeakVisible(visible); break;
                case PLACE_NAMES:     P.setVisiblePlaceNames(visible); break;
                case CITIES:          P.setVisibleCities(visible); break;
                case MOUNTAIN_RANGES: P.setVisibleMountainRanges(visible); break;
                case ISLANDS:         P.setVisibleIslands(visible); break;
                case LAKES:           P.setVisibleLakes(visible); break;
                case ALPINE_HUTS:     P.setVisibleAlpineHuts(visible); break;
                case ROADS:           P.setViewerLayerVisibleBaseRoads(visible); break;
                case PISTES:          P.setPisteVisible(visible); break;
                case NAVIGATION:      P.setLayerVisibleNavigation(visible); break;
                default: throw new IllegalArgumentException("unhandled label: " + label);
            }
        });
        return this;
    }

    /** Turns every label category off, as a base to switch individual ones back on. */
    public PeakNavRenderer clearLabels() {
        for (Label label : Label.values()) {
            setLabel(label, false);
        }
        return this;
    }

    /** Draws the sky (horizon, sun, stars) or leaves it plain. */
    public PeakNavRenderer setSky(final boolean enabled) {
        onRenderThread(() -> P.setSkyView(enabled));
        return this;
    }

    /**
     * Sky mode: 0 follows local time at the viewpoint, 1 forces day, 2 forces night.
     *
     * <p>Worth setting for any image meant to be looked at: the app lights the terrain
     * from the real sun position, so a viewpoint whose local time is night renders an
     * almost black frame - correct, and useless as a picture.
     */
    public PeakNavRenderer setSkyMode(final int mode) {
        onRenderThread(() -> P.setSkyMode(mode));
        return this;
    }

    /** Constellation lines, when the sky is on. */
    public PeakNavRenderer setConstellations(final boolean enabled) {
        onRenderThread(() -> P.setSkyConstellations(enabled));
        return this;
    }

    /** Shades the terrain by sun position. */
    public PeakNavRenderer setSunShading(final boolean enabled) {
        onRenderThread(() -> P.setSunShading(enabled));
        return this;
    }

    /** The compass strip across the horizon - worth turning off for a clean image. */
    public PeakNavRenderer setHorizonCompass(final boolean enabled) {
        onRenderThread(() -> P.setHorizonCompass(enabled));
        return this;
    }

    /** The coordinates pill at the bottom of the screen. */
    public PeakNavRenderer setShowCoordinates(final boolean enabled) {
        onRenderThread(() -> P.setShowCoordinates(enabled));
        return this;
    }

    /** The compass rose in the top-right corner. */
    public PeakNavRenderer setCornerCompass(final boolean enabled) {
        onRenderThread(() -> P.setCornerCompass(enabled));
        return this;
    }

    /**
     * Lets the render loop run for a fixed time. A plain dwell - prefer
     * {@link #awaitTilesLoaded} unless a specific duration is what is wanted.
     */
    public PeakNavRenderer settle(long millis) {
        sleep(millis);
        return this;
    }

    /**
     * Waits until the view has stopped filling in, or until {@code timeoutMillis} elapses.
     *
     * <p>Tiles stream in on background threads, so "loaded" is not a single event. Three
     * things are watched, in combination:
     *
     * <ul>
     *   <li>the app's own {@code isLoadingMapData} flag;
     *   <li>{@code PeakNavAppState.getPendingSatelliteWork()} — a counter bracketing each
     *       satellite tile fetch-and-draw (see {@code TileRendererRunnerSatellite}), so
     *       "all satellite imagery has arrived" is a fact, not an inference;
     *   <li>the road/path layer: tiles near enough to be given one but not yet rasterised
     *       ({@code TileRenderer.pendingRoadWork()}), and pixmaps drawn but not yet joined
     *       into GL textures. A tile is {@code IS_DRAWN} once its elevation mesh is ready,
     *       with the paths still to come, so without these the first frames of a video came
     *       out as bare terrain - the map's paths arrived a second after the shutter.
     * </ul>
     *
     * @return true if the view finished, false if it timed out with work still arriving
     */
    /**
     * The longest the wait will hold once the TERRAIN is finished, for the map layers that come
     * after it (roads and paths, and pixmaps not yet joined into textures).
     *
     * <p>This is a ceiling, not a target. Road tiles are rasterised one at a time and a full
     * neighbourhood of them takes minutes - far longer than any frame should wait - so waiting
     * for "all of them" turned every boot into its own two-minute timeout. Six seconds buys the
     * near tiles, which are the ones filling the picture (they are rasterised nearest-first; see
     * {@code TileRenderer.drawArea}), and then the render goes ahead regardless.
     *
     * <p>The principle is general: every signal this wait consults may fail to arrive, so none of
     * them may gate a frame without a bound. Anything not settled by the ceiling is reported and
     * skipped, never waited on indefinitely.
     */
    private static final long LAYER_PATIENCE_MILLIS = 6_000;

    /**
     * How long road work may sit at the same outstanding count, with the road renderer idle,
     * before the wait stops expecting it. Roads that are still coming make this counter fall;
     * a count that will never fall (a pass dropped when its executor was restarted, a tile with
     * no map data behind it) must not hold every frame hostage - that is the failure mode that
     * once turned a whole render into one frame per minute.
     */
    private static final long ROAD_STALL_MILLIS = 4_000;

    /**
     * A road backlog already waited out once. Frames come in their hundreds per boot; having
     * concluded that these tiles are not getting their layer, paying the patience again on every
     * single one of them would be the same stall by instalments. Cleared by {@link #moveTo},
     * because somewhere new deserves a fresh answer.
     */
    private int roadBacklogGivenUp = -1;

    public boolean awaitTilesLoaded(long timeoutMillis, final long quietMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int lastRoadPending = Integer.MIN_VALUE;
        long roadProgressMillis = System.currentTimeMillis();
        long terrainQuietSince = 0;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] quiet = new boolean[1];
            final int[] roadPending = new int[1];
            final boolean[] roadIdle = new boolean[1];
            final int[] textureJoins = new int[1];
            final boolean[] areasLoaded = new boolean[1];
            onRenderThread(() -> {
                // Readiness is asked of the TILES, not inferred from a clock. The
                // old test - "no tile-update timestamp for N ms" - was jammed four
                // separate ways (a POI-retrieve storm, a paced renderer dripping
                // updates, a welding queue re-marking the clock every frame), each
                // time freezing whole runs at the timeout. Timestamps say something
                // happened; the states say whether the picture is finished, which
                // is the actual question.
                boolean allSettled = true;
                for (com.peaknav.viewer.tiles.MapTile tile
                        : getC().mapTileStorage.getMapTiles()) {
                    if (tile.isDisposed()) {
                        continue;
                    }
                    com.peaknav.viewer.tiles.MapTile.MapTileState st = tile.getMapTileState();
                    if (st != com.peaknav.viewer.tiles.MapTile.MapTileState.IS_DRAWN
                            && st != com.peaknav.viewer.tiles.MapTile.MapTileState
                                    .ELEVATION_DATA_NOT_FOUND) {
                        allSettled = false;
                        break;
                    }
                }
                // Roads and paths are rasterised AFTER a tile is drawn, on their own
                // executor, and become visible only once the render thread joins the
                // pixmap into a texture. Neither shows up in the tile states, so the
                // early frames of a video - the ones taken while a freshly booted
                // renderer is still rasterising - came out as bare terrain with no
                // paths on it. Both are now part of "the picture is finished".
                roadPending[0] = getC().tileManager.tileRenderer.pendingRoadWork();
                roadIdle[0] = getC().tileManager.tileRenderer.isRoadRendererIdle();
                textureJoins[0] = getC().mapTilePixmapToTexturesHandler.pendingTextureJoins();
                // Area names (ranges, islands, lakes, towns) are read from disk a few tiles per
                // frame, so the set is still growing over the opening frames of a video unless
                // something waits for it.
                areasLoaded[0] = getC().areaRegistry.isNeighbourhoodLoaded(
                        getC().L.getTargetLatitude(), getC().L.getTargetLongitude());
                // The TERRAIN's own readiness. The map layers on top of it are judged
                // separately below, under a bound, so that a layer which never arrives
                // cannot hold the frame.
                quiet[0] = allSettled
                        && !getAppState().isLoadingMapData()
                        && getAppState().getPendingSatelliteWork() == 0
                        && com.peaknav.viewer.tiles.PixmapLayers.pendingDrawWork() == 0
                        // The POIs of a new position arrive tile by tile on their own
                        // thread; a label pass run before the last tile lands publishes
                        // a partial set, and a frame captured on it lacks its peaks.
                        && com.peaknav.utils.ResourceStats.poiRetrievesInFlight.get() == 0;
            });
            long nowMillis = System.currentTimeMillis();
            if (!quiet[0]) {
                // Terrain still arriving: the map layers are not even the question yet.
                terrainQuietSince = 0;
                sleep(100);
                continue;
            }
            if (terrainQuietSince == 0) {
                terrainQuietSince = nowMillis;
            }
            // Only a SHRINKING backlog counts as progress. A count that grows is new tiles
            // arriving, which must not buy the wait another lease - the ceiling below is what
            // bounds it either way.
            if (roadPending[0] < lastRoadPending) {
                roadProgressMillis = nowMillis;
            }
            lastRoadPending = roadPending[0];

            long held = nowMillis - terrainQuietSince;
            // Roads either finished, or established as not coming: idle and unchanged for a
            // while, or a backlog this renderer has already waited out once.
            boolean roadsSettled = roadPending[0] == 0
                    || (roadIdle[0] && nowMillis - roadProgressMillis >= ROAD_STALL_MILLIS)
                    || (roadIdle[0] && roadPending[0] == roadBacklogGivenUp);
            boolean layersReady = roadsSettled && textureJoins[0] == 0 && areasLoaded[0];
            if (layersReady || held >= LAYER_PATIENCE_MILLIS) {
                boolean shortOfRoads = roadPending[0] > 0;
                if ((shortOfRoads || !areasLoaded[0])
                        && (roadPending[0] != roadBacklogGivenUp || !areasLoaded[0])) {
                    roadBacklogGivenUp = roadPending[0];
                    System.out.println("[headless] going ahead after " + held + " ms: "
                            + roadPending[0] + " tiles without their road layer"
                            + (areasLoaded[0] ? "" : ", area names still loading")
                            + " - some may be missing from these frames");
                    System.out.flush();
                } else if (held > 200) {
                    // What the frame gained by waiting: exactly how early it used to be taken.
                    System.out.println("[headless] held the frame " + held
                            + " ms for the map layers after the terrain went quiet");
                    System.out.flush();
                }
                // One breath for the pixmap-to-texture joiner, which runs per render
                // frame: the last drawn tile's texture join may be one frame behind
                // its state flip.
                sleep(Math.min(quietMillis, 150));
                return true;
            }
            sleep(100);
        }
        return false;
    }

    /** {@link #awaitTilesLoaded(long, long)} with a 2 second quiet period. */
    public boolean awaitTilesLoaded(long timeoutMillis) {
        return awaitTilesLoaded(timeoutMillis, 2_000);
    }

    /**
     * Waits until at least one label has actually been drawn, or until the timeout.
     *
     * <p>Labels lag the terrain: POIs are fetched, laid out on background threads and only
     * then drawn, so a capture taken the moment the tiles settle can miss them.
     * {@code LabelRenderer} publishes how many labels each frame drew
     * ({@code PeakNavAppState.getVisibleLabelCount()}); this returns once that is non-zero.
     * Only meaningful when at least one label category is switched on — with all labels off
     * it would wait the full timeout, so call it only when labels are expected.
     *
     * @return true once a label is on screen, false on timeout
     */
    public boolean awaitLabelsRendered(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final int[] count = new int[1];
            onRenderThread(() -> count[0] = getAppState().getVisibleLabelCount());
            if (count[0] > 0) {
                return true;
            }
            sleep(250);
        }
        return false;
    }

    /**
     * Takes a snapshot and writes it to {@code output}.
     *
     * <p>This is the app's own snapshot, the one behind the share button:
     * {@link com.peaknav.viewer.screens.MapViewerScreen#takeSnapshot()} raises a flag that
     * the render loop picks up at exactly the right moment - after the terrain and labels
     * are drawn but <em>before</em> {@code stage.draw()} paints the interface. So the image
     * has no camera, search or menu buttons in it, and it handles the photo-backdrop crop.
     * Reading the framebuffer directly from here instead would capture the whole window,
     * buttons and all.
     *
     * <p>The app hands the finished Pixmap to {@code NativeScreenCaller.shareSnapshot} on a
     * background thread; {@link FileSnapshotWriter} takes it from there and saves it with
     * the platform's own {@code UtilsOSDep.savePixmapAsPng/Jpg}. Nothing about the image is
     * re-implemented here.
     *
     * @param output written as JPEG when the name ends in .jpg or .jpeg, otherwise PNG
     */
    public PeakNavRenderer capture(final File output) {
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        CountDownLatch written = snapshotWriter.expect(output);
        onRenderThread(mapApp.mapViewerScreen::takeSnapshot);
        try {
            if (!written.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("no snapshot arrived within "
                        + ACTION_TIMEOUT_SECONDS + "s for " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a snapshot", e);
        }
        snapshotWriter.rethrowFailure();
        return this;
    }

    /** Runs {@code action} on the render thread and waits for it, surfacing any failure. */
    /**
     * Runs an action on the render thread and waits for it - for tests that drive parts of
     * the app which assume that thread (the input controller, the camera).
     */
    public void runOnRenderThread(final Runnable action) {
        onRenderThread(action);
    }

    private void onRenderThread(final Runnable action) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        Gdx.app.postRunnable(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("render thread did not respond within "
                        + ACTION_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the render thread", e);
        }
        Throwable failure = thrown.get();
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure != null) {
            throw new IllegalStateException("render thread action failed", failure);
        }
    }

    @Override
    public void close() {
        if (Gdx.app != null) {
            Gdx.app.exit();
        }
        try {
            appThread.join(TimeUnit.SECONDS.toMillis(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
