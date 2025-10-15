package com.peaknav.compatibility;

import com.peaknav.database.MapSqlite;
import com.peaknav.utils.CrashLogger;
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
    NotificationManagerPeakNav getPeakNavNotificationManager();

    CrashLogger getCrashLogger(Throwable throwable, String fileNamePrefix);
}
