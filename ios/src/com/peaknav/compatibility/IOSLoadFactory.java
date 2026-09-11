package com.peaknav.compatibility;

import com.peaknav.database.MapSqlite;
import com.peaknav.database.MapSqliteIOS;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.CrashLoggerIOS;
import com.peaknav.utils.FileMover;
import com.peaknav.utils.IOSCaches;
import com.peaknav.utils.IOSLogger;
import com.peaknav.utils.PeakNavCaches;
import com.peaknav.utils.PeakNavLogger;
import com.peaknav.utils.RenameFileMover;
import com.peaknav.utils.UtilsOSDep;
import com.peaknav.utils.UtilsOSIOS;

/**
 * Everything platform-shaped that the shared core asks iOS for.
 *
 * <p>The same role {@code DesktopLoadFactory} and the Android one play: core is written
 * against these interfaces and knows nothing about the platform behind them.
 *
 * <p>Logging, caches, file writing, crash reports, notifications, the catalogue of which
 * tiles have been downloaded ({@link MapSqliteIOS}: RoboVM has neither the JDBC driver
 * desktop uses nor Android's SQLite, so it binds the system {@code libsqlite3}) and the whole
 * {@link NativeScreenCallerIOS} surface. Nothing here draws the map: the roads and trails are
 * rasterized in plain Java by {@code com.peaknav.roads} and styled by the terrain shader, the
 * same on every platform.
 */
public class IOSLoadFactory implements LoadFactory {

    private final NativeScreenCallerIOS nativeScreenCaller = new NativeScreenCallerIOS();
    private final IOSLogger logger = new IOSLogger();
    private final IOSCaches caches = new IOSCaches();
    private final UtilsOSIOS utilsOS = new UtilsOSIOS();
    // RenameFileMover, never NioFileMover: the nio one names java.nio.file classes RoboVM
    // does not have, and must stay unreachable from this module.
    private final FileMover fileMover = new RenameFileMover();
    private final NotificationManagerIOS notifications = new NotificationManagerIOS();

    private final MapSqliteIOS mapSqlite = new MapSqliteIOS();

    @Override
    public MapSqlite getMapSqlite() {
        return mapSqlite;
    }

    @Override
    public NativeScreenCaller getNativeScreenCaller() {
        return nativeScreenCaller;
    }

    @Override
    public PeakNavLogger getPeakNavLogger() {
        return logger;
    }

    @Override
    public PeakNavCaches getCaches() {
        return caches;
    }

    @Override
    public UtilsOSDep getUtilsOSDep() {
        return utilsOS;
    }

    @Override
    public FileMover getFileMover() {
        return fileMover;
    }

    @Override
    public NotificationManagerPeakNav getPeakNavNotificationManager() {
        return notifications;
    }

    @Override
    public CrashLogger getCrashLogger(Throwable throwable, String fileNamePrefix) {
        return new CrashLoggerIOS(throwable, fileNamePrefix);
    }

    @Override
    public boolean isDebugBuild() {
        return false;
    }

    @Override
    public java.io.File getDebugSamplesDir() {
        return com.badlogic.gdx.Gdx.files.local("skyline_samples").file();
    }
}
