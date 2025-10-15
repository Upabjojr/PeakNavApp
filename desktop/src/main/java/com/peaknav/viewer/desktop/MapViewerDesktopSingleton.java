package com.peaknav.viewer.desktop;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.awt.view.MapView;
import org.mapsforge.map.util.MapViewProjection;

import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.nio.file.Path;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;

import com.badlogic.gdx.Gdx;
import com.peaknav.compatibility.LoadFactory;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.compatibility.NativeScreenCallerDesktop;
import com.peaknav.compatibility.NotificationManagerPeakNav;
import com.peaknav.database.MapSqlite;
import com.peaknav.database.MapSqliteDesktop;
import com.peaknav.ui.ClickCallback;
import com.peaknav.utils.CrashLogger;
import com.peaknav.utils.CrashLoggerDesktop;
import com.peaknav.utils.DesktopLogger;
import com.peaknav.utils.PeakNavCaches;
import com.peaknav.utils.PeakNavLogger;
import com.peaknav.utils.UtilsOSDep;
import com.peaknav.utils.UtilsOSDesktop;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.map_data.GetMapsforgeMapView;

public class MapViewerDesktopSingleton extends MapViewerSingleton {

    public static void initializeDesktopGraphicFactory() {

        loadFactory = new LoadFactory() {
            private UtilsOSDep utilsOSDep;

            @Override
            public MapSqlite getMapSqlite() {
                return  new MapSqliteDesktop();
            }

            @Override
            public GraphicFactory getGraphicFactory() {
                return new AwtGraphicFactory();
            }

            private final NativeScreenCallerDesktop screenCaller = new NativeScreenCallerDesktop();

            @Override
            public NativeScreenCaller getNativeScreenCaller() {
                return screenCaller;
            }

            @Override
            public PeakNavLogger getPeakNavLogger() {
                return new DesktopLogger();
            }

            @Override
            public PeakNavCaches getCaches() {
                return new PeakNavCaches() {
                    private Path tempDir;

                    @Override
                    public File getCacheDir() {
                        /*
                        if (tempDir == null) {
                            try {
                                tempDir = Files.createTempDirectory("tempPeakNav");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        };
                        return tempDir.toFile();
                         */
                        File cacheDir = new File(Gdx.files.getExternalStoragePath(), "peaknav_cache");
                        if (!cacheDir.exists())
                            cacheDir.mkdir();
                        return cacheDir;
                    }
                };
            }

            @Override
            public synchronized UtilsOSDep getUtilsOSDep() {
                if (utilsOSDep == null)
                    utilsOSDep = new UtilsOSDesktop();
                return utilsOSDep;
            }

            @Override
            public NotificationManagerPeakNav getPeakNavNotificationManager() {
                return NotificationManagerDesktop.getInstance();
            }

            @Override
            public CrashLogger getCrashLogger(Throwable throwable, String fileNamePrefix) {
                return new CrashLoggerDesktop(throwable, fileNamePrefix);
            }
        };
    }

}
