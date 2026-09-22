package com.peaknav.viewer.mapscreens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.geo.BoundingBox;
import com.peaknav.geo.Tile;
import com.peaknav.network.PeakNavDownloadManager;
import com.peaknav.pbf.PbfLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Choosing the area to download: a map, the block of tiles around the chosen point shaded red,
 * and what is already on the device shaded beneath it.
 *
 * <p>Behaves as the Android chooser did. A tap moves the point; Download area fetches the block
 * around it and closes. The first-run wizard starts on the whole world and asks where the
 * reader is - the fix, when it comes, moves the point there - and has no Back button.
 */
class DownloadAreaScreen extends MapScreens.Base {

    private static final float ZOOM_WORLD = 3.5f;
    private static final float ZOOM_CLOSE = 9.5f;

    /** One download at a time, off the render thread. */
    private static final ExecutorService DOWNLOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "download-area");
        t.setDaemon(true);
        return t;
    });

    /**
     * What is on the device already, one kind of data to a colour, as the legend says. Each
     * outline set a little inside the one before (see SlippyMap.Shading), since the same area is
     * usually downloaded for every kind and the outlines would otherwise lie on top of each other.
     */
    private enum Layer {
        ELEVATION("elev", new Color(1f, 0.82f, 0f, 1f), "Legend_elevation"),
        MAP_DATA(PbfLayer.PBF_HIGHWAYS.name(), new Color(0.15f, 0.45f, 1f, 1f), "Legend_map_data"),
        POI(PbfLayer.PBF_POI.name(), new Color(0.1f, 0.8f, 0.25f, 1f), "Legend_poi"),
        SKI(PbfLayer.PBF_PISTES.name(), new Color(0.9f, 0.25f, 0.9f, 1f), "Legend_ski");

        final String table;
        final Color color;
        final String caption;

        Layer(String table, Color color, String caption) {
            this.table = table;
            this.color = color;
            this.caption = caption;
        }

        /** Ski data is downloaded with every area, but only worth a colour where it is shown. */
        static List<Layer> shown() {
            List<Layer> layers = new ArrayList<>();
            for (Layer layer : values()) {
                if (layer != SKI || P.isSkiSlopesVisible() || P.isLiftsVisible()) {
                    layers.add(layer);
                }
            }
            return layers;
        }
    }

    private static final Color SELECTED = new Color(0.9f, 0.05f, 0.05f, 1f);

    private final boolean goToAfterDownload;
    private final boolean wizard;
    private double pointLat, pointLon;
    /** The first fix moves the point; later ones, or ones after a tap, leave the choice alone. */
    private boolean pointChosen = false;
    private final List<SlippyMap.Shading> downloaded = new ArrayList<>();

    DownloadAreaScreen(double lat, double lon, boolean goToAfterDownload, boolean wizard) {
        this.goToAfterDownload = goToAfterDownload;
        // A first launch is the wizard whoever opened it: there is no position to start from.
        this.wizard = wizard || P.getCoordinatesFirstTime();
        this.pointLat = lat;
        this.pointLon = lon;
        float unit = MapScreens.unit();

        Label title = new Label(s("select_area_to_download"), MapScreens.darkCaption());
        root.add(title).left().pad(0.2f * unit, 0.3f * unit, 0.2f * unit, 0.3f * unit).row();

        SlippyMap map = newMap();
        // The archives are zoom-8 tiles: closer than 9 there is nothing more to choose between.
        map.setZoomRange(1f, 9f);
        map.setCenter(lat, lon);
        map.setZoom(this.wizard ? ZOOM_WORLD : ZOOM_CLOSE);
        map.setTapListener((tapLat, tapLon) -> {
            pointChosen = true;
            setPoint(tapLat, tapLon);
        });

        Table over = new Table();
        over.setTouchable(Touchable.childrenOnly);
        over.add(mapControls(unit, () -> askForFix(true))).expand().top().right().pad(0.2f * unit).row();
        // Bottom left, clear of the imagery's credit in the bottom right corner.
        over.add(legend(unit)).bottom().left().pad(0.2f * unit, 0.2f * unit, 1.1f * unit, 0.2f * unit);

        Stack stack = new Stack();
        stack.add(map);
        stack.add(over);
        root.add(stack).grow().row();

        Table bottom = new Table();
        if (!this.wizard) {
            TextButton back = MapScreens.button(s("Back"));
            back.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    close();
                }
            });
            bottom.add(back).height(unit).minWidth(3f * unit).expandX().left();
        } else {
            bottom.add().expandX();
        }
        TextButton download = MapScreens.button(s("download_selected_area"));
        download.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                downloadPressed();
            }
        });
        bottom.add(download).height(unit).minWidth(3f * unit).expandX().right();
        root.add(bottom).growX().pad(0.2f * unit);

        setPoint(lat, lon);
        loadDownloadedTiles();
    }

    @Override
    void onShown() {
        if (wizard) {
            askForFix(false);
        }
    }

    /**
     * @param pressed the locate button: the fix moves the point whatever was chosen before.
     *                Otherwise (the wizard asking on its own) a point the reader has already
     *                tapped is not taken away from them by a fix arriving late.
     */
    private void askForFix(boolean pressed) {
        getNativeScreenCaller().requestCurrentLocation((longitude, latitude) ->
                // The fix can come on any thread, twice (network, then GPS), and long after
                // the screen has gone - which on Android is what crashed the old chooser.
                Gdx.app.postRunnable(() -> {
                    if (!isShowing() || (latitude == 0 && longitude == 0)) {
                        return;
                    }
                    if (!pressed && pointChosen) {
                        return;
                    }
                    pointChosen = true;
                    setPoint(latitude, longitude);
                    map.setCenter(latitude, longitude);
                    map.setZoom(ZOOM_CLOSE);
                }));
    }

    /** Moves the point and the red block of tiles that Download area would fetch around it. */
    private void setPoint(double lat, double lon) {
        pointLat = lat;
        pointLon = lon;
        map.setMarker(lat, lon);
        PeakNavDownloadManager manager = getC().missingDataDownloader.getPeakNavDownloadManager();
        List<Tile> tiles = manager.getQueueMapData(lat, lon, manager.getZoomPoi(), manager.getRangePoi());
        List<BoundingBox> area = new ArrayList<>();
        area.add(MissingDataDownloader.getBoundingBoxOfTargetTiles(tiles));
        List<SlippyMap.Shading> shadings = new ArrayList<>(downloaded);
        shadings.add(new SlippyMap.Shading(area, withAlpha(SELECTED, 0.12f), SELECTED, 0, 2.5f));
        map.setShadings(shadings);
    }

    /** Shades what is on the device already, read off the render thread and handed back. */
    private void loadDownloadedTiles() {
        getC().submitExecutorGeneric(() -> {
            final List<SlippyMap.Shading> layers = new ArrayList<>();
            List<Layer> shown = Layer.shown();
            for (int i = 0; i < shown.size(); i++) {
                layers.add(layer(shown.get(i), i));
            }
            Gdx.app.postRunnable(() -> {
                if (!isShowing()) {
                    return;
                }
                downloaded.clear();
                downloaded.addAll(layers);
                setPoint(pointLat, pointLon);
            });
        });
    }

    private static SlippyMap.Shading layer(Layer layer, int inset) {
        List<BoundingBox> boxes = new ArrayList<>();
        try {
            for (Tile tile : getC().mapSqlite.getListOfDownloadedTiles(layer.table)) {
                boxes.add(tile.getBoundingBox());
            }
        } catch (RuntimeException e) {
            // No database yet, on a first run: nothing is downloaded, which is what this shows.
        }
        return new SlippyMap.Shading(boxes, withAlpha(layer.color, 0.12f), layer.color, inset, 1f);
    }

    private static Color withAlpha(Color color, float alpha) {
        return new Color(color.r, color.g, color.b, alpha);
    }

    /** What each colour on the map means, on a light plate over the map's corner. */
    private static Table legend(float unit) {
        Table legend = new Table();
        legend.setBackground(getC().widgetTextures.getUniformDrawable(new Color(1f, 1f, 1f, 0.85f)));
        legend.pad(0.15f * unit, 0.25f * unit, 0.15f * unit, 0.25f * unit);
        for (Layer layer : Layer.shown()) {
            legendRow(legend, layer.color, s(layer.caption), unit);
        }
        legendRow(legend, SELECTED, s("Legend_selected"), unit);
        return legend;
    }

    private static void legendRow(Table legend, Color color, String caption, float unit) {
        com.badlogic.gdx.scenes.scene2d.ui.Image swatch =
                new com.badlogic.gdx.scenes.scene2d.ui.Image(getC().widgetTextures.getUniformDrawable(color));
        legend.add(swatch).size(0.35f * unit).padRight(0.2f * unit).padBottom(0.08f * unit);
        Label label = new Label(caption, new Label.LabelStyle(getC().styleSingleton.getBitmapFontVerySmallDark(), Color.BLACK));
        legend.add(label).left().padBottom(0.08f * unit).row();
    }

    private void downloadPressed() {
        if (!P.isCollectDownloadInfo()) {
            // Downloading needs the consent the welcome screen asks for; given here, the
            // download goes ahead at once rather than asking for a second tap.
            getNativeScreenCaller().promptYesNo("", s("Missing_download_info_consent"), () ->
                    getC().submitExecutorGeneric(() -> {
                        P.setCollectDownloadInfo(true);
                        Gdx.app.postRunnable(() -> {
                            if (isShowing()) {
                                startDownload();
                            }
                        });
                    }));
            return;
        }
        startDownload();
    }

    private void startDownload() {
        final double lat = pointLat, lon = pointLon;
        final boolean goTo = goToAfterDownload;
        // The started flag suppresses the missing-data prompt and banner while a download runs.
        // Set here, before the target moves below, or the tile updater finds the new place empty
        // and offers to download what is already downloading. Cleared on every way out below,
        // or the prompt never appears again this session.
        getAppState().setMapDataDownloadStarted(true);
        if (P.getCoordinatesFirstTime() || goTo) {
            // Go there now rather than when the download ends. A first run must, or the app would
            // stay on null island; and a download meant to take the reader somewhere then shows
            // what the iPhone always showed - "Download in progress...", the percentage and the
            // pictures (LabelLoading), which appear only while the target has nothing to draw.
            // Left on the old place, the map there counted as loaded and the download ran with
            // no sign of it but the thin bar at the top.
            getC().L.setCurrentTargetCoords(lat, lon, false);
        }
        DOWNLOADER.execute(() -> {
            MissingDataDownloader downloader = getC().missingDataDownloader;
            try {
                downloader.setCoords(lat, lon);
                downloader.doDownload(goTo);
            } finally {
                getAppState().setMapDataDownloadStarted(false);
            }
            getAppState().setMapDataDownloaded(true);
        });
        close();
    }
}
