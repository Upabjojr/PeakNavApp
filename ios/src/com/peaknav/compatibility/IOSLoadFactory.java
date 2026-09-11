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

import org.mapsforge.core.graphics.GraphicFactory;

/**
 * Everything platform-shaped that the shared core asks iOS for.
 *
 * <p>The same role {@code DesktopLoadFactory} and the Android one play: core is written
 * against these interfaces and knows nothing about the platform behind them.
 *
 * <h2>What works, and what does not, yet</h2>
 *
 * Logging, caches, file writing, crash reports, notifications and the whole
 * {@link NativeScreenCallerIOS} surface are real. Two pieces are not, and they are the two
 * that stand between this module and an app that launches:
 *
 * <ul>
 *   <li><b>{@link #getMapSqlite()}</b> - the catalogue of which tiles have been downloaded.
 *       Desktop uses the JDBC driver and Android the platform's own SQLite; RoboVM has
 *       neither, so iOS needs a small binding to the system {@code libsqlite3} (which is
 *       present on every device) behind the eleven methods of {@link MapSqlite}. This is
 *       ordinary work, just work that has not been done.
 *   <li><b>{@link #getGraphicFactory()}</b> - null, and unused: the roads and trails that
 *       mapsforge once drew onto a per-platform canvas are rasterized in plain Java now
 *       (see {@code com.peaknav.roads}), so iOS draws them like the other platforms.
 * </ul>
 *
 * <p>Both throw rather than returning null, and say what is missing. A null here reappears
 * later as a NullPointerException in a stack that names none of this, which is a much worse
 * way to find out. Until they are built, the honest description of this target is "compiles,
 * does not launch".
 *
 * <p>A first milestone worth considering is terrain-only: the 3D view, satellite imagery,
 * labels and the sky need neither of these two, so a sqlite binding alone would put a
 * running - if roadless - app on a device.
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

    /**
     * No mapsforge graphics backend on iOS - deliberately null, and nothing needs one now.
     *
     * <p>Mapsforge used to rasterise the road and path layer through its own graphics
     * abstraction, with backends for AWT and Android only, so iOS had no paths at all. They
     * are rasterized in plain Java by {@code com.peaknav.roads} now and styled by the terrain
     * shader, the same on every platform, and this factory is left unused.
     */
    @Override
    public GraphicFactory getGraphicFactory() {
        return null;
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
