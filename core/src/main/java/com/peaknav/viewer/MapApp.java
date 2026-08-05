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
