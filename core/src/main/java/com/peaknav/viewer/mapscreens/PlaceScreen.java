package com.peaknav.viewer.mapscreens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.checkMissingElevationForCoord;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.geo.BoundingBox;
import com.peaknav.geo.Tile;
import com.peaknav.network.NominatimResponse;
import com.peaknav.network.PeakNavDownloadManager;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.utils.CoordinateSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finding a place and downloading it, in one screen: a search box with its results over a map,
 * the map to pick a point on, what is on the device already shaded on it, and the block of
 * tiles a download would fetch around the point shaded red. Go To flies to the point, and
 * where nothing is downloaded there yet offers to download it first; Download fetches the
 * block and stays where it is, unless it was opened to take the reader there.
 *
 * <p>Two screens once, the search and the download chooser, which were the same map with a
 * different half missing: a place found by name could not be downloaded without going back
 * to the map and through a dialog, and the chooser could only be steered by panning.
 *
 * <p>Searching behaves as the Android screen did. Every keystroke asks the offline index, which
 * answers in a millisecond or two; Search (or Enter) also asks the online search, whose answers
 * are added below when they come. Coordinates in any common form are offered as the one result.
 *
 * <p>The first-run wizard starts on the whole world with no point, asks where the reader is,
 * and has only Download - nothing is on the device yet, so Go To would be the same button, and
 * there is nowhere to go back to. Until a point comes, from the fix, a search or a tap,
 * Download says to choose one: it used to fetch the ocean around 0° 0°. With location access
 * refused, the reader is offered an estimate from the internet connection or the search box
 * (see {@code NativeScreenCaller.locationPermissionDenied}).
 */
class PlaceScreen extends MapScreens.Base {

    /** What the screen was opened for, which decides where it starts and what it offers. */
    enum Purpose {
        /** From the menu's search: the cursor in the box, the point on the map's target. */
        SEARCH,
        /** From the menu's download, or a place with no data: the point on the given place. */
        DOWNLOAD,
        /** The first run: the whole world, no point, and the device asked where it is. */
        WIZARD,
        /** From the menu's storage: no point, and the block a point is in deleted, not fetched. */
        DELETE
    }

    private static final float ZOOM_WORLD = 3.5f;
    private static final float ZOOM_CLOSE = 9.5f;
    /** Deleting starts on the country around the target: the blocks there are, at a glance. */
    private static final float ZOOM_REGION = 5f;

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
    private static final Color DANGER = new Color(0.8f, 0.12f, 0.1f, 1f);
    private static final Color DANGER_PRESSED = new Color(0.62f, 0.08f, 0.07f, 1f);
    private static final Color SELECTED_ROW = new Color(0.78f, 0.87f, 1f, 1f);

    private final Purpose purpose;
    private final boolean goToAfterDownload;
    private final boolean wizard;
    private final TextField field;
    private final Table results = new Table();
    private final ScrollPane resultsPane;
    private double pointLat, pointLon;
    /**
     * Whether there is a point: from the start everywhere but the wizard; there, once a fix, a
     * search or a tap gives one. A fix arriving late does not move a point already chosen.
     */
    private boolean pointChosen;
    /**
     * Whether the point is the reader's own - tapped, picked from the results, typed - and not
     * one a fix placed. Only the reader's own is kept from a fix arriving late. The phones
     * give the last position they knew first, which can be hours and a journey old, and the
     * real one after it: kept from that as well, the wizard stayed on the old place.
     */
    private boolean pointFromReader;
    private final List<SlippyMap.Shading> downloaded = new ArrayList<>();
    /**
     * Whether what is on the device is shaded, with the legend: on unless switched off, and
     * for the rest of the session once it is - a switch that came back on each time the
     * screen opened would be switched off each time by whoever wanted it off.
     */
    private static boolean showDownloaded = true;
    private Table legendLayer;
    /** Distinguishes the latest online search from older ones still on their way back. */
    private int searchGeneration = 0;
    /** Deleting: the button that does it, whose caption says how much the chosen block holds. */
    private com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton deleteButton;
    /** The bytes the chosen block holds, once measured; -1 until then. */
    private volatile long blockBytes = -1;
    /** Distinguishes the latest measurement from older ones still on their way back. */
    private int measureGeneration = 0;
    /** The result rows in list order, and where each one points, for the arrow keys. */
    private final List<Table> resultRows = new ArrayList<>();
    private final List<double[]> resultPoints = new ArrayList<>();
    /** The row the arrow keys have highlighted, or -1: Enter goes there instead of searching. */
    private int selected = -1;

