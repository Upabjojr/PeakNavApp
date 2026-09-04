package com.peaknav.compatibility;

import com.peaknav.database.MapSqlite;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.FileMover;
import com.peaknav.utils.PeakNavCaches;
import com.peaknav.utils.PeakNavLogger;
import com.peaknav.utils.UtilsOSDep;

import org.mapsforge.core.graphics.GraphicFactory;

public interface LoadFactory {
    MapSqlite getMapSqlite();
    GraphicFactory getGraphicFactory();
    NativeScreenCaller getNativeScreenCaller();
    PeakNavLogger getPeakNavLogger();
    PeakNavCaches getCaches();
    UtilsOSDep getUtilsOSDep();
    FileMover getFileMover();
    NotificationManagerPeakNav getPeakNavNotificationManager();

    CrashLogger getCrashLogger(Throwable throwable, String fileNamePrefix);

    /** Whether this is a debug build: development-only controls are shown only then. */
    boolean isDebugBuild();

    /**
     * Where debug builds save photo/pose samples for the skyline dataset
     * (see {@code PhotoSkylineAligner.saveSample}). On Android the app's private files
     * directory, which {@code adb shell run-as <package>} can read from a debug build.
     */
    java.io.File getDebugSamplesDir();
}
