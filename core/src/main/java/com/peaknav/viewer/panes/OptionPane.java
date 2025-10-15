package com.peaknav.viewer.panes;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.widgets.WidgetGetter;
import static com.peaknav.viewer.widgets.WidgetGetter.ImageTextButtonOptionPane;

import static com.peaknav.utils.PreferencesManager.UnitSystem.IMPERIAL;
import static com.peaknav.utils.PreferencesManager.UnitSystem.METRIC;
import static com.peaknav.viewer.imgmapprovider.SatelliteImageProvider.SatelliteProviderOptions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
    private final EnumMap<SatelliteProviderOptions, TextButton> selectBoxSatSrcMap = new EnumMap<>(SatelliteProviderOptions.class);

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

    private final Table selectBoxUnits;

    public OptionPane(Button optionsButton, float widgetUnitStep) {
        this.optionsButton = optionsButton;
        this.widgetUnitStep = widgetUnitStep;
        mapApp = MapViewerSingleton.getAppInstance();

        roundButtonSize = widgetUnitStep / 2f;
        padHeight = 0.2f*roundButtonSize;
        height = 2f*roundButtonSize;
        buttonWidth = 6.0f*widgetUnitStep;

        selectBoxSatSrc = createSatelliteSourceSelectBox();
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
        List<Table> buttons = new ArrayList<>(16);

        SatelliteProviderOptions defaultOption = P.getUnderlayImageProvider();

        for (SatelliteProviderOptions option : SatelliteProviderOptions.values()) {
            TextButton button = getC().widgetGetter.getTextButton(
                    option.getProviderName(), true);
            button.setProgrammaticChangeEvents(false);

            if (option == defaultOption) {
                button.setChecked(true);
                prevChecked = button;
            } else {
                button.setChecked(false);
            }

            selectBoxSatSrcMap.put(option, button);

            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    changer.execute(
                            () -> {
                                // if (true || !button.isChecked()) {
                                P.setUnderlayImageProvider(option);
                                getC().widgetGetter.setCopyrightLabel(option.getCopyrightNotice());
                                getC().tileManager.tileRenderer.drawSatelliteLayer();
                                // }
                            });
                    prevChecked.setChecked(false);
                    button.setChecked(true);
                    prevChecked = button;
                    table.setVisible(false);
                    P.setLayerVisibleUnderlayLayer(true);
                    hide();
                }
            });
            buttons.add(button);
        }

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

        ImageTextButtonOptionPane buttonMapDataDownload = getC().widgetGetter.getImageTextButton("icons/icon_checkbox_download_data.png", s("Download_map_data"), false);
        buttonMapDataDownload.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                mapApp.nativeScreenCaller.openMapDataDownloadChooser();
                mapApp.mapViewerScreen.optionPane.hide();
            }
        });
        buttons.add(buttonMapDataDownload);

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
        SatelliteProviderOptions provider = P.getUnderlayImageProvider();
        for (Map.Entry<SatelliteProviderOptions, TextButton> entry : selectBoxSatSrcMap.entrySet()) {
            if (entry.getKey() == provider) {
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
        selectBoxUnits.setVisible(false);
        selectInfoOpts.setVisible(false);
        // tableAppInfo.setVisible(false);

        optionsButton.setChecked(true);
    }

    public void hide() {
        table.setVisible(false);
        tableOneColumn.setVisible(false);
        selectBoxSatSrc.setVisible(false);
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
