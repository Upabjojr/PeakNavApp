package com.peaknav.viewer;

import com.peaknav.compatibility.LoadFactory;
import com.peaknav.viewer.screens.MapViewerScreen;

public class MapViewerSingleton {

    protected volatile static MapApp mapApp;

    public static LoadFactory getLoadFactory() {
        return loadFactory;
    }

    protected static LoadFactory loadFactory;

    public static MapApp getAppInstance() {
        if (mapApp == null) {
            synchronized (MapViewerSingleton.class) {
                if (mapApp == null) {
                    mapApp = new MapApp(loadFactory);
                }
            }
        }
        return mapApp;
    }

    /** Whether the app exists yet. Unlike getAppInstance, never creates it. */
    public static boolean hasAppInstance() {
        return mapApp != null;
    }

    public static void setAppInstance(MapApp mapApp) {
        synchronized (MapViewerSingleton.class) {
            MapViewerSingleton.mapApp = mapApp;
        }
    }

    public static MapViewerScreen getViewerInstance() {
        return getAppInstance().mapViewerScreen;
    }
}
