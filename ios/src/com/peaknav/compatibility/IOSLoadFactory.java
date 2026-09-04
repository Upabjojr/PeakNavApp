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
 *   <li><b>{@link #getGraphicFactory()}</b> - the harder one. Mapsforge rasterises the road
 *       and path layer through its own graphics abstraction, and ships backends for AWT and
 *       for Android. There is no iOS backend and there is no way around needing one: the
 *       interface wants a canvas that can stroke dashed paths and lay out text, which
 *       libGDX's Pixmap cannot do. It has to be written against CoreGraphics.
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
     * No mapsforge graphics backend on iOS - deliberately null, and handled.
     *
     * <p>Mapsforge rasterises the road and path layer through its own graphics abstraction
     * and ships backends for AWT and for Android only; an iOS one has to be written against
     * CoreGraphics, and has not been. Rather than block the whole app on it,
     * {@code TileRenderer} treats a null factory as "this platform has no path layer" and
     * skips building the mapsforge machinery. Everything else - the 3D terrain, satellite
     * imagery, peak and area labels, the sky - goes nowhere near mapsforge and works.
     *
     * <p>So iOS is a terrain app until that backend exists. The map is the mountain, without
     * the footpaths drawn on it.
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
