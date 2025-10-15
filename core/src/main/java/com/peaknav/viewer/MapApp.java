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
        ElevationUtils.initializeElevationTileCache();

        setC(new MapController(loadFactory));

        nativeScreenCaller = loadFactory.getNativeScreenCaller();

        mapViewerScreen = new MapViewerScreen(this);
        introScreen = new IntroScreen(this);
    }

    @Override
    public void create() {
        // if no location has ever been created, ask for one:

        if (mapViewerScreen.needToBeShown) {
            mapViewerScreen.showOnce();
        }

        setScreen(introScreen);

        getAppState().setMapDataDownloaded(getC().mapSqlite.existDownloadedTiles());
    }

    @Override
    public void render () {
        // TODO: this is a temporary throwable-catcher used to detect when the main thread crashes:
        if (screen != null) {
            try {
                screen.render(Gdx.graphics.getDeltaTime());
            } catch (Throwable throwable) {
                if (firstOccurrence) {
                    firstOccurrence = false;
                    // CrashLogger crashLogger = loadFactory.getCrashLogger(throwable, "render");
                    // crashLogger.logToFile();
                }
                throwable.printStackTrace();
                // TODO: reset all renders otherwise .begin() will raise an error as .end()
                // has not been called.
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
