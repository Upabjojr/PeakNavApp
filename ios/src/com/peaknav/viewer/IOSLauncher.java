package com.peaknav.viewer;

import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.math.Vector3;

import com.peaknav.compatibility.LoadFactory;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.compatibility.NotificationManagerPeakNav;
import com.peaknav.database.MapSqlite;
import com.peaknav.gesture.PositionChangeListener;
import com.peaknav.network.PeakNavS3Downloader;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.PeakNavCaches;
import com.peaknav.utils.PeakNavLogger;
import com.peaknav.utils.UtilsOSDep;

public class IOSLauncher extends IOSApplication.Delegate {
    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        MapApp mapApp = new MapApp(new LoadFactory() {
            @Override
            public MapSqlite getMapSqlite() {
                return null;
            }

            @Override
            public PeakNavS3Downloader getElevationTileDownloader() {
                return null;
            }

            @Override
            public org.mapsforge.core.graphics.GraphicFactory getGraphicFactory() {
                return null;
            }

            @Override
            public NativeScreenCaller getNativeScreenCaller() {
                return null;
            }

            @Override
            public void startWizard() {

            }

            @Override
            public PeakNavLogger getPeakNavLogger() {
                return null;
            }

            @Override
            public PeakNavCaches getCaches() {
                return null;
            }

            @Override
            public UtilsOSDep getUtilsOSDep() {
                return null;
            }

            @Override
            public NotificationManagerPeakNav getPeakNavNotificationManager() {
                return null;
            }

            @Override
            public CrashLogger getCrashLogger(Throwable throwable, String fileNamePrefix) {
                return null;
            }
        });
        mapApp.mapViewerScreen.addPositionChangeListener(new PositionChangeListener() {
            @Override
            public void onCameraPositionChanged(Vector3 position) {

            }

            @Override
            public void onZoomChanged(float fieldOfView) {

            }

            @Override
            public void onCameraDirectionChanged(Vector3 direction, Vector3 up) {

            }
        });
        return new IOSApplication(mapApp, config);
    }

    public static void main(String[] argv) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(argv, null, IOSLauncher.class);
        pool.close();
    }
}