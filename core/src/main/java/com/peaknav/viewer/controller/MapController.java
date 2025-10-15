package com.peaknav.viewer.controller;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import com.ibm.icu.text.Transliterator;
import com.peaknav.compatibility.LoadFactory;
import com.peaknav.database.CheckMissingData;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.database.MapSqlite;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.elevation.ElevationImageProviderManager;
import com.peaknav.network.OnlineSearch;
import com.peaknav.network.PeakNavHttpCompressDownloader;
import com.peaknav.utils.CacheDirManager;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.utils.PeakNavThreadFactory;
import com.peaknav.viewer.DataRetrieveThreadManager;
import com.peaknav.viewer.I18NWrapper;
import com.peaknav.viewer.MapDataManager;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.render_tiles.MapTilePixmapToTexturesHandler;
import com.peaknav.viewer.screens.MapViewerScreen;
import com.peaknav.viewer.map_data.MapsforgeConnector;
import com.peaknav.viewer.tiles.MapTileWelder;
import com.peaknav.viewer.tiles.TileManager;
import com.peaknav.viewer.spatial.Collisions;
import com.peaknav.viewer.spatial.Visibility;
import com.peaknav.viewer.widgets.StyleSingleton;
import com.peaknav.viewer.widgets.WidgetGetter;
import com.peaknav.viewer.widgets.WidgetTextures;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.GraphicFactory;

public class MapController {

    public final ElevationImageProviderManager elevationImageProviderManager;
    public final MapSqlite mapSqlite;

    public final Visibility visibility;
    public final Collisions collisions;

    public DataRetrieveThreadManager dataRetrieveThreadManager;
    public final MapsforgeConnector mapsforgeConnector;
    public final MapDataManager mapDataManager;
    public CheckMissingData checkMissingData;
    public final ThreadPoolExecutor executorEleLoad = (ThreadPoolExecutor)
            Executors.newFixedThreadPool(
                    2, new PeakNavThreadFactory("eleLoad"));

    public final PeakNavThreadExecutor executorGeneric = new PeakNavThreadExecutor(1, "excGnrc");

    public final CurrentLocation L = new CurrentLocation();
    public final TileManager tileManager;

    public final ObjectManager O;

    public MapTileStorage mapTileStorage = new MapTileStorage();

    public final MissingDataDownloader missingDataDownloader;
    public final StyleSingleton styleSingleton = new StyleSingleton();
    public final WidgetTextures widgetTextures = new WidgetTextures();

    public volatile I18NWrapper i18n;
    public StaticData staticData = new StaticData();
    public final Transliterator transliterator = Transliterator.getInstance("Any-Latin; Latin-ASCII");
    private static int numOfCpuCores;

    public final MapTilePixmapToTexturesHandler mapTilePixmapToTexturesHandler = new MapTilePixmapToTexturesHandler();
    public final Queue<MapTileWelder> weldingQueue = new LinkedBlockingQueue<>();
    public final OnlineSearch onlineSearch = new OnlineSearch();
    public WidgetGetter widgetGetter = null;
    public CacheDirManager cacheDirManager;
    public LuceneGeonameSearch luceneGeonameSearch;

    public MapController(LoadFactory loadFactory) {
        GraphicFactory graphicFactory = loadFactory.getGraphicFactory();
        this.mapsforgeConnector = new MapsforgeConnector() {
            @Override
            public Bitmap getBitmap() {
                return graphicFactory.createBitmap(8*256, 8*256, true);
            }

            @Override
            public GraphicFactory getGraphicFactory() {
                return graphicFactory;
            }
        };
        mapSqlite = loadFactory.getMapSqlite();

        PeakNavHttpCompressDownloader eleDown = new PeakNavHttpCompressDownloader();

        missingDataDownloader = new MissingDataDownloader(
                eleDown,
                mapSqlite);
        mapDataManager = new MapDataManager();
        tileManager = new TileManager(this);

        visibility = new Visibility(this);
        collisions = new Collisions(this);

        O = new ObjectManager();

        executorEleLoad.setThreadFactory(new PeakNavThreadFactory("threadpool-ele-load"));

        elevationImageProviderManager = new ElevationImageProviderManager();
    }

    public Future<?> submitExecutorGeneric(Runnable runnable) {
        return executorGeneric.submit(runnable);
    }

    public void redactAll() {
        dataRetrieveThreadManager.triggerReadData();
    }

    public MapViewerScreen getMapViewerScreen() {
        return MapViewerSingleton.getViewerInstance();
    }

    public static int getNumOfCpuCores() {
        return numOfCpuCores;
    }

    public static void setNumOfCpuCores(int numOfCpuCores) {
        MapController.numOfCpuCores = numOfCpuCores;
    }

    public void createI18NifNeeded() {
        if (i18n == null) {
            i18n = new I18NWrapper();
        }
    }
}
