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
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
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

    private Table createInfoOptsMenu() {
        Table table = new Table();
        table.center();
        table.setFillParent(true);

        float buttonWidth = this.buttonWidth * 1.2f;

        List<Table> buttons = new ArrayList<>(16);

        TextButton buttonAppInfo = getC().widgetGetter.getTextButton(s("App_info"), false);
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

        ImageTextButtonOptionPane checkBoxShowPeaks = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_peak_names.png", s("Peak_names"), true);
        addCheckingStateProperty(checkBoxShowPeaks, ()->P.isPeakVisible());
        checkBoxShowPeaks.addClickListener(() -> {
            changer.execute(() -> P.setPeakVisible(checkBoxShowPeaks.isChecked()));
        });
        buttons.add(checkBoxShowPeaks);

        ImageTextButtonOptionPane checkBoxShowPlaces = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_place_names.png", s("Place_names"), true);
        addCheckingStateProperty(checkBoxShowPlaces, ()->P.isVisiblePlaceNames());
        checkBoxShowPlaces.addClickListener(() -> changer.execute(() -> {
            P.setVisiblePlaceNames(checkBoxShowPlaces.isChecked());
        }));
        buttons.add(checkBoxShowPlaces);

        ImageTextButtonOptionPane checkBoxShowAlpineHuts = getC().widgetGetter.getImageTextButton(
                "icons/icon_checkbox_alpine_huts.png", s("Alpine_huts"), true);
        addCheckingStateProperty(checkBoxShowAlpineHuts, ()->P.isVisibleAlpineHuts());
        checkBoxShowAlpineHuts.addClickListener(() -> changer.execute(() -> {
            P.setVisibleAlpineHuts(checkBoxShowAlpineHuts.isChecked());
        }));
        buttons.add(checkBoxShowAlpineHuts);

        ImageTextButtonOptionPane checkBoxLargeFonts = getC().widgetGetter.getImageTextButton("icons/icon_checkbox_large_fonts.png", s("Large_fonts"), true);
        addCheckingStateProperty(checkBoxLargeFonts, ()->P.getViewLargeFonts());
        checkBoxLargeFonts.addClickListener(() -> changer.execute(() -> {
            P.setViewLargeFonts(checkBoxLargeFonts.isChecked());
            getC().O.iterateOverVisiblePoisUnstoppable(poiObject -> poiObject.drawLabel.updateLabelPolygonCoordinates());
        }));
        buttons.add(checkBoxLargeFonts);

        ImageTextButtonOptionPane checkBoxHorizonCompass = getC().widgetGetter.getImageTextButton(
                "icons/icon_compass.png", s("Horizon_compass"), true);
        addCheckingStateProperty(checkBoxHorizonCompass, () -> P.isHorizonCompass());
        checkBoxHorizonCompass.addClickListener(() -> changer.execute(
                () -> P.setHorizonCompass(checkBoxHorizonCompass.isChecked())));
        buttons.add(checkBoxHorizonCompass);

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
        buttons.add(checkBoxLayerVisibleBaseRoads);

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

        TextButton buttonUnits = getC().widgetGetter.getTextButton(s("Units"), false);
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
        TextButton buttonAppInfo = getC().widgetGetter.getTextButton(
                textInfo, false);
        addCheckingStateProperty(
                buttonAppInfo, () -> {
                    buttonAppInfo.setText(textInfo);
                    return true;
                }
        );
        buttonAppInfo.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                // tableAppInfo.setVisible(true);
                buttonAppInfo.setText(textInfo);
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
        // tableAppInfo.setVisible(false);
        optionsButton.setChecked(false);
        changer.submit(() -> getC().widgetGetter.setCopyrightLabel(
                        P.getUnderlayImageProvider().getCopyrightNotice()));
    }

    public Table getTable() {
        return table;
    }
}
