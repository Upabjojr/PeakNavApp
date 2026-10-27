package com.peaknav.viewer;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.setC;

import com.badlogic.gdx.Game;

import com.badlogic.gdx.Gdx;
import com.peaknav.compatibility.LoadFactory;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.ui.ClickCallback;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.screens.IntroScreen;
import com.peaknav.viewer.screens.MapViewerScreen;

public class MapApp extends Game {
    private boolean paused = false;

    public final MapViewerScreen mapViewerScreen;
    public final IntroScreen introScreen;

    public NativeScreenCaller nativeScreenCaller;
    // public Runnable showOnceCallback;
    public final LoadFactory loadFactory;
    private boolean firstOccurrence = true;

    public MapApp(LoadFactory loadFactory) {
        PeakNavUtils.initializeCache();
        this.loadFactory = loadFactory;

        // TODO: make these the default access methods for loadFactory and C:
        PeakNavUtils.setLoadFactory(loadFactory);

        PeakNavUtils.setLogger(loadFactory.getPeakNavLogger());
        PeakNavUtils.setCaches(loadFactory.getCaches());

        setC(new MapController(loadFactory));

        nativeScreenCaller = loadFactory.getNativeScreenCaller();

        mapViewerScreen = new MapViewerScreen(this);
        introScreen = new IntroScreen(this);
    }

    /**
     * Whether {@link #create} has run: libGDX is up and the screens can be used. The screens
     * themselves exist from the constructor on, well before that - on Android, before the
     * activity's onCreate has even returned.
     */
    private volatile boolean created;

    public boolean isCreated() {
        return created;
    }

    /** Whether the start-up is spread over the first frames; see {@link #setStartUpInSteps}. */
    private boolean startUpInSteps;
    /** The step of the start-up the next frame takes; render thread only. */
    private int startUpStep;
    /** Set by the worker decoding the fonts' pages once it is done. */
    private volatile boolean fontsPrepared;

    /**
     * Has {@link #create} return at once and the start-up run over the frames after it, a short
     * step on each, with the fonts' pages decoded meanwhile on a worker. For Android, called
     * before libGDX starts. There the main thread waits in {@code GLSurfaceView.surfaceChanged}
     * until the render thread has finished a frame, whenever the surface changes - the window
     * laid out again, the phone turned - and the start-up in one piece (database, shaders,
     * fonts, both screens) made that frame long enough on a slower phone for Play to report an
     * ANR (1.3.3, Oppo Reno13 A). Moving it whole to a later frame does not help: the emulator
     * turned during it showed the same wait.
     *
     * <p>Not for the desktop and the headless renderer, which post work to the render thread as
     * soon as its loop is running and expect the screens to exist by then.
     */
    public void setStartUpInSteps(boolean inSteps) {
        startUpInSteps = inSteps;
    }

    @Override
    public void create() {
        if (!startUpInSteps) {
            if (mapViewerScreen.needToBeShown) {
                mapViewerScreen.showOnce();
            }
            openFirstScreen();
        }
    }

    /**
     * Takes the start-up's next step; a frame each, drawn blank, until the first screen opens.
     * The steps are the parts of {@link MapViewerScreen#showOnce}, in its order.
     */
    private void startUpStep() {
        boolean showOnce = mapViewerScreen.needToBeShown;
        switch (startUpStep) {
            case 0:
                // The blank first frame lets the main thread go at once; the fonts' pages are
                // decoded meanwhile, which is most of what loading them costs.
                if (showOnce) {
                    getC().styleSingleton.updateMinSize();
                    Thread fonts = new Thread(() -> {
                        try {
                            getC().styleSingleton.prepareBakedFonts();
                        } finally {
                            fontsPrepared = true;
                        }
                    }, "fonts-prepare");
                    fonts.setDaemon(true);
                    fonts.start();
                }
                break;
            case 1:
                if (showOnce) {
                    mapViewerScreen.showOnceData();
                }
                break;
            case 2:
                if (showOnce) {
                    if (!fontsPrepared) {
                        drawStartUpFrame();   // blank frames until it is
                        return;
                    }
                    mapViewerScreen.showOnceGraphicsBase();
                }
                break;
            case 3:
                if (showOnce) {
                    mapViewerScreen.showOnceWidgets();
                }
                break;
            case 4:
                if (showOnce) {
                    mapViewerScreen.showOnceFinish();
                }
                break;
            default:
                openFirstScreen();
                return;
        }
        startUpStep++;
        drawStartUpFrame();
    }

    private static void drawStartUpFrame() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
        // The next step's frame, should the frames have been switched to on-request.
        Gdx.graphics.requestRendering();
    }

    private void openFirstScreen() {

        // Straight to the map when there is data to show. The welcome screen used to come first
        // whatever the case, and gave way to the map only on a later frame (setMapDataDownloaded
        // below hands its switch to the render thread), so every start flashed it for a moment.
        boolean downloaded = getC().mapSqlite.existDownloadedTiles();
        setScreen(downloaded ? mapViewerScreen : introScreen);

        getAppState().setMapDataDownloaded(downloaded);
        created = true;
    }

    @Override
    public void render () {
        if (startUpInSteps && !created) {
            startUpStep();
            if (!created) {
                return;
            }
        }
        if (screen != null) {
            try {
                screen.render(Gdx.graphics.getDeltaTime());
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                if (firstOccurrence) {
                    // Keep a trace of the first failure so a field report is diagnosable.
                    firstOccurrence = false;
                    try {
                        loadFactory.getCrashLogger(throwable, "render").logToFile();
                    } catch (Throwable ignored) {
                    }
                }
                // End whatever batch the failed frame left open. Without this, one transient
                // render error poisoned every following frame (begin() throws "already drawing"),
                // nothing was ever drawn again, and the app sat on a blank screen until killed.
                try {
                    if (screen == mapViewerScreen) {
                        mapViewerScreen.recoverFromRenderError();
                    } else if (screen == introScreen) {
                        introScreen.recoverFromRenderError();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void pause() {
        paused = true;
        super.pause();
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public void resume() {
        paused = false;
        super.resume();
    }

}
