package com.peaknav.viewer.panes;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.widgets.WidgetGetter;
import static com.peaknav.viewer.widgets.WidgetGetter.ImageTextButtonOptionPane;

import static com.peaknav.utils.PreferencesManager.UnitSystem.IMPERIAL;
import static com.peaknav.utils.PreferencesManager.UnitSystem.METRIC;
import static com.peaknav.viewer.imgmapprovider.SatelliteImageProvider.SatelliteProviderOptions;

import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.network.DownloadProvider;
import com.peaknav.roads.RoadStyle;
import com.peaknav.ui.TextFieldsCallback;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OptionPane {

    private final MapApp mapApp;
    private final Table table;
    private final Table tableOneColumn;

    private final ExecutorService changer = Executors.newSingleThreadExecutor();

    // TODO: maybe replace with getC().widgetTextures.getUniformDrawable(Color.BLACK); ?
    private final Button optionsButton;
    private final float widgetUnitStep;
    private final Table selectBoxSatSrc;
    private final Table selectInfoOpts;
    private final Table selectGpx;
    private final Table selectLabels;
    private final Table selectSky;
    private final Table selectCompass;
    /** The roads submenu, laid out in pairs for a wide screen and in one column for a tall one. */
    private final Table selectRoads;
    private final Table selectRoadsOneColumn;
    /** Re-read the road style into the roads submenus' swatches and sliders when one opens. */
    private final List<Runnable> roadMenuRefreshers = new ArrayList<>();
    private final float buttonWidth;
    private final float height;
    private final float padHeight;
    private final float roundButtonSize;
    /** Share of a row taken by a custom source's delete button; reserved on every row. */
    private static final float REMOVE_BUTTON_WIDTH_FRACTION = 0.18f;
    /** The source list scrolls rather than growing past this share of the screen height. */
    private static final float MAX_PROVIDER_LIST_SCREEN_FRACTION = 0.55f;
    /** Scrollbar width, and the gap between it and the buttons, relative to a round button. */
    private static final float SCROLLBAR_WIDTH_FRACTION = 0.55f;
    private static final float SCROLLBAR_GAP_FRACTION = 0.35f;
    private static final Color SCROLLBAR_TRACK_COLOR = new Color(1f, 1f, 1f, 0.30f);
    private static final Color SCROLLBAR_KNOB_COLOR = new Color(0.25f, 0.25f, 0.25f, 0.9f);

    /** Keyed by provider id, since custom providers are not enum constants. */
    private final Map<String, TextButton> selectBoxSatSrcMap = new LinkedHashMap<>();

/*
    public Table getTableAppInfo() {
        return tableAppInfo;
    }
    */

    // private final Table tableAppInfo;

    public Table getSelectBoxUnits() {
        return selectBoxUnits;
    }

    public Table getSelectInfoOpts() {
        return selectInfoOpts;
    }

    public Table getSelectBoxDownloadSource() {
        return selectBoxDownloadSrc;
    }

    private final Table selectBoxUnits;
    private final Table selectBoxDownloadSrc;

    public OptionPane(Button optionsButton, float widgetUnitStep) {
        this.optionsButton = optionsButton;
        this.widgetUnitStep = widgetUnitStep;
        mapApp = MapViewerSingleton.getAppInstance();

        roundButtonSize = widgetUnitStep / 2f;
        padHeight = 0.2f*roundButtonSize;
        height = 2f*roundButtonSize;
        buttonWidth = 6.0f*widgetUnitStep;

        selectBoxSatSrc = createSatelliteSourceSelectBox();
        selectBoxDownloadSrc = createDownloadSourceSelectBox();
        selectBoxUnits = createSelectBoxUnitSystem();
        selectInfoOpts = createInfoOptsMenu();
        selectGpx = createGpxMenu();
        selectLabels = createLabelsMenu();
        selectSky = createSkyMenu();
        selectCompass = createCompassMenu();
        selectRoads = createRoadsMenu(false);
        selectRoadsOneColumn = createRoadsMenu(true);
        // tableAppInfo = createTableAppInfo();
        table = getPreferencesTable(false);
        tableOneColumn = getPreferencesTable(true);

        selectBoxSatSrc.center();
        table.center();
        tableOneColumn.center();

        table.setVisible(false);
        tableOneColumn.setVisible(false);
    }

    /*
    private Table createTableAppInfo() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        Table container = new Table();

        Label.LabelStyle labelStyle = getC().styleSingleton.getLabelStyle();
        for (int i = 0; i < 20; i++) {
            Label labelAppInfo = new Label(
                    String.format("App Info %d", i),
                    labelStyle);
            labelAppInfo.setWrap(false);
            container.add(labelAppInfo).width(widgetUnitStep*5).row();
        }

        ScrollPane.ScrollPaneStyle scrollPaneStyle = new ScrollPane.ScrollPaneStyle();
        scrollPaneStyle.vScroll = getC().widgetTextures.getTextureRegionDrawable("icons/icon_back.png");
        scrollPaneStyle.vScrollKnob = getC().widgetTextures.getTextureRegionDrawable("icons/icon_back.png");
        ScrollPane scrollPane = new ScrollPane(container, scrollPaneStyle);
        scrollPane.setScrollingDisabled(false, true);
        scrollPane.setFadeScrollBars(false);
        scrollPane.setScrollbarsOnTop(true);
        scrollPane.setScrollBarPositions(true, true);

        scrollPane.setSize(Gdx.graphics.getWidth() * 0.9f, Gdx.graphics.getHeight() * 0.9f);
        scrollPane.setTouchable(Touchable.enabled);

        table.add(scrollPane).expand().fill().width(Gdx.graphics.getWidth()*0.8f).height(Gdx.graphics.getHeight()*0.8f).row();

        List<Table> buttons = new LinkedList<>();

        ImageTextButtonOptionPane back = getImageTextButton("icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true);
        table.setVisible(false);
        return table;
    }
    */

    public Table getTableOneColumn() {
        return tableOneColumn;
    }

    public boolean isVisible() {
        return table.isVisible() || tableOneColumn.isVisible();
    }

    public Table getSelectBoxSatelliteSource() {
        return selectBoxSatSrc;
    }

    public Table getSelectGpx() {
        return selectGpx;
    }

    public Table getSelectLabels() {
        return selectLabels;
    }

    public Table getSelectSky() {
        return selectSky;
    }

    public Table getSelectCompass() {
        return selectCompass;
    }

    public Table getSelectRoads() {
        return selectRoads;
    }

    public Table getSelectRoadsOneColumn() {
        return selectRoadsOneColumn;
    }

    /* private Table createSatelliteSourceSelectBox2() {

        Array<String> options = new Array<>();
        options.add("Option 1");
        options.add("Option 2");
        options.add("Option 3");

        ListStyle listStyle = new ListStyle();
        listStyle.font = getC().styleSingleton.getBitmapFontSmall();
        listStyle.fontColorSelected = Color.BLUE; // Set selected item color
        listStyle.fontColorUnselected = Color.WHITE; // Set unselected item color
        listStyle.selection = getUniformColor(Color.BROWN);

        ScrollPane.ScrollPaneStyle scrollPaneStyle = new ScrollPane.ScrollPaneStyle();
        scrollPaneStyle.vScroll = getUniformColor(Color.BLACK);

        SelectBox.SelectBoxStyle selectBoxStyle = new SelectBox.SelectBoxStyle();

        selectBoxStyle.font = getC().styleSingleton.getBitmapFontSmall();
        selectBoxStyle.fontColor = Color.WHITE; // Set font color
        selectBoxStyle.background = getC().widgetTextures.getUniformDrawable(Color.BLUE); // Set background color
        selectBoxStyle.scrollStyle = scrollPaneStyle;
        selectBoxStyle.listStyle = listStyle;
        selectBoxStyle.backgroundOpen = getC().widgetTextures.getUniformDrawable(Color.LIGHT_GRAY); // Set background color when open

        // Create SelectBox
        SelectBox<String> selectBox = new SelectBox<>(selectBoxStyle);
        selectBox.setItems(options);

        // Set position and size
        // selectBox.setPosition(100, 100);
        // selectBox.setSize(200, 50);

        Table table = new Table();
        table.setFillParent(true);
        table.center();
        table.add(selectBox).width(Gdx.graphics.getWidth()*0.6f)
                .height(Gdx.graphics.getHeight()*0.6f);
        table.setVisible(true);

        return table;
    }
     */

    private volatile TextButton prevChecked = null;

    private Table createGpxMenu() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        float buttonWidth = this.buttonWidth * 1.2f;
        List<Table> buttons = new ArrayList<>(8);

        ImageTextButtonOptionPane buttonFile = getC().widgetGetter.getImageTextButton(
                "icons/icon_map.png", s("Load_gpx_file"), false);
        buttonFile.addClickListener(() -> {
            getNativeScreenCaller().pickGpxFile();
            hide();
        });
        buttons.add(buttonFile);

        ImageTextButtonOptionPane buttonUrl = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_download_data.png", s("Load_gpx_url"), false);
        buttonUrl.addClickListener(() -> {
            getNativeScreenCaller().promptForTextFields(
                    s("Load_gpx_url"), s("Gpx_url_prompt"),
                    new String[]{"URL"}, new String[]{""},
                    new com.peaknav.ui.TextFieldsCallback() {
                        @Override
                        public void onEntered(String[] values) {
                            if (values != null && values.length > 0) {
                                getC().gpxManager.loadFromUrl(values[0]);
                            }
                        }

                        @Override
                        public void onCancelled() {
                        }
                    });
            hide();
        });
        buttons.add(buttonUrl);

        // Reliable way to launch the cinematic tour, in addition to the on-map camera button that
        // appears once a track is loaded.
        ImageTextButtonOptionPane buttonFly = getC().widgetGetter.getImageTextButton(
                "icons/icon_gpx_play.png", s("Gpx_flyover"), false);
        buttonFly.addClickListener(() -> {
            if (!getC().gpxManager.isEmpty()) {
                getC().getMapViewerScreen().startGpxFlythrough();
                hide();
            }
        });
        buttons.add(buttonFly);

        ImageTextButtonOptionPane buttonClear = getC().widgetGetter.getImageTextButton(
                "icons/icon_x.png", s("Clear_gpx"), false);
        buttonClear.addClickListener(() -> {
            getC().getMapViewerScreen().stopGpxFlythrough(); // don't keep touring a cleared track
            getC().gpxManager.clear();
        });
        buttons.add(buttonClear);

        ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton(
                "icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true, buttonWidth);
        table.setVisible(false);
        return table;
    }

    /**
     * Submenu that toggles which labels are shown: the POI labels (peaks, places, alpine huts) and
     * the ranged-area labels (islands, cities, mountain ranges, lakes).
     */
    private Table createLabelsMenu() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        List<Table> buttons = new ArrayList<>(9);

        ImageTextButtonOptionPane checkBoxShowPeaks = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_peak_names.png", s("Peak_names"), true);
        addCheckingStateProperty(checkBoxShowPeaks, () -> P.isPeakVisible());
        checkBoxShowPeaks.addClickListener(() ->
                changer.execute(() -> P.setPeakVisible(checkBoxShowPeaks.isChecked())));
        buttons.add(checkBoxShowPeaks);

        ImageTextButtonOptionPane checkBoxShowPlaces = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_place_names.png", s("Place_names"), true);
        addCheckingStateProperty(checkBoxShowPlaces, () -> P.isVisiblePlaceNames());
        checkBoxShowPlaces.addClickListener(() ->
                changer.execute(() -> P.setVisiblePlaceNames(checkBoxShowPlaces.isChecked())));
        buttons.add(checkBoxShowPlaces);

        ImageTextButtonOptionPane checkBoxShowAlpineHuts = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_alpine_huts.png", s("Alpine_huts"), true);
        addCheckingStateProperty(checkBoxShowAlpineHuts, () -> P.isVisibleAlpineHuts());
        checkBoxShowAlpineHuts.addClickListener(() ->
                changer.execute(() -> P.setVisibleAlpineHuts(checkBoxShowAlpineHuts.isChecked())));
        buttons.add(checkBoxShowAlpineHuts);

        ImageTextButtonOptionPane checkBoxShowIslands = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_islands.png", s("Islands_label"), true);
        addCheckingStateProperty(checkBoxShowIslands, () -> P.isVisibleIslands());
        checkBoxShowIslands.addClickListener(() ->
                changer.execute(() -> P.setVisibleIslands(checkBoxShowIslands.isChecked())));
        buttons.add(checkBoxShowIslands);

        ImageTextButtonOptionPane checkBoxShowCities = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_large_towns.png", s("Cities_label"), true);
        addCheckingStateProperty(checkBoxShowCities, () -> P.isVisibleCities());
        checkBoxShowCities.addClickListener(() ->
                changer.execute(() -> P.setVisibleCities(checkBoxShowCities.isChecked())));
        buttons.add(checkBoxShowCities);

        ImageTextButtonOptionPane checkBoxShowRanges = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_mountain_ranges.png", s("Mountain_ranges_label"), true);
        addCheckingStateProperty(checkBoxShowRanges, () -> P.isVisibleMountainRanges());
        checkBoxShowRanges.addClickListener(() ->
                changer.execute(() -> P.setVisibleMountainRanges(checkBoxShowRanges.isChecked())));
        buttons.add(checkBoxShowRanges);

        ImageTextButtonOptionPane checkBoxShowLakes = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_lakes.png", s("Lakes_label"), true);
        addCheckingStateProperty(checkBoxShowLakes, () -> P.isVisibleLakes());
        checkBoxShowLakes.addClickListener(() ->
                changer.execute(() -> P.setVisibleLakes(checkBoxShowLakes.isChecked())));
        buttons.add(checkBoxShowLakes);

        // Street, track and trail names, written along their ways (see RoadNameRenderer).
        ImageTextButtonOptionPane checkBoxRoadNames = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_roads.png", s("Road_names"), true);
        addCheckingStateProperty(checkBoxRoadNames, () -> P.getRoadStyle().isRoadNames());
        checkBoxRoadNames.addClickListener(() -> changer.execute(() -> {
            P.getRoadStyle().setRoadNames(checkBoxRoadNames.isChecked());
            P.persistRoadStyle();
        }));
        buttons.add(checkBoxRoadNames);

        ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton(
                "icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true, buttonWidth);
        table.setVisible(false);
        return table;
    }

    /** Label for the sky-mode cycle button: 0 = local time, 1 = day, 2 = night (see PreferencesManager). */
    private String skyModeLabel() {
        String mode;
        switch (P.getSkyMode()) {
            case 1: mode = s("Sky_mode_day"); break;
            case 2: mode = s("Sky_mode_night"); break;
            default: mode = s("Sky_mode_local"); break;
        }
        return s("Sky_mode") + ": " + mode;
    }

    private Table createSkyMenu() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        List<Table> buttons = new ArrayList<>(5);

        // Sky mode: cycles local time / forced day / forced night.
        ImageTextButtonOptionPane buttonSkyMode = getC().widgetGetter.getImageTextButton(
                "icons/icon_sky_mode.png", skyModeLabel(), false);
        buttonSkyMode.addClickListener(() -> {
            P.setSkyMode(P.getSkyMode() + 1);
            buttonSkyMode.getLabel().setText(skyModeLabel());
            P.setSkyView(true); // touching a sky option turns the sky on
        });
        buttons.add(buttonSkyMode);

        // Custom time: opens the native date/time picker (or reset to the device clock).
        ImageTextButtonOptionPane buttonSkyTime = getC().widgetGetter.getImageTextButton(
                "icons/icon_sky_time.png", s("Sky_time"), false);
        buttonSkyTime.addClickListener(() -> {
            P.setSkyView(true); // setting a custom sky time turns the sky on
            hide();
            getNativeScreenCaller().chooseSkyTime();
        });
        buttons.add(buttonSkyTime);

        ImageTextButtonOptionPane checkBoxConstellations = getC().widgetGetter.getImageTextButton(
                "icons/icon_sky_constellations.png", s("Sky_constellations"), true);
        addCheckingStateProperty(checkBoxConstellations, () -> P.isSkyConstellations());
        checkBoxConstellations.addClickListener(() ->
                changer.execute(() -> {
                    P.setSkyConstellations(checkBoxConstellations.isChecked());
                    P.setSkyView(true); // touching a sky option turns the sky on
                }));
        buttons.add(checkBoxConstellations);

        // Sits directly under the constellations, because it governs their names as well as
        // everything else written on the sky.
        ImageTextButtonOptionPane checkBoxSkyLabels = getC().widgetGetter.getImageTextButton(
                "icons/icon_sky_labels.png", s("Sky_labels"), true);
        addCheckingStateProperty(checkBoxSkyLabels, () -> P.isSkyLabels());
        checkBoxSkyLabels.addClickListener(() ->
                changer.execute(() -> {
                    P.setSkyLabels(checkBoxSkyLabels.isChecked());
                    P.setSkyView(true); // touching a sky option turns the sky on
                }));
        buttons.add(checkBoxSkyLabels);

        ImageTextButtonOptionPane checkBoxSkyGrid = getC().widgetGetter.getImageTextButton(
                "icons/icon_sky_grid.png", s("Sky_grid"), true);
        addCheckingStateProperty(checkBoxSkyGrid, () -> P.isSkyGrid());
        checkBoxSkyGrid.addClickListener(() ->
                changer.execute(() -> {
                    P.setSkyGrid(checkBoxSkyGrid.isChecked());
                    P.setSkyView(true); // touching a sky option turns the sky on
                }));
        buttons.add(checkBoxSkyGrid);

        ImageTextButtonOptionPane checkBoxEcliptic = getC().widgetGetter.getImageTextButton(
                "icons/icon_sky_ecliptic.png", s("Sky_ecliptic"), true);
        addCheckingStateProperty(checkBoxEcliptic, () -> P.isSkyEcliptic());
        checkBoxEcliptic.addClickListener(() ->
                changer.execute(() -> {
                    P.setSkyEcliptic(checkBoxEcliptic.isChecked());
                    P.setSkyView(true);
                }));
        buttons.add(checkBoxEcliptic);

        ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton(
                "icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true, buttonWidth);
        table.setVisible(false);
        return table;
    }

    private Table createCompassMenu() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        List<Table> buttons = new ArrayList<>(4);

        // Touching any option turns the whole group on, exactly as the sky submenu
        // turns the sky on - an adjustment you can see beats one silently ignored
        // because the master switch was off.

        ImageTextButtonOptionPane checkBoxCoordinates = getC().widgetGetter.getImageTextButton(
                "icons/icon_loc_pin.png", s("Show_coordinates"), true);
        addCheckingStateProperty(checkBoxCoordinates, () -> P.isShowCoordinates());
        checkBoxCoordinates.addClickListener(() -> changer.execute(() -> {
            P.setShowCoordinates(checkBoxCoordinates.isChecked());
            P.setCompassLocation(true);
        }));
        buttons.add(checkBoxCoordinates);

        ImageTextButtonOptionPane checkBoxHorizon = getC().widgetGetter.getImageTextButton(
                "icons/icon_compass_horizon.png", s("Horizon_compass"), true);
        addCheckingStateProperty(checkBoxHorizon, () -> P.isHorizonCompass());
        checkBoxHorizon.addClickListener(() -> changer.execute(() -> {
            P.setHorizonCompass(checkBoxHorizon.isChecked());
            P.setCompassLocation(true);
        }));
        buttons.add(checkBoxHorizon);

        ImageTextButtonOptionPane checkBoxCorner = getC().widgetGetter.getImageTextButton(
                "icons/icon_compass_corner.png", s("Corner_compass"), true);
        addCheckingStateProperty(checkBoxCorner, () -> P.isCornerCompass());
        checkBoxCorner.addClickListener(() -> changer.execute(() -> {
            P.setCornerCompass(checkBoxCorner.isChecked());
            P.setCompassLocation(true);
        }));
        buttons.add(checkBoxCorner);

        ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton(
                "icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true, buttonWidth);
        table.setVisible(false);
        return table;
    }

    /** The i18n key naming each road-style colour in the roads submenu. */
    private static String swatchLabelKey(RoadStyle.Swatch swatch) {
        switch (swatch) {
            case ROADS: return "Road_color_roads";
            case TRACKS: return "Road_color_tracks";
            case TRAILS_EASY: return "Road_color_trails_easy";
            case TRAILS_MOUNTAIN: return "Road_color_trails_mountain";
            default: return "Road_color_trails_alpine";
        }
    }

    /** Shows a colour as the button's icon: a plain square of it. */
    private void showSwatch(ImageTextButtonOptionPane button, int rgba8888) {
        TextureRegionDrawable swatch = getC().widgetTextures.getUniformDrawable(new Color(rgba8888));
        swatch.setMinWidth(0.7f * widgetUnitStep);
        swatch.setMinHeight(0.7f * widgetUnitStep);
        button.getStyle().imageUp = swatch;
        button.getStyle().imageDown = swatch;
        button.getStyle().imageChecked = swatch;
    }

    /** What a slider in the roads submenu does with its value. */
    private interface SliderChange {
        /** @param settled true once the finger has let go: the moment to save */
        void changed(float value, boolean settled);
    }

    /** The slider look of the photo bar: the same knob and track, so the two read as one family. */
    private Slider.SliderStyle menuSliderStyle(float knobSize) {
        Slider.SliderStyle style = new Slider.SliderStyle();
        TextureRegionDrawable knob = getC().widgetTextures.getTextureRegionDrawable("icons/icon_slider_alpha.png");
        knob.setMinWidth(knobSize);
        knob.setMinHeight(knobSize);
        style.knob = knob;
        style.background = getC().widgetTextures.getNinePatchDrawable("icons/slider_nine_patch.png");
        return style;
    }

    /** A menu row with a label on the left and a slider filling the rest, on a button's white. */
    private Table sliderRow(String text, float min, float max, float step, float sliderWidth,
                            final Slider[] out, final SliderChange onChange) {
        Table row = new Table();
        row.setBackground(getC().widgetTextures.getUniformDrawable(Color.WHITE));
        Label label = new Label(text, new Label.LabelStyle(getC().styleSingleton.getBitmapFontSmall(), Color.BLACK));
        final Slider slider = new Slider(min, max, step, false, menuSliderStyle(0.8f * height));
        slider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                onChange.changed(slider.getValue(), !slider.isDragging());
            }
        });
        row.add(label).left().padLeft(0.3f * widgetUnitStep).expandX();
        row.add(slider).width(sliderWidth).height(0.8f * height).padRight(0.3f * widgetUnitStep);
        out[0] = slider;
        return row;
    }

    /**
     * A slider in half a menu row, its name as a small caption above it. Beside the slider there
     * would be no room for "Lunghezza tratteggio" or "Beschriftungsdichte" in half a row; above
     * it the name has the cell's whole width.
     */
    private Table sliderCell(String text, float min, float max, float step, final Slider[] out,
                             final SliderChange onChange) {
        Table cell = new Table();
        cell.setBackground(getC().widgetTextures.getUniformDrawable(Color.WHITE));
        Label label = new Label(text, new Label.LabelStyle(getC().styleSingleton.getBitmapFontVerySmall(), Color.BLACK));
        final Slider slider = new Slider(min, max, step, false, menuSliderStyle(0.5f * height));
        slider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                onChange.changed(slider.getValue(), !slider.isDragging());
            }
        });
        cell.add(label).left().padLeft(0.3f * widgetUnitStep).row();
        cell.add(slider).expandX().fillX().height(0.5f * height)
                .padLeft(0.3f * widgetUnitStep).padRight(0.3f * widgetUnitStep);
        out[0] = slider;
        return cell;
    }

    /** A full-width slider row in one column, or a captioned half-row cell in pairs. */
    private Table sliderControl(boolean oneColumn, String text, float min, float max, float step,
                                float sliderWidth, Slider[] out, SliderChange onChange) {
        return oneColumn ? sliderRow(text, min, max, step, sliderWidth, out, onChange)
                : sliderCell(text, min, max, step, out, onChange);
    }

    /**
     * How the roads and trails look: a colour for each kind of way - tap one to step through the
     * palette - the length of the trail dashes and how fast they move, how often names and
     * numbers are written along them and whether they are written at all, and the ski pistes on
     * or off. Every change shows on the next frame, because the terrain shader reads the style every
     * frame; nothing is redrawn, and each change is saved as it is made.
     *
     * <p>Built twice, like the main menu: in pairs for a screen held sideways - six rows, the
     * sliders in half-row cells with their names above them - and in one column for a screen
     * held upright.
     */
    private Table createRoadsMenu(boolean oneColumn) {
        final Table table = new Table();
        table.center();
        table.setFillParent(true);

        List<Table> swatches = new ArrayList<>(5);
        for (final RoadStyle.Swatch swatch : RoadStyle.Swatch.values()) {
            final ImageTextButtonOptionPane button = getC().widgetGetter.getImageTextButton(
                    null, s(swatchLabelKey(swatch)), false);
            showSwatch(button, P.getRoadStyle().color(swatch));
            button.addClickListener(() -> {
                showSwatch(button, P.getRoadStyle().cycleColor(swatch));
                changer.execute(P::persistRoadStyle);
            });
            roadMenuRefreshers.add(() -> showSwatch(button, P.getRoadStyle().color(swatch)));
            swatches.add(button);
        }

        // Dash length, short on the left: a slider over the dash count, reversed, so dragging
        // right lengthens the dashes rather than multiplying them.
        float sliderWidth = oneColumn ? buttonWidth * 0.62f : buttonWidth * 1.3f;
        final Slider[] dashLength = new Slider[1];
        Table dashLengthRow = sliderControl(oneColumn, s("Road_dash_length"), RoadStyle.DASH_COUNT_MIN,
                RoadStyle.DASH_COUNT_MAX, 1f, sliderWidth, dashLength, (value, settled) -> {
                    P.getRoadStyle().setDashCount(RoadStyle.DASH_COUNT_MIN + RoadStyle.DASH_COUNT_MAX
                            - Math.round(value));
                    if (settled) {
                        changer.execute(P::persistRoadStyle);
                    }
                });
        roadMenuRefreshers.add(() -> dashLength[0].setValue(RoadStyle.DASH_COUNT_MIN
                + RoadStyle.DASH_COUNT_MAX - P.getRoadStyle().dashCount()));

        // Dash animation: still at the left end.
        final Slider[] dashSpeed = new Slider[1];
        Table dashSpeedRow = sliderControl(oneColumn, s("Road_dash_animation"), 0f, RoadStyle.DASH_SPEED_MAX, 0.05f,
                sliderWidth, dashSpeed, (value, settled) -> {
                    P.getRoadStyle().setDashSpeed(value);
                    if (settled) {
                        changer.execute(P::persistRoadStyle);
                    }
                });
        roadMenuRefreshers.add(() -> dashSpeed[0].setValue(P.getRoadStyle().dashSpeed()));

        final ImageTextButtonOptionPane checkBoxPistes = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_roads.png", s("Ski_pistes"), true);
        addCheckingStateProperty(checkBoxPistes, () -> P.getPisteVisible());
        checkBoxPistes.addClickListener(() ->
                changer.execute(() -> P.setPisteVisible(checkBoxPistes.isChecked())));
        roadMenuRefreshers.add(() -> checkBoxPistes.setChecked(P.getPisteVisible()));

        // Road and trail names on or off: the same setting as in the Labels submenu.
        final ImageTextButtonOptionPane checkBoxNames = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_roads.png", s("Road_names"), true);
        addCheckingStateProperty(checkBoxNames, () -> P.getRoadStyle().isRoadNames());
        checkBoxNames.addClickListener(() -> changer.execute(() -> {
            P.getRoadStyle().setRoadNames(checkBoxNames.isChecked());
            P.persistRoadStyle();
        }));
        roadMenuRefreshers.add(() -> checkBoxNames.setChecked(P.getRoadStyle().isRoadNames()));

        // How often names and numbers are written: fewer on the left, more on the right.
        final Slider[] labelFrequency = new Slider[1];
        Table labelFrequencyRow = sliderControl(oneColumn, s("Road_label_frequency"), RoadStyle.LABEL_FREQUENCY_MIN,
                RoadStyle.LABEL_FREQUENCY_MAX, 1f, sliderWidth, labelFrequency, (value, settled) -> {
                    P.getRoadStyle().setLabelFrequency(Math.round(value));
                    if (settled) {
                        changer.execute(P::persistRoadStyle);
                    }
                });
        roadMenuRefreshers.add(() -> labelFrequency[0].setValue(P.getRoadStyle().labelFrequency()));

        ImageTextButtonOptionPane buttonReset = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_roads.png", s("Road_style_reset"), false);
        buttonReset.addClickListener(() -> {
            RoadStyle style = P.getRoadStyle();
            style.resetColors();
            style.setDashCount(RoadStyle.DASH_COUNT_DEFAULT);
            style.setDashSpeed(RoadStyle.DASH_SPEED_DEFAULT);
            style.setLabelFrequency(RoadStyle.LABEL_FREQUENCY_DEFAULT);
            for (Runnable refresher : roadMenuRefreshers) {
                refresher.run();
            }
            changer.execute(P::persistRoadStyle);
        });

        ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton(
                "icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });

        if (oneColumn) {
            List<Table> rows = new ArrayList<>(swatches);
            rows.add(dashLengthRow);
            rows.add(dashSpeedRow);
            rows.add(labelFrequencyRow);
            rows.add(checkBoxNames);
            rows.add(checkBoxPistes);
            rows.add(buttonReset);
            rows.add(back);
            addButtonsToTable(table, rows, true, buttonWidth * 1.2f);
        } else {
            addPair(table, swatches.get(0), swatches.get(1));
            addPair(table, swatches.get(2), swatches.get(3));
            addPair(table, swatches.get(4), checkBoxPistes);
            // Six rows, as tall as the main options menu: any taller and it runs under the
            // camera and compass buttons at the top of a phone held sideways.
            addPair(table, dashLengthRow, dashSpeedRow);
            addPair(table, labelFrequencyRow, checkBoxNames);
            addPair(table, buttonReset, back);
        }

        for (Runnable refresher : roadMenuRefreshers) {
            refresher.run();
        }
        table.setVisible(false);
        return table;
    }

    /** Two menu buttons side by side, as the main menu lays them out on a wide screen. */
    private void addPair(Table table, Table left, Table right) {
        table.add(left).width(buttonWidth).height(height).padBottom(padHeight)
                .padRight(0.2f * roundButtonSize);
        table.add(right).width(buttonWidth).height(height).padBottom(padHeight).row();
    }

    private Table createInfoOptsMenu() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        float buttonWidth = this.buttonWidth * 1.2f;

        List<Table> buttons = new ArrayList<>(16);

        ImageTextButtonOptionPane buttonAppInfo = getC().widgetGetter.getImageTextButton(
                "icons/icon_info.png", s("App_info"), false);
        buttonAppInfo.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                // tableAppInfo.setVisible(true);
                getNativeScreenCaller().openAppInfoScreen();
                hide();
            }
        });
        buttons.add(buttonAppInfo);

        WidgetGetter.ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton("icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true, buttonWidth);
        table.setVisible(false);
        return table;
    }

    private Table createSatelliteSourceSelectBox() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);
        populateSatelliteSourceSelectBox(table);
        // Hidden until the user opens it. Rebuilds must not touch visibility, otherwise adding
        // or deleting a source would close the menu the user is still working in.
        table.setVisible(false);
        return table;
    }

    /**
     * (Re)builds the list of satellite sources. It has to be rebuildable rather than built once,
     * because the user can add and remove their own providers while the app is running.
     */
    private void populateSatelliteSourceSelectBox(Table table) {
        table.clearChildren();
        selectBoxSatSrcMap.clear();

        List<SatelliteImageProvider> providers = P.getSatelliteProviderRegistry().getAllProviders();

        // Only give up width for the delete column once there is something to delete. When any
        // custom source exists every row reserves it, including the built-ins that do not show
        // one, so all source buttons stay exactly the same size as each other.
        boolean anyCustom = false;
        for (SatelliteImageProvider provider : providers) {
            if (provider.isCustom()) {
                anyCustom = true;
                break;
            }
        }
        float removeWidth = anyCustom ? buttonWidth * REMOVE_BUTTON_WIDTH_FRACTION : 0f;

        // The scrollbar only appears once the list actually overflows, so reserve a lane for it
        // only then. Otherwise every source button would be permanently narrowed to make room for
        // a bar that is not there.
        float scrollBarWidth = SCROLLBAR_WIDTH_FRACTION * roundButtonSize;
        float rowHeight = height + padHeight;
        float wantedHeight = providers.size() * rowHeight;
        float maxHeight = Gdx.graphics.getHeight() * MAX_PROVIDER_LIST_SCREEN_FRACTION;
        // Show a whole number of rows: a row clipped through the middle at the bottom edge reads
        // as a rendering glitch rather than as "there is more below".
        int visibleRows = Math.max(1, (int) (maxHeight / rowHeight));
        float listHeight = Math.min(wantedHeight, visibleRows * rowHeight);
        boolean scrolls = wantedHeight > listHeight;
        float scrollBarLane = scrolls ? scrollBarWidth + SCROLLBAR_GAP_FRACTION * roundButtonSize : 0f;

        float nameWidth = buttonWidth - removeWidth - scrollBarLane;

        Table providerList = new Table();
        providerList.top();

        SatelliteImageProvider selected = P.getUnderlayImageProvider();

        for (SatelliteImageProvider provider : providers) {
            TextButton button = getC().widgetGetter.getTextButton(
                    provider.getProviderName(), true);
            button.setProgrammaticChangeEvents(false);

            if (selected != null && provider.getId().equals(selected.getId())) {
                button.setChecked(true);
                prevChecked = button;
            } else {
                button.setChecked(false);
            }

            selectBoxSatSrcMap.put(provider.getId(), button);

            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    changer.execute(
                            () -> {
                                P.setUnderlayImageProvider(provider);
                                getC().widgetGetter.setCopyrightLabel(provider.getCopyrightNotice());
                                getC().tileManager.tileRenderer.drawSatelliteLayer();
                            });
                    if (prevChecked != null)
                        prevChecked.setChecked(false);
                    button.setChecked(true);
                    prevChecked = button;
                    table.setVisible(false);
                    P.setLayerVisibleUnderlayLayer(true);
                    hide();
                }
            });

            providerList.add(button).width(nameWidth).height(height).padBottom(padHeight);

            if (provider.isCustom()) {
                // Custom entries get a delete button next to the name.
                TextButton removeButton = getC().widgetGetter.getTextButton("X", false);
                removeButton.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        P.getSatelliteProviderRegistry().removeCustomProvider(provider);
                        P.onCustomProviderRemoved(provider);
                        prevChecked = null;
                        populateSatelliteSourceSelectBox(table);
                        getC().widgetGetter.setCopyrightLabel(
                                P.getUnderlayImageProvider().getCopyrightNotice());
                    }
                });
                providerList.add(removeButton).width(removeWidth).height(height).padBottom(padHeight);
            } else if (anyCustom) {
                // Empty spacer, so built-in and custom name buttons stay the same width.
                providerList.add().width(removeWidth).height(height).padBottom(padHeight);
            }
            providerList.row();
        }

        // The list can grow without limit as the user adds sources, so it scrolls once it no
        // longer fits. "Add" and "Back" stay outside the scroll area and are always reachable.
        ScrollPane.ScrollPaneStyle scrollPaneStyle = new ScrollPane.ScrollPaneStyle();
        if (scrolls) {
            // The default drawables are a bare 10px texture, which renders as a hairline. Size
            // both track and knob explicitly so the bar is wide enough to see and to drag.
            TextureRegionDrawable track = getC().widgetTextures.getUniformDrawable(SCROLLBAR_TRACK_COLOR);
            track.setMinWidth(scrollBarWidth);
            TextureRegionDrawable knob = getC().widgetTextures.getUniformDrawable(SCROLLBAR_KNOB_COLOR);
            knob.setMinWidth(scrollBarWidth);
            scrollPaneStyle.vScroll = track;
            scrollPaneStyle.vScrollKnob = knob;
        }
        ScrollPane scrollPane = new ScrollPane(providerList, scrollPaneStyle);
        scrollPane.setScrollingDisabled(true, false);
        scrollPane.setFadeScrollBars(false);
        // Draw the bar over the widget rather than letting ScrollPane carve width out of it: the
        // lane it sits in has already been reserved above, so it lands beside the buttons with a
        // gap rather than on top of them.
        scrollPane.setScrollbarsOnTop(true);
        scrollPane.setOverscroll(false, false);

        table.add(scrollPane)
                .width(buttonWidth)
                .height(listHeight)
                .padBottom(padHeight)
                .row();

        List<Table> buttons = new ArrayList<>(2);

        WidgetGetter.ImageTextButtonOptionPane addCustom = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_satellite.png", s("Add_custom_provider"), false);
        addCustom.addClickListener(() -> promptForCustomSatelliteProvider(table));
        buttons.add(addCustom);

        WidgetGetter.ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton("icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true);
    }

    /**
     * Asks for a custom tile source. The native dialog runs on the platform UI thread, so the
     * menu is rebuilt through postRunnable to get back onto the render thread first.
     */
    private void promptForCustomSatelliteProvider(Table table) {
        NativeScreenCaller nativeScreenCaller = getNativeScreenCaller();
        if (nativeScreenCaller == null) {
            // iOS does not provide one.
            return;
        }
        nativeScreenCaller.promptForTextFields(
                s("Add_custom_provider"),
                s("Provider_url_help"),
                new String[]{s("Provider_URL_template"), s("Provider_name"), s("Provider_attribution")},
                new String[]{"", "", ""},
                new TextFieldsCallback() {
                    @Override
                    public void onEntered(String[] values) {
                        String error = P.getSatelliteProviderRegistry()
                                .addCustomProvider(values[0], values[1], values[2]);
                        Gdx.app.postRunnable(() -> {
                            if (error != null) {
                                nativeScreenCaller.makeToast(error);
                                return;
                            }
                            prevChecked = null;
                            populateSatelliteSourceSelectBox(table);
                        });
                    }

                    @Override
                    public void onCancelled() {
                    }
                });
    }

    private Table createDownloadSourceSelectBox() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);
        populateDownloadSourceSelectBox(table);
        table.setVisible(false);
        return table;
    }

    /**
     * (Re)builds the list of map-data (download) sources. They are fallback mirrors, tried in list
     * order, so there is no selection: each row is just the provider, tap to edit, with a delete
     * button. "Add" and "Back" sit below and stay reachable even when the list scrolls.
     */
    private void populateDownloadSourceSelectBox(Table table) {
        table.clearChildren();

        List<DownloadProvider> providers = getC().downloadProviderRegistry.getProviders();

        // Reserve the delete column only when something is actually removable (the built-in
        // HuggingFace default is not), so with just the default every row stays full width.
        boolean anyRemovable = false;
        for (DownloadProvider provider : providers) {
            if (!provider.builtin) {
                anyRemovable = true;
                break;
            }
        }
        float removeWidth = anyRemovable ? buttonWidth * REMOVE_BUTTON_WIDTH_FRACTION : 0f;
        float scrollBarWidth = SCROLLBAR_WIDTH_FRACTION * roundButtonSize;
        float rowHeight = height + padHeight;
        float wantedHeight = providers.size() * rowHeight;
        float maxHeight = Gdx.graphics.getHeight() * MAX_PROVIDER_LIST_SCREEN_FRACTION;
        int visibleRows = Math.max(1, (int) (maxHeight / rowHeight));
        float listHeight = Math.min(wantedHeight, visibleRows * rowHeight);
        boolean scrolls = wantedHeight > listHeight;
        float scrollBarLane = scrolls ? scrollBarWidth + SCROLLBAR_GAP_FRACTION * roundButtonSize : 0f;
        float nameWidth = buttonWidth - removeWidth - scrollBarLane;

        Table providerList = new Table();
        providerList.top();

        for (int i = 0; i < providers.size(); i++) {
            final int index = i;
            final DownloadProvider provider = providers.get(i);

            TextButton button = getC().widgetGetter.getTextButton(provider.name, false);
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    promptForEditDownloadProvider(table, index, provider);
                }
            });
            providerList.add(button).width(nameWidth).height(height).padBottom(padHeight);

            if (!provider.builtin) {
                TextButton removeButton = getC().widgetGetter.getTextButton("X", false);
                removeButton.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        getC().downloadProviderRegistry.removeProvider(index);
                        populateDownloadSourceSelectBox(table);
                    }
                });
                providerList.add(removeButton).width(removeWidth).height(height).padBottom(padHeight);
            } else if (anyRemovable) {
                // Keep built-in and removable rows the same width by reserving the empty column.
                providerList.add().width(removeWidth).height(height).padBottom(padHeight);
            }
            providerList.row();
        }

        ScrollPane.ScrollPaneStyle scrollPaneStyle = new ScrollPane.ScrollPaneStyle();
        if (scrolls) {
            TextureRegionDrawable track = getC().widgetTextures.getUniformDrawable(SCROLLBAR_TRACK_COLOR);
            track.setMinWidth(scrollBarWidth);
            TextureRegionDrawable knob = getC().widgetTextures.getUniformDrawable(SCROLLBAR_KNOB_COLOR);
            knob.setMinWidth(scrollBarWidth);
            scrollPaneStyle.vScroll = track;
            scrollPaneStyle.vScrollKnob = knob;
        }
        ScrollPane scrollPane = new ScrollPane(providerList, scrollPaneStyle);
        scrollPane.setScrollingDisabled(true, false);
        scrollPane.setFadeScrollBars(false);
        scrollPane.setScrollbarsOnTop(true);
        scrollPane.setOverscroll(false, false);

        table.add(scrollPane)
                .width(buttonWidth)
                .height(listHeight)
                .padBottom(padHeight)
                .row();

        List<Table> buttons = new ArrayList<>(2);

        WidgetGetter.ImageTextButtonOptionPane addSource = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_download_data.png", s("Add_map_data_source"), false);
        addSource.addClickListener(() -> promptForCustomDownloadProvider(table));
        buttons.add(addSource);

        WidgetGetter.ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton("icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true);
    }

    private void promptForCustomDownloadProvider(Table table) {
        NativeScreenCaller nativeScreenCaller = getNativeScreenCaller();
        if (nativeScreenCaller == null) {
            return;
        }
        nativeScreenCaller.promptForTextFields(
                s("Add_map_data_source"),
                "",
                new String[]{s("Provider_name"), s("Elevation_base_url"), s("Map_data_base_url")},
                new String[]{"", "", ""},
                new TextFieldsCallback() {
                    @Override
                    public void onEntered(String[] values) {
                        String error = getC().downloadProviderRegistry.addProvider(values[0], values[1], values[2]);
                        Gdx.app.postRunnable(() -> {
                            if (error != null) {
                                nativeScreenCaller.makeToast(error);
                                return;
                            }
                            populateDownloadSourceSelectBox(table);
                        });
                    }

                    @Override
                    public void onCancelled() {
                    }
                });
    }

    private void promptForEditDownloadProvider(Table table, int index, DownloadProvider provider) {
        NativeScreenCaller nativeScreenCaller = getNativeScreenCaller();
        if (nativeScreenCaller == null) {
            return;
        }
        nativeScreenCaller.promptForTextFields(
                s("Edit_map_data_source"),
                "",
                new String[]{s("Provider_name"), s("Elevation_base_url"), s("Map_data_base_url")},
                new String[]{
                        provider.name == null ? "" : provider.name,
                        provider.elevationBaseUrl == null ? "" : provider.elevationBaseUrl,
                        provider.mapDataBaseUrl == null ? "" : provider.mapDataBaseUrl},
                new TextFieldsCallback() {
                    @Override
                    public void onEntered(String[] values) {
                        String error = getC().downloadProviderRegistry
                                .updateProvider(index, values[0], values[1], values[2]);
                        Gdx.app.postRunnable(() -> {
                            if (error != null) {
                                nativeScreenCaller.makeToast(error);
                                return;
                            }
                            populateDownloadSourceSelectBox(table);
                        });
                    }

                    @Override
                    public void onCancelled() {
                    }
                });
    }

    private Table createSelectBoxUnitSystem() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);
        List<Table> buttons = new ArrayList<>(4);

        TextButton buttonUnitsMetric = getC().widgetGetter.getTextButton(s("Metric"), true);
        buttonUnitsMetric.setProgrammaticChangeEvents(false);
        buttonUnitsMetric.setChecked(P.getUnitSystem() == METRIC);
        buttons.add(buttonUnitsMetric);

        TextButton buttonUnitsImperial = getC().widgetGetter.getTextButton(s("Imperial"), true);
        buttonUnitsImperial.setProgrammaticChangeEvents(false);
        buttonUnitsImperial.setChecked(P.getUnitSystem() == IMPERIAL);
        buttons.add(buttonUnitsImperial);

        buttonUnitsMetric.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                P.setUnitSystemNoPersist(METRIC);
                changer.submit(() -> P.setUnitSystem(METRIC));
                buttonUnitsMetric.setChecked(true);
                buttonUnitsImperial.setChecked(false);
                table.setVisible(false);
                hide();
            }
        });
        buttonUnitsImperial.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                P.setUnitSystemNoPersist(IMPERIAL);
                changer.submit(() -> P.setUnitSystem(IMPERIAL));
                buttonUnitsMetric.setChecked(false);
                buttonUnitsImperial.setChecked(true);
                table.setVisible(false);
                hide();
            }
        });

        WidgetGetter.ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton("icons/icon_back.png", s("Back"), false);
        back.addClickListener(() -> {
            table.setVisible(false);
            show();
        });
        buttons.add(back);

        addButtonsToTable(table, buttons, true);
        table.setVisible(false);
        return table;
    }

    private Table getPreferencesTable(boolean oneColumn) {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        List<Table> buttons = new ArrayList<>(16);

        // Label visibility toggles live in their own submenu (peaks, places, alpine huts, plus the
        // ranged labels: islands, cities, mountain ranges).
        ImageTextButtonOptionPane buttonLabelsMenu = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_peak_names.png", s("Labels_menu"), false);
        buttonLabelsMenu.addClickListener(() -> {
            selectLabels.setVisible(true);
            table.setVisible(false);
            tableOneColumn.setVisible(false);
        });
        buttons.add(buttonLabelsMenu);

        ImageTextButtonOptionPane checkBoxLargeFonts = getC().widgetGetter.getImageTextButton("icons/icon_checkbox_large_fonts.png", s("Large_fonts"), true);
        addCheckingStateProperty(checkBoxLargeFonts, ()->P.getViewLargeFonts());
        checkBoxLargeFonts.addClickListener(() -> changer.execute(() -> {
            P.setViewLargeFonts(checkBoxLargeFonts.isChecked());
            getC().O.iterateOverVisiblePoisUnstoppable(poiObject -> poiObject.drawLabel.updateLabelPolygonCoordinates());
        }));
        buttons.add(checkBoxLargeFonts);

        // Compass & location: master on/off plus a "..." submenu (coordinates, horizon
        // markers, corner rose) - the same composite scheme as the sky row below.
        ImageTextButtonOptionPane checkBoxCompassLocation = getC().widgetGetter.getImageTextButton(
                "icons/icon_compass_location.png", s("Compass_location"), true);
        addCheckingStateProperty(checkBoxCompassLocation, () -> P.isCompassLocation());
        checkBoxCompassLocation.addClickListener(() -> changer.execute(
                () -> P.setCompassLocation(checkBoxCompassLocation.isChecked())));
        checkBoxCompassLocation.setProgrammaticChangeEvents(false);
        Table tableCompass = new Table();
        tableCompass.add(checkBoxCompassLocation).width(buttonWidth * 0.8f);
        TextButton buttonCompassOptions = getC().widgetGetter.getTextButton("...", false);
        buttonCompassOptions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                selectCompass.setVisible(true);
                table.setVisible(false);
            }
        });
        tableCompass.add(buttonCompassOptions).width(buttonWidth * 0.2f).height(height);
        buttons.add(tableCompass);

        // Sky & stars: a shortened on/off checkbox with a "..." options button in the same row
        // (same scheme as the satellite and map-data rows above).
        ImageTextButtonOptionPane checkBoxSky = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_sky.png", s("Sky_view"), true);
        addCheckingStateProperty(checkBoxSky, () -> P.isSkyView());
        checkBoxSky.addClickListener(() -> changer.execute(
                () -> P.setSkyView(checkBoxSky.isChecked())));
        checkBoxSky.setProgrammaticChangeEvents(false);
        Table tableSky = new Table();
        tableSky.add(checkBoxSky).width(buttonWidth * 0.8f);
        TextButton buttonSkyOptions = getC().widgetGetter.getTextButton("...", false);
        buttonSkyOptions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                selectSky.setVisible(true);
                table.setVisible(false);
            }
        });
        tableSky.add(buttonSkyOptions).width(buttonWidth * 0.2f).height(height);
        buttons.add(tableSky);

        // GPX paths: a single entry that opens its own submenu (load file / from URL / clear).
        ImageTextButtonOptionPane buttonGpxMenu = getC().widgetGetter.getImageTextButton(
                "icons/icon_map.png", s("Gpx_paths"), false);
        buttonGpxMenu.addClickListener(() -> {
            selectGpx.setVisible(true);
            table.setVisible(false);
            tableOneColumn.setVisible(false);
        });
        buttons.add(buttonGpxMenu);

        ImageTextButtonOptionPane checkBoxLayerVisibleBaseRoads = getC().widgetGetter.getImageTextButton("icons/icon_checkbox_roads.png", s("Base_Roads"), true);
        addCheckingStateProperty(checkBoxLayerVisibleBaseRoads, () -> P.isViewerLayerVisibleBaseRoads());
        checkBoxLayerVisibleBaseRoads.addClickListener(() -> changer.execute(() -> {
            boolean checked = checkBoxLayerVisibleBaseRoads.isChecked();
            P.setViewerLayerVisibleBaseRoads(checked);
            if (checked) {
                boolean missingData = getC().checkMissingData.checkMissingDataForCoord(
                        getC().L.getCurrentLatitude(), getC().L.getCurrentLongitude());
                if (missingData) {
                    getNativeScreenCaller().askForDownloadScreen(
                            getC().L.getCurrentLatitude(), getC().L.getCurrentLongitude()
                    );
                }
            }
            if (checked) {
                getC().tileManager.startAerialAndDataRenderExecutors();
            }
        }));
        checkBoxLayerVisibleBaseRoads.setProgrammaticChangeEvents(false);
        // Roads & paths: on/off plus a "..." submenu for their colours, dashes and pistes -
        // the same composite scheme as the satellite and sky rows.
        Table tableRoads = new Table();
        tableRoads.add(checkBoxLayerVisibleBaseRoads).width(buttonWidth * 0.8f);
        TextButton buttonRoadOptions = getC().widgetGetter.getTextButton("...", false);
        buttonRoadOptions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                for (Runnable refresher : roadMenuRefreshers) {
                    refresher.run();
                }
                boolean wide = Gdx.graphics.getWidth() > Gdx.graphics.getHeight();
                selectRoads.setVisible(wide);
                selectRoadsOneColumn.setVisible(!wide);
                table.setVisible(false);
            }
        });
        tableRoads.add(buttonRoadOptions).width(buttonWidth * 0.2f).height(height);
        buttons.add(tableRoads);

        ImageTextButtonOptionPane checkBoxLayerVisibleUnderlayLayer = getC().widgetGetter.getImageTextButton("icons/icon_checkbox_satellite.png", s("Satellite_images"), true);
        addCheckingStateProperty(checkBoxLayerVisibleUnderlayLayer, ()->P.isLayerVisibleUnderlayLayer());
        checkBoxLayerVisibleUnderlayLayer.addClickListener(() -> {
            /*
            if (!getAdUtils().isSubscribed()) {
                // getNativeScreenCaller().openSubscribeDialog();
                // Unsubscribe tile provider:
                P.setUnderlayImageProvider(SatelliteProviderOptions.USGS_SATELLITE);
                // checkBoxLayerVisibleUnderlayLayer.setChecked(false);
            }
             */
            changer.execute(() -> {
                boolean checked = checkBoxLayerVisibleUnderlayLayer.isChecked();
                P.setLayerVisibleUnderlayLayer(checked);
                if (checked) {
                    getC().tileManager.startAerialAndDataRenderExecutors();
                }
            });
        });
        checkBoxLayerVisibleUnderlayLayer.setProgrammaticChangeEvents(false);
        Table tableSatelliteVisible = new Table();
        tableSatelliteVisible.add(checkBoxLayerVisibleUnderlayLayer).width(buttonWidth*0.8f);
        TextButton buttonSatelliteOptions = getC().widgetGetter.getTextButton("...", false);
        buttonSatelliteOptions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                checkSelectBoxSatSrcSelection();
                selectBoxSatSrc.setVisible(true);
                table.setVisible(false);
            }
        });
        tableSatelliteVisible.add(buttonSatelliteOptions).width(buttonWidth*0.2f).height(height);
        buttons.add(tableSatelliteVisible);

        ImageTextButtonOptionPane checkBoxSunShading = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_sun.png", s("Sun_shading"), true);
        addCheckingStateProperty(checkBoxSunShading, () -> P.isSunShading());
        // The shader reads the preference every frame, so the terrain updates without a redraw.
        checkBoxSunShading.addClickListener(() -> changer.execute(
                () -> P.setSunShading(checkBoxSunShading.isChecked())));
        checkBoxSunShading.setProgrammaticChangeEvents(false);
        buttons.add(checkBoxSunShading);

        ImageTextButtonOptionPane buttonMapDataDownload = getC().widgetGetter.getImageTextButton("icons/icon_checkbox_download_data.png", s("Download_map_data"), false);
        buttonMapDataDownload.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                mapApp.nativeScreenCaller.openMapDataDownloadChooser();
                mapApp.mapViewerScreen.optionPane.hide();
            }
        });
        Table tableMapData = new Table();
        tableMapData.add(buttonMapDataDownload).width(buttonWidth * 0.8f);
        TextButton buttonMapDataSources = getC().widgetGetter.getTextButton("...", false);
        buttonMapDataSources.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                populateDownloadSourceSelectBox(selectBoxDownloadSrc);
                selectBoxDownloadSrc.setVisible(true);
                table.setVisible(false);
            }
        });
        tableMapData.add(buttonMapDataSources).width(buttonWidth * 0.2f).height(height);
        buttons.add(tableMapData);

        ImageTextButtonOptionPane buttonUnits = getC().widgetGetter.getImageTextButton(
                "icons/icon_units.png", s("Units"), false);
        buttonUnits.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                selectBoxUnits.setVisible(true);
                table.setVisible(false);
            }
        });
        buttons.add(buttonUnits);

        Table tableInfo = new Table();
        String textInfo = s("App_info");
        ImageTextButtonOptionPane buttonAppInfo = getC().widgetGetter.getImageTextButton(
                "icons/icon_info.png", textInfo, false);
        addCheckingStateProperty(
                buttonAppInfo, () -> {
                    buttonAppInfo.getLabel().setText(textInfo);
                    return true;
                }
        );
        buttonAppInfo.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                // tableAppInfo.setVisible(true);
                buttonAppInfo.getLabel().setText(textInfo);
                getNativeScreenCaller().openAppInfoScreen();
                hide();
            }
        });
        tableInfo.add(buttonAppInfo).width(buttonWidth*0.8f).height(height);
        TextButton buttonInfoOptions = getC().widgetGetter.getTextButton("...", false);
        buttonInfoOptions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                selectInfoOpts.setVisible(true);
                table.setVisible(false);
            }
        });
        tableInfo.add(buttonInfoOptions).width(buttonWidth*0.2f).height(height);
        buttons.add(tableInfo);

        ImageTextButtonOptionPane back = getC().widgetGetter.getImageTextButton("icons/icon_back.png", s("Back"), false);
        back.addClickListener(this::hide);
        buttons.add(back);

        addButtonsToTable(table, buttons, oneColumn);

        return table;
    }

    private void checkSelectBoxSatSrcSelection() {
        SatelliteImageProvider provider = P.getUnderlayImageProvider();
        String selectedId = provider == null ? null : provider.getId();
        for (Map.Entry<String, TextButton> entry : selectBoxSatSrcMap.entrySet()) {
            if (entry.getKey().equals(selectedId)) {
                entry.getValue().setChecked(true);
            } else {
                entry.getValue().setChecked(false);
            }
        }
    }

    Map<Button, Callable<Boolean>> checkingStateMap = new HashMap<>();

    private void addCheckingStateProperty(Button toggable, Callable<Boolean> callable) {
        toggable.setProgrammaticChangeEvents(false);
        checkingStateMap.put(toggable, callable);
    }

    private void updateCheckingStates() {
        for (Map.Entry<Button, Callable<Boolean>> entry : checkingStateMap.entrySet()) {
            try {
                entry.getKey().setChecked(entry.getValue().call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void addButtonsToTable(Table table, List<Table> buttons, boolean oneColumn) {
        addButtonsToTable(table, buttons, oneColumn, buttonWidth);
    }

    private void addButtonsToTable(Table table, List<Table> buttons, boolean oneColumn, float buttonWidth) {

        for (int j = 0; j < buttons.size(); j++) {
            int i = j;
            if (buttons.size() % 2 == 0 && j >= buttons.size() - 2 && !oneColumn) {
                if (j % 2 == 0) {
                    i++;
                } else {
                    i--;
                }
            }
            Table button = buttons.get(i);
            Cell<Table> cell = table.add(button).width(buttonWidth).height(height).uniformX();
            cell.padBottom(padHeight);
            if (j == buttons.size() - 1)
                break;
            if (oneColumn || j % 2 == 1) {
                cell.row();
            } else {
                cell.padRight(0.2f*roundButtonSize);
            }
        }
    }

    public void show() {
        updateCheckingStates();
        if (Gdx.graphics.getWidth() > Gdx.graphics.getHeight()) {
            table.setVisible(true);
            tableOneColumn.setVisible(false);
        } else {
            table.setVisible(false);
            tableOneColumn.setVisible(true);
        }
        selectBoxSatSrc.setVisible(false);
        selectBoxDownloadSrc.setVisible(false);
        selectBoxUnits.setVisible(false);
        selectInfoOpts.setVisible(false);
        selectGpx.setVisible(false);
        selectLabels.setVisible(false);
        selectSky.setVisible(false);
        selectCompass.setVisible(false);
        selectRoads.setVisible(false);
        selectRoadsOneColumn.setVisible(false);
        // tableAppInfo.setVisible(false);

        optionsButton.setChecked(true);
    }

    public void hide() {
        table.setVisible(false);
        tableOneColumn.setVisible(false);
        selectBoxSatSrc.setVisible(false);
        selectBoxDownloadSrc.setVisible(false);
        selectBoxUnits.setVisible(false);
        selectInfoOpts.setVisible(false);
        selectGpx.setVisible(false);
        selectLabels.setVisible(false);
        selectSky.setVisible(false);
        selectCompass.setVisible(false);
        selectRoads.setVisible(false);
        selectRoadsOneColumn.setVisible(false);
        // tableAppInfo.setVisible(false);
        optionsButton.setChecked(false);
        changer.submit(() -> getC().widgetGetter.setCopyrightLabel(
                        P.getUnderlayImageProvider().getCopyrightNotice()));
    }

    public Table getTable() {
        return table;
    }
}
