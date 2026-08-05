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
                Math.max(getNumOfCpuCores() / 2, 1),
                "execDraw");
        graphicFactory = C.mapsforgeConnector.getGraphicFactory();

        displayModel = new DisplayModel();
        displayModel.setFixedTileSize(256);
        pbfMapDataStore = C.mapDataManager.getMultiMapDataStore();
    }

    public void initialize() {
        if (graphicFactory == null) {
            // No mapsforge graphics backend on this platform, so there is nothing to
            // rasterise roads and paths WITH. The rest of the app - terrain, satellite
            // imagery, labels, sky - does not go through mapsforge at all, so it runs
            // perfectly well without this; the map simply has no path layer on it. iOS is
            // in this position until a CoreGraphics backend exists (see IOSLoadFactory).
            // Everything below would throw on a null factory, and everything that USES
            // what it builds is guarded by databaseRenderer being null.
            return;
        }
        renderThemes = new RenderThemes();

        TileCache tileCache = new FileSystemTileCache(
                100,
                Gdx.files.external("tile_cache").file(),
                graphicFactory);

        TileBasedLabelStore tileBasedLabelStore = new TileBasedLabelStore(1024);
        databaseRenderer = new DatabaseRenderer(pbfMapDataStore, graphicFactory, tileCache, tileBasedLabelStore, true, false, null);
    }

    /**
     * Roads draped on the 3D terrain looked "pixelated" because the render theme's stroke widths are
     * absolute pixels tuned for a 256px tile, so on our {@code SUPERSAMPLE*256}px tiles they came out
     * hairline-thin (a ~1.6px road on a 2048px tile), which foreshortening then broke up. Mapsforge
     * has no runtime stroke multiplier (unlike text's {@code textScale}), but the theme-level
     * {@code base-stroke-width} attribute multiplies every line stroke — so the templates carry a
     * {@code ${BASE_STROKE_WIDTH}} placeholder we fill, scaled to the supersample, to restore
     * standard-map thickness. {@code ROAD_STROKE_BOLDNESS} is the dial: 1.0 ≈ a standard map here.
     */
    private static final float ROAD_STROKE_BOLDNESS = 1.0f;

    /**
     * Label border (white halo) prominence. Mapsforge scales label <em>text</em> by {@code textScale}
     * but leaves the halo {@code stroke-width} unscaled (Caption/PathText {@code scaleStrokeWidth} is a
     * no-op), so on our supersampled tiles the border became a hairline around big glyphs — hence the
     * poor legibility. The template's {@code ${TEXT_HALO_MAIN/MINOR}} placeholders are filled with the
     * halo widths scaled by the supersample (× this boldness) so the border stays a constant fraction
     * (~15%) of the glyph height. Raise for a heavier outline.
     */
    private static final float TEXT_HALO_BOLDNESS = 1.5f;

    // Base halo widths (in the original theme) for the two label tiers; scaled up when the template
    // is filled. Main = road/place names, minor = the small track labels.
    private static final float MAIN_HALO_BASE = 2.0f;
    private static final float MINOR_HALO_BASE = 1.0f;

    public XmlRenderTheme getMapsforgeXmlRenderTheme(String assetName) {
        FileHandle asset = Gdx.files.internal(assetName);
        float supersample = TileRendererRunner.ROAD_TILE_SUPERSAMPLE;
        float haloScale = supersample * TEXT_HALO_BOLDNESS;

        java.util.Map<String, String> vars = new java.util.HashMap<>();
        vars.put("BASE_STROKE_WIDTH", String.valueOf(supersample * ROAD_STROKE_BOLDNESS));
        vars.put("TEXT_HALO_MAIN", String.valueOf(MAIN_HALO_BASE * haloScale));
        vars.put("TEXT_HALO_MINOR", String.valueOf(MINOR_HALO_BASE * haloScale));

        String xml = fillTemplate(asset.readString("UTF-8"), vars);
        java.io.InputStream themeStream = new java.io.ByteArrayInputStream(
                xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        XmlRenderTheme xmlRenderTheme = new StreamRenderTheme("", themeStream, new XmlRenderThemeMenuCallback() {
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

    /**
     * Substitutes {@code ${KEY}} placeholders in a render-theme template with the given values, and
     * fails fast if any placeholder was left unfilled (a typo'd token would otherwise surface as an
     * obscure Mapsforge parse error).
     */
    private static String fillTemplate(String template, java.util.Map<String, String> vars) {
        String result = template;
        for (java.util.Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        int unfilled = result.indexOf("${");
        if (unfilled >= 0) {
            throw new IllegalStateException("Unfilled render-theme placeholder near: "
                    + result.substring(unfilled, Math.min(unfilled + 40, result.length())));
        }
        return result;
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

    /**
     * How much road/path drawing is still outstanding: tiles near enough to be given a road layer
     * that have not been rasterised yet, plus whatever the renderer thread still holds.
     *
     * <p>A tile reaches {@code IS_DRAWN} as soon as its elevation mesh is ready — the roads are
     * rasterised afterwards, on a separate low-priority executor, and only then handed over as a
     * pixmap. So "every tile is drawn" is NOT "the map is finished", and anything that captures an
     * image on that signal alone gets terrain with no paths on it. The headless renderer's wait
     * asks this as well; see {@code PeakNavRenderer.awaitTilesLoaded}.
     *
     * <p>Counts only what is actually expected: nothing when the roads layer is switched off, and
     * nothing for tiles past {@link TileRendererRunner#ROAD_CUTOFF_DEGREES}, which never get one.
     */
    public int pendingRoadWork() {
        if (databaseRenderer == null)
            return 0;   // nothing can draw roads here, so nothing is outstanding
        if (!P.isPixmapLayerNameVisible(PixmapLayerName.BASE_ROADS))
            return 0;
        int pending = 0;
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            if (mapTile.isDisposed())
                continue;
            if (!TileRendererRunner.roadsExpectedFor(mapTile.tile))
                continue;
            if (!mapTile.isLayerDrawn(PixmapLayerName.BASE_ROADS))
                pending++;
        }
        return pending;
    }

    /** True while the road renderer has nothing queued and nothing in hand. */
    public boolean isRoadRendererIdle() {
        return tileRendererExecutor.getQueue().isEmpty()
                && tileRendererExecutor.getActiveCount() == 0;
    }

    public void drawArea(PixmapLayerName pixmapLayerName) {
        if (pixmapLayerName != PixmapLayerName.BASE_ROADS)
            return;
        if (databaseRenderer == null)
            return;   // no graphics backend: there is no road layer on this platform

        // Nearest tile first. These are rasterised one at a time, and a full neighbourhood takes
        // far longer than anyone waits for a frame - so the ORDER decides what a picture taken
        // meanwhile contains. Storage order scattered the work all round the compass, leaving the
        // foreground bare while tiles behind the camera were drawn; nearest-first fills the view
        // from the ground up, which is also the order the frame needs them in.
        java.util.List<MapTile> waiting = new java.util.ArrayList<>();
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            if (!mapTile.isLayerDrawn(pixmapLayerName))
                waiting.add(mapTile);
        }
        final LatLong target = getC().L.getTargetLatLong();
        Collections.sort(waiting, (a, b) -> Double.compare(
                LatLongUtils.distance(a.getImpWhiteTileIndex(), target),
                LatLongUtils.distance(b.getImpWhiteTileIndex(), target)));

        for (MapTile mapTile : waiting) {
            TileRendererRunner renderer = new TileRendererRunnerMapsforge(
                    this, renderThemes, mapTile, pixmapLayerName);
            renderer.setPriority(Thread.MIN_PRIORITY);
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
