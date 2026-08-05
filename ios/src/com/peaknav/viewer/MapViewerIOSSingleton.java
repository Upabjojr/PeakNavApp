package com.peaknav.viewer;

import com.peaknav.compatibility.IOSLoadFactory;

/**
 * Registers the iOS {@link com.peaknav.compatibility.LoadFactory} with
 * {@link MapViewerSingleton}, the way {@code MapViewerDesktopSingleton} and
 * {@code MapViewerAndroidSingleton} do for their platforms.
 *
 * <p>This exists because iOS was the one launcher that skipped it. {@code IOSLauncher} built
 * its {@code MapApp} with {@code new MapApp(new IOSLoadFactory())} and handed it straight to
 * the libGDX backend, so {@code MapViewerSingleton.mapApp} and its {@code loadFactory} both
 * stayed null. Shared code reaches the running app through
 * {@link MapViewerSingleton#getAppInstance()} - {@code OptionPane} does it while the first
 * screen is being built - and that method, finding null, quietly constructed a *second*
 * {@code MapApp} from the null factory. The app died on launch with a NullPointerException
 * inside {@code MapApp.<init>}, several frames below anything that named iOS.
 *
 * <p>The fix is to go through the singleton rather than around it: seed the factory here, then
 * let {@code getAppInstance()} build the one instance and keep it.
 */
public class MapViewerIOSSingleton extends MapViewerSingleton {

    private MapViewerIOSSingleton() {
    }

    /** Sets the iOS load factory. Call once, before {@link #getAppInstance()}. */
    public static void initializeIOSLoadFactory() {
        loadFactory = new IOSLoadFactory();
    }
}