    PlaceScreen(double lat, double lon, boolean goToAfterDownload, Purpose purpose) {
        // A first launch is the wizard whoever opened it: there is no position to start from.
        this.wizard = purpose == Purpose.WIZARD || P.getCoordinatesFirstTime();
        this.purpose = wizard ? Purpose.WIZARD : purpose;
        this.goToAfterDownload = goToAfterDownload;
        this.pointLat = lat;
        this.pointLon = lon;
        // The wizard is handed the target when the platform knows one, and 0° 0° when not.
        // Deleting starts with no point: what goes is the reader's to choose, not the place
        // the map happens to be on.
        this.pointChosen = purpose != Purpose.DELETE && (!wizard || lat != 0 || lon != 0);
        // In the wizard the point comes from the platform, which a better fix may still move.
        this.pointFromReader = !wizard;
        float unit = MapScreens.unit();

        field = new TextField("", fieldStyle(unit));
        field.setMessageText(s("Search_place_title"));
        field.setTextFieldListener((textField, c) -> {
            if (c == '\n' || c == '\r') {
                onEnter(textField.getText());
            } else {
                searchOffline(textField.getText());
            }
        });
        field.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.DOWN) {
                    return moveSelection(1);
                } else if (keycode == Input.Keys.UP) {
                    return moveSelection(-1);
                }
                return false;
            }
        });
        com.badlogic.gdx.scenes.scene2d.ui.Button search = searchButton(unit);
        search.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                searchAll(field.getText());
            }
        });

        Table top = new Table();
        top.add(field).growX().minWidth(0).height(unit).padRight(0.2f * unit);
        top.add(search).height(unit).width(1.3f * unit);
        root.add(top).growX().pad(0.2f * unit, 0.2f * unit, 0.1f * unit, 0.2f * unit).row();
        // The buttons under the search box, not at the foot of the screen: the keyboard, up
        // while a place is typed, covers the foot of the screen on a phone - Android does not
        // shrink the app to make room for it - and Download was hidden behind it.
        // They share the row equally, however long their captions: three of them side by
        // side are nine units of a ten-unit-wide upright phone before any caption is longer than
        // its button, and "Download area" in some languages is. A caption that does not fit
        // slides (MarqueeLabel) instead of pushing the row off the screen.
        Table bottom = new Table();
        // minWidth(0): a libGDX button's minimum width is its whole preferred width, caption and
        // all, so three of them asked for seven hundred pixels of a four-hundred-pixel phone and
        // the row, and the screen with it, ran off both sides.
        bottom.defaults().height(unit).minWidth(0).uniformX().fillX().expandX().pad(0, 0.1f * unit, 0, 0.1f * unit);
        if (!wizard) {
            com.badlogic.gdx.scenes.scene2d.ui.Button back = iconButton("icons/icon_back.png", s("Back"), false);
            back.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    close();
                }
            });
            bottom.add(back);
        }
        if (!wizard && this.purpose != Purpose.DELETE) {
            com.badlogic.gdx.scenes.scene2d.ui.Button goTo = iconButton("icons/icon_go_to_dest.png", s("Go_To"), false);
            goTo.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    goToPoint();
                }
            });
            bottom.add(goTo);
        }
        if (this.purpose == Purpose.DELETE) {
            // Red, not the blue of a main action: what it does cannot be taken back.
            com.badlogic.gdx.scenes.scene2d.ui.Button delete = iconButton(
                    "icons/icon_x.png", s("Storage_delete_area"), DANGER, DANGER_PRESSED, Color.WHITE);
            deleteButton = (com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton) delete;
            delete.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    deletePressed();
                }
            });
            bottom.add(delete);
        } else {
            // In the wizard Download is the one button, and has the row to itself.
            com.badlogic.gdx.scenes.scene2d.ui.Button download =
                    iconButton("icons/icon_checkbox_download_data.png", s("download_selected_area"), true);
            download.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    downloadPressed();
                }
            });
            bottom.add(download);
        }
        root.add(bottom).growX().pad(0, 0.1f * unit, 0.2f * unit, 0.1f * unit).row();

        SlippyMap map = newMap();
        map.setZoomRange(1f, 14f);
        map.setCenter(lat, lon);
        map.setZoom(pointChosen ? ZOOM_CLOSE : purpose == Purpose.DELETE ? ZOOM_REGION : ZOOM_WORLD);
        map.setTapListener((tapLat, tapLon) -> {
            // A point picked on the map is an answer, as a result from the list is: the list
            // and the keyboard have nothing more to do, and they cover the map the point is on.
            hideKeyboard();
            showResults(false);
            choose(tapLat, tapLon, false);
            // The point in the search box, in decimal degrees as a typed coordinate reads: it says
            // exactly where the tap landed, can be copied, and searched again as it stands.
            field.setText(String.format(Locale.ROOT, "%.5f, %.5f", tapLat, tapLon));
            field.setCursorPosition(field.getText().length());
        });

        results.top();
        ScrollPane.ScrollPaneStyle paneStyle = new ScrollPane.ScrollPaneStyle();
        resultsPane = new ScrollPane(results, paneStyle);
        resultsPane.setScrollingDisabled(true, false);
        resultsPane.setOverscroll(false, false);
        resultsPane.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (resultsPane.getStage() != null) {
                    resultsPane.getStage().setScrollFocus(resultsPane);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (resultsPane.getStage() != null && toActor != null && toActor.isDescendantOf(map)) {
                    resultsPane.getStage().setScrollFocus(map);
                }
            }
        });

        // The legend and the controls on layers of their own, not side by side in one row: a
        // phone held upright is ten buttons wide, and the legend beside the controls - which
        // keep room for the imagery list that opens beside them - came to thirteen, pushing
        // the controls off the right edge.
        legendLayer = new Table();
        legendLayer.setVisible(showsDownloaded());
        legendLayer.setTouchable(Touchable.childrenOnly);
        // Bottom left, clear of the imagery's credit in the bottom right corner.
        legendLayer.add(legend(unit, purpose == Purpose.DELETE)).expand().bottom().left().pad(0.2f * unit, 0.2f * unit, 1.1f * unit, 0.2f * unit);
        Table controlsLayer = new Table();
        controlsLayer.setTouchable(Touchable.childrenOnly);
        controlsLayer.add(mapControls(unit, () -> askForFix(true), downloadedSwitch())).expand().top().right().minWidth(0).pad(0.2f * unit);
        // The results a layer of their own, over the controls: in the same table, the list's
        // cell kept its height when hidden and left the buttons pushed down the map.
        Table resultsLayer = new Table();
        resultsLayer.top();
        resultsLayer.setTouchable(Touchable.childrenOnly);
        resultsLayer.add(resultsPane).growX().maxHeight(6f * unit);
        showResults(false);

        Stack stack = new Stack();
        stack.add(map);
        stack.add(legendLayer);
        stack.add(controlsLayer);
        stack.add(resultsLayer);
        root.add(stack).grow().row();


        showPoint();
        loadDownloadedTiles();
    }

    @Override
    void onShown() {
        if (pointChosen) {
            // Once laid out, which is the next frame: the block to download, whole, on the map.
            Gdx.app.postRunnable(() -> {
                if (isShowing()) {
                    map.fit(downloadBlock(), 0.08f);
                }
            });
        }
        if (purpose == Purpose.SEARCH) {
            offerSearch();
        } else if (purpose == Purpose.WIZARD) {
            askForFix(false);
        }
    }

    /**
     * The cursor in the search box and the keyboard up: the screen opened to search, or the
     * device could not say where it is and the reader chose to search instead.
     */
    void offerSearch() {
        if (stage() == null) {
            return;
        }
        stage().setKeyboardFocus(field);
        Gdx.input.setOnscreenKeyboardVisible(true);
    }

    /** Whether this is the wizard still waiting for a point: a search offer is for it. */
    boolean isWaitingForPoint() {
        return wizard && !pointChosen;
    }

    /**
     * @param pressed the locate button: the fix moves the point whatever was chosen before.
     *                Otherwise (the wizard asking on its own) a point the reader has already
     *                chosen is not taken away from them by a fix arriving late.
     */
    private void askForFix(boolean pressed) {
        getNativeScreenCaller().requestCurrentLocation((longitude, latitude) ->
                // The fix can come on any thread, twice (network, then GPS), and long after
                // the screen has gone - which on Android is what crashed the old chooser.
                Gdx.app.postRunnable(() -> {
                    if (!isShowing() || (latitude == 0 && longitude == 0)) {
                        return;
                    }
                    if (!pressed && pointChosen && pointFromReader && wizard) {
                        return;
                    }
                    choose(latitude, longitude, true);
                    pointFromReader = false;
                }));
    }

    private void hideKeyboard() {
        Gdx.input.setOnscreenKeyboardVisible(false);
        if (stage() != null) {
            stage().setKeyboardFocus(root);
        }
    }

    /** The point moves here, with the block a download would fetch around it. */
    private void choose(double lat, double lon, boolean center) {
        // An online answer still on its way belongs to the search this choice has ended: it
        // put the list back over the map and the point just chosen.
        searchGeneration++;
        pointLat = lat;
        pointLon = lon;
        pointChosen = true;
        pointFromReader = true;   // askForFix says otherwise after the call
        showPoint();
        measureBlock();
        if (center && !map.fit(downloadBlock(), 0.08f)) {
            map.setCenter(lat, lon);
            map.setZoom(ZOOM_CLOSE);
        }
    }

    /**
     * The tiles a download would fetch around the point. Shown whole when a point is chosen:
     * three archives a side, some 300 km, it was larger than the map at the zoom a point used
     * to be shown at, and all there was to see of it was a faint red wash.
     */
    private BoundingBox downloadBlock() {
        if (purpose == Purpose.DELETE) {
            // What Delete would delete: the block the point is in.
            return com.peaknav.database.DownloadedData.blockAt(pointLat, pointLon).getBoundingBox();
        }
        PeakNavDownloadManager manager = getC().missingDataDownloader.getPeakNavDownloadManager();
        List<Tile> tiles = manager.getQueueMapData(pointLat, pointLon, manager.getZoomPoi(), manager.getRangePoi());
        return MissingDataDownloader.getBoundingBoxOfTargetTiles(tiles);
    }

    /** The marker and the red block, when there is a point; what is downloaded, always. */
    private void showPoint() {
        List<SlippyMap.Shading> shadings = new ArrayList<>();
        if (showsDownloaded()) {
            shadings.addAll(downloaded);
        }
        if (pointChosen) {
            map.setMarker(pointLat, pointLon);
            List<BoundingBox> area = new ArrayList<>();
            area.add(downloadBlock());
            shadings.add(new SlippyMap.Shading(area, withAlpha(SELECTED, 0.12f), SELECTED, 0, 2.5f));
        }
        map.setShadings(shadings);
    }

    /**
     * A button with its icon and caption, on a rounded background with a soft shadow; the main
     * action ({@code accent}) filled blue. Icon and caption centred together; a caption too
     * long for the button slides (MarqueeLabel).
     */
    private static com.badlogic.gdx.scenes.scene2d.ui.Button iconButton(String icon, String caption, boolean accent) {
        return iconButton(icon, caption,
                accent ? MapScreens.ACCENT : Color.WHITE,
                accent ? MapScreens.ACCENT_PRESSED : MapScreens.PRESSED,
                accent ? Color.WHITE : MapScreens.INK);
    }

    private static com.badlogic.gdx.scenes.scene2d.ui.Button iconButton(
            String icon, String caption, Color fill, Color pressed, Color ink) {
        float unit = MapScreens.unit();
        com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton button =
                getC().widgetGetter.getImageTextButton(icon, caption, false);
        com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton.ImageTextButtonStyle style =
                new com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton.ImageTextButtonStyle(button.getStyle());
        style.up = background(fill, unit);
        style.down = background(pressed, unit);
        style.checked = null;
        style.fontColor = ink;
        button.setStyle(style);
        button.center();
        button.getImageCell().size(0.68f * unit);
        button.getLabelCell().expand(0, 0).padLeft(0.2f * unit);
        return button;
    }

    /** Search: the magnifier alone, beside the box it searches. */
    private static com.badlogic.gdx.scenes.scene2d.ui.Button searchButton(float unit) {
        com.badlogic.gdx.scenes.scene2d.ui.ImageButton.ImageButtonStyle style =
                new com.badlogic.gdx.scenes.scene2d.ui.ImageButton.ImageButtonStyle();
        style.up = background(Color.WHITE, unit);
        style.down = background(MapScreens.PRESSED, unit);
        style.imageUp = getC().widgetTextures.getTextureRegionDrawable("icons/icon_search.png");
        com.badlogic.gdx.scenes.scene2d.ui.ImageButton button =
                new com.badlogic.gdx.scenes.scene2d.ui.ImageButton(style);
        button.getImageCell().size(0.68f * unit);
        return button;
    }

    /** A rounded background, with room inside for its content. */
    static com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable background(Color fill, float unit) {
        com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable drawable = RoundedDrawables.box(fill, 0.22f * unit);
        drawable.setLeftWidth(0.25f * unit);
        drawable.setRightWidth(0.25f * unit);
        drawable.setTopHeight(0.08f * unit);
        drawable.setBottomHeight(0.12f * unit);
        return drawable;
    }

    /**
     * The switch for the shading of what is downloaded and its legend, at the foot of the map's
     * buttons: they cover much of the map where a lot is downloaded, and hide the place being
     * chosen. Dimmed while off.
     */
    private com.badlogic.gdx.scenes.scene2d.ui.Button downloadedSwitch() {
        final com.badlogic.gdx.scenes.scene2d.ui.Button button =
                getC().widgetTextures.getButtonWithIcon("icons/icon_checkbox_download_data2.png");
        button.setName("downloaded_areas");
        button.getColor().a = showDownloaded ? 1f : 0.4f;
        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                showDownloaded = !showDownloaded;
                button.getColor().a = showDownloaded ? 1f : 0.4f;
                legendLayer.setVisible(showsDownloaded());
                showPoint();
            }
        });
        return button;
    }

    /** What is downloaded is shaded unless switched off - and always where it is there to be deleted. */
    private boolean showsDownloaded() {
        return showDownloaded || purpose == Purpose.DELETE;
    }

    /**
     * Deleting: how much the block chosen would free, on the button that frees it. Measured
     * off the render thread - a block is some thousands of files - and shown when it is
     * known, unless another block has been chosen meanwhile.
     */
    private void measureBlock() {
        if (purpose != Purpose.DELETE || deleteButton == null || !pointChosen) {
            return;
        }
        final int generation = ++measureGeneration;
        final Tile block = com.peaknav.database.DownloadedData.blockAt(pointLat, pointLon);
        blockBytes = -1;
        deleteButton.setText(s("Storage_delete_area"));
        getC().submitExecutorGeneric(() -> {
            final long bytes = com.peaknav.database.DownloadedData.ofTheApp().blockBytes(block);
            Gdx.app.postRunnable(() -> {
                if (generation != measureGeneration || !isShowing()) {
                    return;
                }
                blockBytes = bytes;
                deleteButton.setText(s("Storage_delete_area") + ": "
                        + com.peaknav.database.DownloadedData.readable(bytes));
            });
        });
    }

    /**
     * Delete: the block the point is in, after a question; the map then shows what is left.
     * Not while a download runs, which is writing into the folders this would empty.
     */
    private void deletePressed() {
        if (!requirePoint()) {
            return;
        }
        if (getAppState().isMapDataDownloadStarted()) {
            getNativeScreenCaller().makeToast(s("Download_in_progress"));
            return;
        }
        final Tile block = com.peaknav.database.DownloadedData.blockAt(pointLat, pointLon);
        getC().submitExecutorGeneric(() -> {
            if (!com.peaknav.database.DownloadedData.ofTheApp().isDownloaded(block)) {
                getNativeScreenCaller().makeToast(s("Storage_nothing_here"));
                return;
            }
            // The question says how much goes, measured here if the button's count is not in yet.
            long holds = blockBytes >= 0 ? blockBytes
                    : com.peaknav.database.DownloadedData.ofTheApp().blockBytes(block);
            String question = s("Storage_delete_area_prompt") + " ("
                    + com.peaknav.database.DownloadedData.readable(holds) + ")";
            getNativeScreenCaller().promptYesNo("", question, () ->
                    getC().submitExecutorGeneric(() -> {
                        long freed = com.peaknav.database.DownloadedData.ofTheApp().deleteBlock(block);
                        com.peaknav.database.DownloadedData.tellTheApp();
                        getNativeScreenCaller().makeToast(s("Storage_freed") + " "
                                + com.peaknav.database.DownloadedData.readable(freed));
                        Gdx.app.postRunnable(this::measureBlock);
                    }));
        });
    }

    /** With no point yet, the buttons that need one say how to get one instead. */
    private boolean requirePoint() {
        if (pointChosen) {
            return true;
        }
        getNativeScreenCaller().makeToast(s("Choose_place_first"));
        offerSearch();
        return false;
    }

    /**
     * Go To: there at once where the data is on the device; where it is not, the offer to
     * download it and go - arriving on empty terrain is no use to anyone.
     */
    private void goToPoint() {
        if (!requirePoint()) {
            return;
        }
        final double lat = pointLat, lon = pointLon;
        if (checkMissingElevationForCoord(lat, lon)) {
            getNativeScreenCaller().promptYesNo("", s("Missing_data_prompt_download"), () ->
                    Gdx.app.postRunnable(() -> {
                        if (isShowing()) {
                            downloadWithConsent(true);
                        }
                    }));
            return;
        }
        close();
        getC().submitExecutorGeneric(() -> getC().L.setCurrentTargetCoords(lat, lon));
    }

    private void downloadPressed() {
        if (!requirePoint()) {
            return;
        }
        downloadWithConsent(goToAfterDownload);
    }

    private void downloadWithConsent(boolean goTo) {
        if (!P.isCollectDownloadInfo()) {
            // Downloading needs the consent the welcome screen asks for; given here, the
            // download goes ahead at once rather than asking for a second tap.
            getNativeScreenCaller().promptYesNo("", s("Missing_download_info_consent"), () ->
                    getC().submitExecutorGeneric(() -> {
                        P.setCollectDownloadInfo(true);
                        Gdx.app.postRunnable(() -> {
                            if (isShowing()) {
                                startDownload(goTo);
                            }
                        });
                    }));
            return;
        }
        startDownload(goTo);
    }

    private void startDownload(boolean goToAfter) {
        final double lat = pointLat, lon = pointLon;
        final boolean goTo = goToAfter;
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
            // And remember it now. The position is otherwise saved only once a place has
            // landed, which takes the elevation this download is fetching: an app closed during
            // its first download kept nothing, and came back to null island with nothing
            // loaded and nothing downloading. Saved, it comes back here, and the download is
            // taken up where it stopped (MapViewerScreen.showOnce).
            getC().L.saveCoordinatesToPreferences(lat, lon);
        }
        DOWNLOADER.execute(() -> {
            MissingDataDownloader downloader = getC().missingDataDownloader;
            PeakNavDownloadManager.Outcome outcome = null;
            try {
                downloader.setCoords(lat, lon);
                outcome = downloader.doDownload(goTo);
            } finally {
                getAppState().setMapDataDownloadStarted(false);
                getAppState().mapDataDownloadEnded(outcome, true);
            }
        });
        close();
    }

    // ------------------------------------------------------------------ searching

    private void searchOffline(String typed) {
        searchGeneration++;   // an online answer to an earlier text no longer belongs here
        List<LuceneGeonameSearch.GeonameResult> found = new ArrayList<>();
        double[] coordinates = CoordinateSearch.parseCoordinates(typed);
        if (coordinates != null) {
            // Offered as the one result while typing, not jumped to: "46.02, 7" on the way to
            // "46.02, 7.74" already reads as coordinates.
            String label = String.format(Locale.ROOT, "%.5f, %.5f", coordinates[0], coordinates[1]);
            found.add(new LuceneGeonameSearch.GeonameResult(
                    label, label, (float) coordinates[0], (float) coordinates[1], -1));
        } else {
            String query = CoordinateSearch.cleanQuery(typed);
            // The index is opened after start-up; until it is, no results is the honest answer,
            // and the next keystroke asks again.
            LuceneGeonameSearch index = getC().luceneGeonameSearch;
            if (!query.isEmpty() && index != null) {
                found.addAll(index.searchGeoName(query));
            }
        }
        setResults(found);
    }

    /** Search or Enter: the offline results at once, then the online ones added as they come. */
    private void searchAll(String typed) {
        double[] coordinates = CoordinateSearch.parseCoordinates(typed);
        if (coordinates != null) {
            hideKeyboard();
            showResults(false);
            choose(coordinates[0], coordinates[1], true);
            return;
        }
        searchOffline(typed);
        String query = CoordinateSearch.cleanQuery(typed);
        if (query.isEmpty()) {
            return;
        }
        final int generation = searchGeneration;
        getC().onlineSearch.parseDestinationText(query, (ArrayList<NominatimResponse> responses) ->
                Gdx.app.postRunnable(() -> {
                    if (!isShowing() || generation != searchGeneration || responses == null) {
                        return;
                    }
                    for (NominatimResponse response : responses) {
                        addResult(response.displayName, response.lat, response.lon);
                    }
                    showResults(results.hasChildren());
                }));
    }

    /**
     * Enter: with a result highlighted, it is picked, and outside the wizard gone to, as a tap
     * and Go To; else a search.
     */
    private void onEnter(String typed) {
        if (selected >= 0 && resultsPane.isVisible()) {
            double[] point = resultPoints.get(selected);
            hideKeyboard();
            showResults(false);
            choose(point[0], point[1], true);
            if (!wizard) {
                goToPoint();
            }
        } else {
            searchAll(typed);
        }
    }

    /**
     * Up and Down: the highlight moves through the results, from none to the first, stopping
     * at either end, and the list scrolls to keep it in view. False, so the key goes on to
     * the text field, when there is no list to move in.
     */
    private boolean moveSelection(int step) {
        if (!resultsPane.isVisible() || resultRows.isEmpty()) {
            return false;
        }
        int next = Math.max(0, Math.min(resultRows.size() - 1, selected + step));
        select(next);
        Table row = resultRows.get(next);
        resultsPane.layout();
        resultsPane.scrollTo(row.getX(), row.getY(), row.getWidth(), row.getHeight());
        return true;
    }

    private void select(int index) {
        if (selected >= 0 && selected < resultRows.size()) {
            resultRows.get(selected).setBackground(MapScreens.white());
        }
        selected = index;
        if (index >= 0) {
            resultRows.get(index).setBackground(
                    getC().widgetTextures.getUniformDrawable(SELECTED_ROW));
        }
    }

    private void setResults(List<LuceneGeonameSearch.GeonameResult> found) {
        results.clearChildren();
        resultRows.clear();
        resultPoints.clear();
        selected = -1;
        for (LuceneGeonameSearch.GeonameResult result : found) {
            addResult(result.getFullName(), result.lat, result.lon);
        }
        showResults(!found.isEmpty());
    }

    private void addResult(String text, final double lat, final double lon) {
        float unit = MapScreens.unit();
        Label label = new Label(text, MapScreens.darkCaption());
        label.setWrap(true);
        label.setAlignment(Align.left);
        Table row = new Table();
        row.setBackground(MapScreens.white());
        row.setTouchable(Touchable.enabled);
        row.add(label).growX().pad(0.2f * unit, 0.3f * unit, 0.2f * unit, 0.3f * unit).minHeight(0.6f * unit);
        row.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                hideKeyboard();
                showResults(false);
                choose(lat, lon, true);
            }
        });
        results.add(row).growX().padBottom(1f).row();
        resultRows.add(row);
        resultPoints.add(new double[]{lat, lon});
    }

    private void showResults(boolean visible) {
        if (!visible) {
            select(-1);
        }
        resultsPane.setVisible(visible);
        if (visible) {
            resultsPane.setScrollY(0);
        }
    }

    // ------------------------------------------------------------------ what is downloaded

    /** What is on the device changed while the screen is up: read it again (MapScreens). */
    void reloadDownloaded() {
        if (isShowing()) {
            loadDownloadedTiles();
        }
    }

    /** Shades what is on the device already, read off the render thread and handed back. */
    private void loadDownloadedTiles() {
        getC().submitExecutorGeneric(() -> {
            final List<List<BoundingBox>> boxes = new ArrayList<>();
            final List<Layer> shown = Layer.shown();
            for (Layer layer : shown) {
                boxes.add(downloadedBoxes(layer));
            }
            Gdx.app.postRunnable(() -> {
                if (!isShowing()) {
                    return;
                }
                // The stripes' textures are made here, on the render thread, which GL wants.
                downloaded.clear();
                int period = Hatching.period(MapScreens.unit());
                for (int i = 0; i < shown.size(); i++) {
                    Layer layer = shown.get(i);
                    downloaded.add(new SlippyMap.Shading(boxes.get(i), null, layer.color, 0, 1f)
                            .hatched(Hatching.texture(layer.color, i, shown.size(), period), period, i, shown.size()));
                }
                showPoint();
            });
        });
    }

    /** The tiles of one kind of data on the device; none on a first run, before the database. */
    private static List<BoundingBox> downloadedBoxes(Layer layer) {
        List<BoundingBox> boxes = new ArrayList<>();
        try {
            for (Tile tile : getC().mapSqlite.getListOfDownloadedTiles(layer.table)) {
                boxes.add(tile.getBoundingBox());
            }
        } catch (RuntimeException e) {
            // No database yet, on a first run: nothing is downloaded, which is what this shows.
        }
        return boxes;
    }

    private static Color withAlpha(Color color, float alpha) {
        return new Color(color.r, color.g, color.b, alpha);
    }

    /** What each colour on the map means, on a light plate over the map's corner. */
    private static Table legend(float unit, boolean deleting) {
        Table legend = new Table();
        legend.setBackground(getC().widgetTextures.getUniformDrawable(new Color(1f, 1f, 1f, 0.85f)));
        legend.pad(0.15f * unit, 0.25f * unit, 0.15f * unit, 0.25f * unit);
        List<Layer> shown = Layer.shown();
        int period = Hatching.period(unit);
        for (int i = 0; i < shown.size(); i++) {
            Layer layer = shown.get(i);
            // The swatch striped as the map is, in its own lane: a stripe's place in the pattern
            // says which kind it is, as much as its colour.
            com.badlogic.gdx.graphics.g2d.TextureRegion stripes = new com.badlogic.gdx.graphics.g2d.TextureRegion(
                    Hatching.texture(layer.color, i, shown.size(), period), 0, 0, period, period);
            legendRow(legend, new com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(stripes),
                    layer.color, s(layer.caption), unit);
        }
        legendRow(legend, getC().widgetTextures.getUniformDrawable(SELECTED), SELECTED,
                s(deleting ? "Legend_to_delete" : "Legend_selected"), unit);
        return legend;
    }

    private static void legendRow(Table legend, com.badlogic.gdx.scenes.scene2d.utils.Drawable fill,
                                  Color outline, String caption, float unit) {
        Table swatch = new Table();
        swatch.setBackground(getC().widgetTextures.getUniformDrawable(outline));
        com.badlogic.gdx.scenes.scene2d.ui.Image inside = new com.badlogic.gdx.scenes.scene2d.ui.Image(fill);
        Table well = new Table();
        well.setBackground(getC().widgetTextures.getUniformDrawable(Color.WHITE));
        well.add(inside).grow();
        swatch.add(well).grow().pad(Math.max(1f, 0.03f * unit));
        legend.add(swatch).size(0.35f * unit).padRight(0.2f * unit).padBottom(0.08f * unit);
        Label label = new Label(caption, new Label.LabelStyle(getC().styleSingleton.getBitmapFontVerySmallDark(), Color.BLACK));
        legend.add(label).left().padBottom(0.08f * unit).row();
    }

    private static TextField.TextFieldStyle fieldStyle(float unit) {
        TextField.TextFieldStyle style = new TextField.TextFieldStyle();
        style.font = getC().styleSingleton.getBitmapFontMedium();
        style.fontColor = Color.BLACK;
        style.messageFont = style.font;
        style.messageFontColor = new Color(0.45f, 0.45f, 0.45f, 1f);
        com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable padded = background(Color.WHITE, unit);
        padded.setLeftWidth(0.35f * unit);
        padded.setRightWidth(0.35f * unit);
        style.background = padded;
        com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable cursor =
                (com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable)
                        getC().widgetTextures.getUniformDrawable(Color.BLACK);
        cursor = new com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(cursor);
        cursor.setMinWidth(Math.max(2f, 0.03f * unit));
        style.cursor = cursor;
        style.selection = getC().widgetTextures.getUniformDrawable(new Color(0.55f, 0.75f, 1f, 1f));
        return style;
    }
}
