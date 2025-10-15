package com.peaknav.singleton;

import android.content.Context;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;

import com.peaknav.compatibility.LoadFactory;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.compatibility.NativeScreenCallerAndroid;
import com.peaknav.compatibility.NotificationManagerAndroid;
import com.peaknav.compatibility.NotificationManagerPeakNav;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.CrashLoggerAndroid;
import com.peaknav.utils.UtilsOSAndroid;
import com.peaknav.utils.UtilsOSDep;
import com.peaknav.views.AndroidLauncher;
import com.peaknav.database.MapSqlite;
import com.peaknav.database.MapSqliteAndroid;
import com.peaknav.utils.AndroidLogger;
import com.peaknav.utils.PeakNavCaches;
import com.peaknav.utils.PeakNavLogger;
import com.peaknav.viewer.MapViewerSingleton;

import java.io.File;

public class MapViewerAndroidSingleton extends MapViewerSingleton {

    public static void initializeAndroidLoadFactory(Context context, final AndroidLauncher mainActivity) {
        AndroidGraphicFactory.createInstance(context);

        loadFactory = new LoadFactory() {
            private NotificationManagerAndroid notificationManager;
            private UtilsOSAndroid utilsOSDep;

            @Override
            public MapSqlite getMapSqlite() {
                return new MapSqliteAndroid(context);
            }

            @Override
            public GraphicFactory getGraphicFactory() {
                return AndroidGraphicFactory.INSTANCE;
            }

            private final NativeScreenCallerAndroid nativeScreenCallerAndroid =
                    new NativeScreenCallerAndroid(context, mainActivity);

            @Override
            public NativeScreenCaller getNativeScreenCaller() {
                return nativeScreenCallerAndroid;
            }

            @Override
            public PeakNavLogger getPeakNavLogger() {
                return new AndroidLogger();
            }

            @Override
            public PeakNavCaches getCaches() {
                return new PeakNavCaches() {
                    @Override
                    public File getCacheDir() {
                        return context.getCacheDir();
                    }
                };
            }

            @Override
            public synchronized UtilsOSDep getUtilsOSDep() {
                if (utilsOSDep == null)
                    utilsOSDep = new UtilsOSAndroid();
                return utilsOSDep;
            }

            @Override
            public NotificationManagerPeakNav getPeakNavNotificationManager() {
                if (notificationManager == null) {
                    synchronized (LoadFactory.class) {
                        if (notificationManager == null)
                            notificationManager = new NotificationManagerAndroid(context.getApplicationContext());
                    }
                };
                return notificationManager;
            }

            @Override
            public CrashLogger getCrashLogger(Throwable throwable, String fileNamePrefix) {
                return new CrashLoggerAndroid(throwable, fileNamePrefix, context.getApplicationContext());
            }
        };
    }
}
