package com.peaknav.viewer.render_tiles;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.controller.MapController.getNumOfCpuCores;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.layer.cache.FileSystemTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.labels.TileBasedLabelStore;
import org.mapsforge.map.layer.renderer.DatabaseRenderer;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.rendertheme.StreamRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback;
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import com.peaknav.pbf.PbfMapDataStore;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.utils.PeakNavThreadFactory;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
import com.peaknav.viewer.tiles.MapTile;

public class TileRenderer {

    final GraphicFactory graphicFactory;
    private RenderThemes renderThemes;
    private SatelliteImageProvider lastSatelliteImageProvider = null;

    public DatabaseRenderer getDatabaseRenderer() {
        return databaseRenderer;
    }

    public class RenderThemes {
        public final RenderThemeFuture renderThemeFutureSkiSlopes;
        public final RenderThemeFuture renderThemeFutureBaseRoads;

        public RenderThemes() {
            XmlRenderTheme xmlRenderThemeBaseRoads = getMapsforgeXmlRenderTheme("mapsforge/base_roads.xml");
            // private final CustomTileRendererLayer tileRendererLayer;
            XmlRenderTheme xmlRenderThemeSkiSlopes = getMapsforgeXmlRenderTheme("mapsforge/ski_slopes.xml");

            ExecutorService executorXmlLoad = Executors.newFixedThreadPool(1, new PeakNavThreadFactory("executorXmlLoad"));

            renderThemeFutureBaseRoads = new RenderThemeFuture(graphicFactory, xmlRenderThemeBaseRoads, displayModel);
            renderThemeFutureSkiSlopes = new RenderThemeFuture(graphicFactory, xmlRenderThemeSkiSlopes, displayModel);

            executorXmlLoad.submit(renderThemeFutureBaseRoads);
            executorXmlLoad.submit(renderThemeFutureSkiSlopes);
        }
    }

    final DisplayModel displayModel;
    private DatabaseRenderer databaseRenderer;
    final PbfMapDataStore pbfMapDataStore;
    public final PeakNavThreadExecutor tileRendererExecutor = new PeakNavThreadExecutor(1, "tileRendererExecutor1");
    public final PeakNavThreadExecutor tileRendererExecutorSat = new PeakNavThreadExecutor(2, "tileRendererExecutor2");
    public final PeakNavThreadExecutor execDraw;

    // private LinkedBlockingQueue<MapTile> updatingQueue = new LinkedBlockingQueue<>();
    // private Set<Integer> tilePixmapSquashQueue = new HashSet<>();

    public TileRenderer(MapController C) {
        execDraw = new PeakNavThreadExecutor(
                Integer.max(getNumOfCpuCores() / 2, 1),
                "execDraw");
        graphicFactory = C.mapsforgeConnector.getGraphicFactory();

        displayModel = new DisplayModel();
        displayModel.setFixedTileSize(256);
        pbfMapDataStore = C.mapDataManager.getMultiMapDataStore();
    }

    public void initialize() {
        renderThemes = new RenderThemes();

        TileCache tileCache = new FileSystemTileCache(
                100,
                Gdx.files.external("tile_cache").file(),
                graphicFactory);

        TileBasedLabelStore tileBasedLabelStore = new TileBasedLabelStore(1024);
        databaseRenderer = new DatabaseRenderer(pbfMapDataStore, graphicFactory, tileCache, tileBasedLabelStore, true, false, null);
    }

    public XmlRenderTheme getMapsforgeXmlRenderTheme(String assetName) {
        FileHandle asset = Gdx.files.internal(assetName);
        XmlRenderTheme xmlRenderTheme = new StreamRenderTheme("", asset.read(), new XmlRenderThemeMenuCallback() {
            @Override
            public Set<String> getCategories(XmlRenderThemeStyleMenu style) {
                Set<String> visibleLayerNames = new HashSet<>();
                // for (String layer : style.getLayers().keySet()) {}
                // visibleLayerNames.addAll(style.getLayers().keySet());
                if (P.getPisteVisible()) {
                    visibleLayerNames.add("piste");
                }
                System.out.println(style);
                return visibleLayerNames;
            }
        });
        xmlRenderTheme.setResourceProvider(new CustomResourceProvider());

        return xmlRenderTheme;
    }

    public List<Tile> getTileZoomScaledPositions(LatLong center, double maxDistance, byte zoomLevel,
                                                 int tileSize) {

        TileAlgorithmScaledRanges algo = new TileAlgorithmScaledRanges(
                (float)center.getLatitude(), (float)center.getLongitude(), zoomLevel, tileSize,
                maxDistance
        );
        List<Tile> tiles = algo.getTiles();

        final LatLong current = getC().L.getTargetLatLong();

        Collections.sort(tiles, (tile1, tile2) -> {
            LatLong center1 = tile1.getBoundingBox().getCenterPoint();
            LatLong center2 = tile2.getBoundingBox().getCenterPoint();
            double d1 = LatLongUtils.distance(center1, current);
            double d2 = LatLongUtils.distance(center2, current);
            return Double.compare(d1, d2);
        });

        return tiles;
    }

    public void drawExecutorStop() {
        tileRendererExecutor.stopLoop();
    }

    public void drawSatelliteLayer() {
        SatelliteImageProvider satelliteImageProvider = P.getUnderlayImageProviderObject();
        // boolean checkDrawn = satelliteImageProvider != lastSatelliteImageProvider;
        tileRendererExecutorSat.stopLoop();
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            /*
            if (checkDrawn && mapTile.isLayerDrawn(PixmapLayerName.UNDERLAY_LAYER)) {
                continue;  // TODO, restore?
            }
             */
            TileRendererRunnerSatellite renderer = new TileRendererRunnerSatellite(
                    this,
                    renderThemes,
                    mapTile,
                    PixmapLayerName.UNDERLAY_LAYER,
                    satelliteImageProvider);
            tileRendererExecutorSat.executeStoppableRunnable(renderer);
        }
        lastSatelliteImageProvider = satelliteImageProvider;
        tileRendererExecutorSat.execute(() -> getC().cacheDirManager.removeOldCacheFiles());
    }

    public void drawArea(PixmapLayerName pixmapLayerName) {

        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            if (mapTile.isLayerDrawn(pixmapLayerName))
                continue;
            TileRendererRunner renderer;
            if (pixmapLayerName == PixmapLayerName.BASE_ROADS) {
                renderer = new TileRendererRunnerMapsforge(
                        this, renderThemes, mapTile, pixmapLayerName);
                renderer.setPriority(Thread.MIN_PRIORITY);
            } else {
                continue;
            }
            tileRendererExecutor.executeStoppableRunnable(renderer);
        }

    }

    /*
    private void drawTileToPNG(Tile tile, TileBitmap tileBitmap, PixmapLayerName pixmapLayerName) {
        String layerName = pixmapLayerName.name();
        String fileName = String.format(Locale.ENGLISH, "output_tiles/%s_%02d_%05d_%05d.png",
                layerName, tile.zoomLevel, tile.tileX, tile.tileY);
        try {
            tileBitmap.compress(new FileOutputStream(fileName));
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    private void drawPixmapToPNG(Pixmap overlayPixmap, PixmapLayerName pixmapLayerName,
                                 int londex, int latdex, int destX, int destY) {
        PixmapIO.writePNG(new FileHandle(String.format(Locale.ENGLISH,
                "output_tiles/pm_%s_%02d_%010d_%010d.png", pixmapLayerName.name(), 14,
                londex*10000 + destX, latdex*10000 + destY)), overlayPixmap);
    }
     */

}
