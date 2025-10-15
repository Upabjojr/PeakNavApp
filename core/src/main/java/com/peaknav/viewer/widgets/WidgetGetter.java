package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.utils.Units.formatDistanceToUnitSystem;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.peaknav.viewer.MapApp;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapViewerSingleton;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class WidgetGetter {
    protected final float widgetUnitStep;
    protected final float Bheight;
    protected final float borderPad;
    protected final MapApp mapApp;

    private final TextureRegionDrawable textureRegionChecked;
    private final TextureRegionDrawable textureRegionUnchecked;
    private Label copyrightLabel;
    private String copyrightNotice = "";
    private TableLocation tableLocation;

    public WidgetGetter(MapApp mapApp, float widgetUnitStep) {
        this.mapApp = mapApp;
        this.widgetUnitStep = widgetUnitStep;
        this.Bheight = 0.7f*widgetUnitStep;
        this.borderPad = 0.3f*widgetUnitStep;

        textureRegionChecked = getC().widgetTextures.getUniformDrawable(Color.GREEN);
        textureRegionUnchecked = getC().widgetTextures.getUniformDrawable(Color.WHITE);
    }

    public Table getTableWatermark() {
        Table tableWatermark = new Table();

        tableWatermark.left().top();
        tableWatermark.setFillParent(true);

        Label.LabelStyle labelStyle = getC().styleSingleton.getLabelWatermarkStyle();
        Cell<Label> cell = null;

        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 12; j++) {
                Label labelWatermark = new Label("peaknav.com", labelStyle);

                cell = tableWatermark
                        .add(labelWatermark)
                        .padLeft(1.5f * widgetUnitStep)
                        .padRight(1.5f * widgetUnitStep)
                        .padTop(1f * widgetUnitStep)
                        .padBottom(1f * widgetUnitStep);
            }
            if (cell != null)
                cell.row();
        }
        return tableWatermark;
    }

    public Table getTableCopyright() {
        Label.LabelStyle labelStyleVerySmall = new Label.LabelStyle();
        labelStyleVerySmall.font = getC().styleSingleton.getBitmapFontVerySmall();
        copyrightLabel = new Label(
                P.getUnderlayImageProvider().getCopyrightNotice(), labelStyleVerySmall);

        Table tableCopyright = new Table();
        tableCopyright.setFillParent(true);
        tableCopyright.right().bottom();
        tableCopyright.add(copyrightLabel);
        return tableCopyright;
    }

    public void setCopyrightLabel(String copyrightNotice) {
        this.copyrightNotice = copyrightNotice;
        tableLocation.copyrightLabel.setText(copyrightNotice);
        copyrightLabel.setText(copyrightNotice);
    }

    public static class HyperlinkLabel extends Table {
        private final Label label;
        private final String url;

        public HyperlinkLabel(String text, Label.LabelStyle style, String url) {
            super();
            this.url = url;

            if (style == null) {
                style = getC().styleSingleton.getLabelStyleHyperlink();
            }

            label = new Label(text, style);
            label.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    Gdx.net.openURI(url);
                }
            });

            add(label);
        }

        public void setText(CharSequence text) {
            label.setText(text);
        }
    }

    public static class ImageTextButtonOptionPane extends ImageTextButton {

        public ImageTextButtonOptionPane(String text, ImageTextButtonStyle style) {
            super(text, style);
        }

        public void addClickListener(Runnable runnable) {
            EventListener listener = new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    runnable.run();
                    getC().dataRetrieveThreadManager.triggerUpdateVisibilityElevationChanged();
                }
            };
            addListener(listener);
        }
    }

    public ImageTextButtonOptionPane getImageTextButton(String internalPath, String text, boolean toggable) {
        ImageTextButton.ImageTextButtonStyle style = new ImageTextButton.ImageTextButtonStyle();

        if (toggable) {
            style.checked = textureRegionChecked;
        }
        style.down = textureRegionChecked;
        style.up = textureRegionUnchecked;

        float marginWidth = 0.3f*widgetUnitStep;

        style.down.setRightWidth(marginWidth);
        style.up.setRightWidth(marginWidth);

        style.down.setLeftWidth(marginWidth);
        style.up.setLeftWidth(marginWidth);

        style.font = getC().styleSingleton.getBitmapFontSmall();
        TextureRegionDrawable drawable;
        if (internalPath == null) {
            drawable = getC().widgetTextures.getUniformDrawable(Color.WHITE);
        } else {
            drawable = getC().widgetTextures.getTextureRegionDrawable(internalPath);
        }
        drawable.setMinWidth(widgetUnitStep);
        drawable.setMinHeight(widgetUnitStep);
        style.imageChecked = drawable;
        style.imageUp = drawable;
        return new ImageTextButtonOptionPane(text, style);
    }

    public TextButton getTextButton(String text, boolean toggable) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        if (toggable) {
            style.checked = textureRegionChecked;
        }
        style.down = textureRegionChecked;
        style.up = textureRegionUnchecked;

        float marginWidth = 0.3f*widgetUnitStep;

        style.down.setRightWidth(marginWidth);
        style.up.setRightWidth(marginWidth);

        style.down.setLeftWidth(marginWidth);
        style.up.setLeftWidth(marginWidth);

        style.font = getC().styleSingleton.getBitmapFontSmall();
        return new TextButton(text, style);
    }

    public class TableTool extends TableContainer {
        public final Slider sliderElevation;
        public final Table tableCameraControl;
        public final Slider sliderCameraAlpha;
        public final Button buttonOrientation;
        private boolean refreshNeeded;

        TableTool() {
            table.setFillParent(true);
            table.left().top().row();

            Button buttonGalleryPick = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_gallery.png", null);
            buttonGalleryPick.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    getNativeScreenCaller().openGalleryPick();
                }
            });
            table.add(buttonGalleryPick).left().width(widgetUnitStep).height(widgetUnitStep)
                    .padTop(borderPad).padLeft(borderPad).padRight(2*widgetUnitStep);

            Button buttonCameraPicture = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_camera.png", null);
            buttonCameraPicture.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    getNativeScreenCaller().openCameraPictureView();
                }
            });
            table.add(buttonCameraPicture).width(widgetUnitStep).left().height(widgetUnitStep)
                    .padTop(borderPad)
                    //.padLeft(borderPad)
                    .row();

            Slider.SliderStyle sliderStyle = getC().styleSingleton.getSliderStyle();
            sliderElevation = new Slider(0f, 100f, 0.1f, true, sliderStyle);
            sliderElevation.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    float visualPerc = sliderElevation.getVisualPercent();
                    mapApp.mapViewerScreen.setCameraElevationBar(visualPerc);
                    float ele = mapApp.mapViewerScreen.cam.position.z - (float)getC().L.getCurrentTerrainEle() - mapApp.mapViewerScreen.LIFT_ELEV;
                    float eleMeters = Units.convertLatitsToMeters(ele);

                    mapApp.mapViewerScreen.toast(
                            " +" + formatDistanceToUnitSystem(eleMeters) + " ");
                }
            });
            table.add(sliderElevation).expandY()
                    .width(widgetUnitStep).left().height(widgetUnitStep *6)
                    .padLeft(borderPad)
                    .row();

            buttonOrientation = getC().widgetTextures.getButtonWithIcon("icons/icon_gyro.png", "icons/icon_gyro_pressed.png");
            // buttonOrientation.setProgrammaticChangeEvents(false);
            buttonOrientation.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    // buttonOrientation.setChecked(!buttonOrientation.isChecked());
                    if (buttonOrientation.isChecked()) {
                        getNativeScreenCaller().getOrientationPointerListener().start();
                    } else {
                        getNativeScreenCaller().getOrientationPointerListener().stop();
                        mapApp.mapViewerScreen.moveCameraAction.setCameraUp(Vector3.Z, true);
                    }
                }
            });
            table.add(buttonOrientation).width(widgetUnitStep).height(widgetUnitStep)
                    .padLeft(borderPad).padBottom(borderPad)
                    .padRight(2*widgetUnitStep);

            // table.add(buttonCameraPicture).width(widgetUnitStep).height(widgetUnitStep)
                    // .padLeft(borderPad).padBottom(borderPad);
                    //.row();

            tableCameraControl = new Table();
            Slider.SliderStyle sliderStyleCA = new Slider.SliderStyle();
            float w = Gdx.graphics.getHeight()*0.05f;
            sliderStyleCA.knob = getC().widgetTextures.getTextureRegionDrawable("icons/icon_slider_alpha.png");
            sliderStyleCA.knob.setMinHeight(w);
            sliderStyleCA.knob.setMinWidth(w);
            sliderStyleCA.background = getC().widgetTextures.getNinePatchDrawable("icons/slider_nine_patch.png");

            sliderCameraAlpha = new Slider(0f, 1f, 0.05f, false, sliderStyleCA);
            sliderCameraAlpha.setVisualPercent(1.0f);
            sliderCameraAlpha.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    float alpha = sliderCameraAlpha.getVisualPercent();
                    MapViewerSingleton.getViewerInstance().labelRenderer.setBackgroundAlpha(alpha);
                }
            });
            tableCameraControl.add(sliderCameraAlpha).width(3*widgetUnitStep).height(widgetUnitStep)
                    .padLeft(borderPad).padBottom(borderPad);

            Button buttonCameraCancel = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_x.png", null
            );
            buttonCameraCancel.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    hideTableCameraControl();
                }
            });
            tableCameraControl.add(buttonCameraCancel).width(widgetUnitStep).height(widgetUnitStep)
                    .padLeft(borderPad).padBottom(borderPad);
            tableCameraControl.setVisible(false);

            table.add(tableCameraControl);
        }

        public void hideTableCameraControl() {
            MapViewerSingleton.getViewerInstance().backgroundPicManager.setBackgroundPixmap(null);
            MapViewerSingleton.getViewerInstance().backgroundPicManager.setBackgroundTexture(null);
            tableCameraControl.setVisible(false);
        }

        public void setRefreshNeeded(boolean refreshNeeded) {
            this.refreshNeeded = refreshNeeded;
        }

        public boolean isRefreshNeeded() {
            return refreshNeeded;
        }
    }

    public TableTool getTableTool() {
        return new TableTool();
    }

    public class TableDownloadData extends TableContainer {

        public TableDownloadData() {

            Button textButton = getImageTextButton(
                    "icons/icon_checkbox_download_data.png",
                    s("Missing_data_prompt_download"), false);
            textButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    Executor executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        getC().checkMissingData.downloadMissingData(
                                getC().L.getTargetLatitude(),
                                getC().L.getTargetLongitude()
                        );
                        table.setVisible(false);
                    });
                    table.setVisible(false);
                }
            });
            Button dismiss = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_x.png", null
            );
            // TextButton dismiss = new TextButton(s("Missing_data_dismiss"), textButtonStyle);
            dismiss.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    getC().checkMissingData.dismiss(
                            getC().L.getTargetLatitude(),
                            getC().L.getTargetLongitude()
                    );
                    table.setVisible(false);
                }
            });
            table.top().padTop(2*borderPad+widgetUnitStep).left();
            table.setFillParent(true);
            // table.add(label).padLeft(borderPad + widgetUnitStep).padTop(2*borderPad+widgetUnitStep).left().row();
            table.add(textButton).padLeft(borderPad + 1.5f*widgetUnitStep)
                    .left().padRight(borderPad);
            table.add(dismiss).width(widgetUnitStep).height(widgetUnitStep).left().row();
            table.setVisible(false);
        }
    }

    public TableDownloadData getTableDownloadData() {
        return new TableDownloadData();
    }

    public class TableLocation extends TableContainer {

        public final Button optionsButton;
        private final Button hereButton;
        private final TextureRegionDrawable icon_here;
        private final TextureRegionDrawable icon_here_gps;
        public final Button buttonGoToDest;
        private final Button buttonCancelGoToDest;
        public final Table tableCancelGoToDest;
        public final Label copyrightLabel;
        public final Table progressBarTable;
        public final ProgressBar progressBar;

        public void setButtonHereFromGps() {
            if (getC().L.isTargetSetFromGPS()) {
                hereButton.getStyle().up = icon_here_gps;
            } else {
                hereButton.getStyle().up = icon_here;
            }
            hereButton.invalidate();
        }

        public TableLocation() {

            table.right().top();
            table.setFillParent(true);

            progressBarTable = new Table();
            progressBarTable.top().right();
            progressBarTable.setFillParent(true);
            progressBarTable.setVisible(false);

            ProgressBar.ProgressBarStyle progressBarStyle = new ProgressBar.ProgressBarStyle();

            Pixmap pixmap = new Pixmap(Math.round(widgetUnitStep), Math.round(widgetUnitStep), Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.GREEN);
            pixmap.fill();
            progressBarStyle.knobBefore = new TextureRegionDrawable(new Texture(pixmap));
            pixmap.dispose();

            pixmap = new Pixmap(Math.round(widgetUnitStep), Math.round(widgetUnitStep), Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.RED);
            pixmap.fill();
            progressBarStyle.knobAfter = new TextureRegionDrawable(new Texture(pixmap));
            pixmap.dispose();

            Label.LabelStyle labelStyleVerySmall = new Label.LabelStyle();
            labelStyleVerySmall.font = getC().styleSingleton.getBitmapFontVerySmall();
            Label downloadLabel = new Label(
                    s("Download_in_progress"), labelStyleVerySmall);
            progressBarTable.add(downloadLabel).padTop(0.3f*borderPad).height(0.7f*borderPad)
                    .padRight(3.2f*widgetUnitStep + borderPad)
                    .width(2*widgetUnitStep).right().row();
            progressBar = new ProgressBar(0f, 1f, 0.01f, false, progressBarStyle);
            progressBar.setValue(0.f);
            // progressBar.setAnimateDuration(1.f);
            progressBarTable.add(progressBar).padTop(0).padRight(3.2f*widgetUnitStep + borderPad)
                    .width(2*widgetUnitStep).height(widgetUnitStep).right();

            Button buttonSearch = getC().widgetTextures.getButtonWithIcon("icons/icon_search.png");
            buttonSearch.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    mapApp.nativeScreenCaller.openScreenSearchLocation(null);
                    // mapApp.setScreen(new SearchScreen(mapApp.mapViewerScreen));
                }
            });
            table.add(buttonSearch).width(widgetUnitStep).height(widgetUnitStep)
                    .right()
                    .padTop(borderPad)
                    .padRight(borderPad)
                    .row();

            optionsButton = getC().widgetTextures.getButtonWithIcon("icons/icon_options.png", "icons/icon_options_checked.png");
            optionsButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (optionsButton.isChecked()) {
                        mapApp.mapViewerScreen.optionPane.show();
                    } else {
                        mapApp.mapViewerScreen.optionPane.hide();
                    }
                }
            });
            table.add(optionsButton).width(widgetUnitStep).height(widgetUnitStep).expandY()
                    .right()
                    .padRight(borderPad)
                    .row();

            tableCancelGoToDest = new Table();
            // tableCancelGoToDest.right();
            buttonCancelGoToDest = getC().widgetTextures.getButtonWithIcon("icons/icon_x.png");
            buttonCancelGoToDest.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    mapApp.mapViewerScreen.removeImpact();
                }
            });
            tableCancelGoToDest.add(buttonCancelGoToDest).width(widgetUnitStep)
                    .height(widgetUnitStep).padRight(0.85f*widgetUnitStep);
            buttonGoToDest = getC().widgetTextures.getButtonWithIcon("icons/icon_go_to_dest.png");
            buttonGoToDest.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    getC().getMapViewerScreen().tableTool.buttonOrientation.setChecked(false);
                    Vector3 impactLifted = mapApp.mapViewerScreen.impact.cpy();
                    impactLifted.z += mapApp.mapViewerScreen.LIFT_ELEV;
                    mapApp.mapViewerScreen.moveCameraAction.setCameraVectors(
                            impactLifted,
                            mapApp.mapViewerScreen.cam.direction,
                            mapApp.mapViewerScreen.cam.up,
                            false,
                            Interpolation.fastSlow,
                            false
                    );
                    Vector3 newDir = mapApp.mapViewerScreen.cam.direction.cpy().scl(-1);
                    // Don't watch too high:
                    final float Z_LIMIT = 0.2f;
                    if (newDir.z > Z_LIMIT) {
                        newDir.z = Z_LIMIT;
                        float scl = (float) Math.sqrt(
                                (1-Z_LIMIT*Z_LIMIT)/(newDir.x*newDir.x + newDir.y*newDir.y));
                        newDir.x *= scl;
                        newDir.y *= scl;
                        // newDir.nor();
                    }
                    mapApp.mapViewerScreen.moveCameraAction.setCameraVectors(
                            null,
                            newDir,
                            mapApp.mapViewerScreen.cam.up,
                            false,
                            Interpolation.fastSlow,
                            true
                    );

                    mapApp.mapViewerScreen.removeImpact();
                }
            });
            tableCancelGoToDest.add(buttonGoToDest).width(widgetUnitStep)
                    .height(widgetUnitStep);
            table.add(tableCancelGoToDest).right().expandY()
                    .padRight(borderPad)
                    .row();

            Button shareButton = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_share.png", null);
            shareButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    mapApp.mapViewerScreen.takeSnapshot();
                }
            });
            table.add(shareButton).width(widgetUnitStep).height(widgetUnitStep).expandY()
                    .right()
                    .padRight(borderPad)
                    .row();

            Table tableBottomRight = new Table();
            // Label.LabelStyle labelStyleVerySmall = new Label.LabelStyle();
            // labelStyleVerySmall.font = getC().styleSingleton.getBitmapFontVerySmall();
            copyrightLabel = new Label(
                    P.getUnderlayImageProvider().getCopyrightNotice(), labelStyleVerySmall);

            tableBottomRight.add(copyrightLabel).bottom().padRight(0.5f*widgetUnitStep);
            Button helpButton = getC().widgetTextures.getButtonWithIcon("icons/icon_help.png");
            helpButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    getNativeScreenCaller().openAppTutorial();
                }
            });
            tableBottomRight.add(helpButton).width(widgetUnitStep).height(widgetUnitStep).padRight(0.5f*widgetUnitStep);
            hereButton = getC().widgetTextures.getButtonWithIcon("icons/icon_here.png");
            icon_here = getC().widgetTextures
                    .getTextureRegionDrawable("icons/icon_here.png");
            icon_here_gps = getC().widgetTextures
                    .getTextureRegionDrawable("icons/icon_here_gps.png");
            hereButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    mapApp.nativeScreenCaller.ensureLocationPermissions();
                    mapApp.nativeScreenCaller.getCallOnUIThread(
                            () -> {
                                getNativeScreenCaller()
                                        .getCurrentLocationListener()
                                        .getCurrentLocation(
                                                (longitude, latitude) -> getC().L.setCurrentTargetCoordsFromGPS(latitude, longitude));
                            });
                }
            });
            tableBottomRight.add(hereButton).width(widgetUnitStep)
                            .height(widgetUnitStep).right();
            table.add(tableBottomRight)
                    .right()
                    .padBottom(borderPad)
                    .padRight(borderPad)
                    .row();
        }
    }

    public TableLocation getTableLocation() {
        tableLocation = new TableLocation();
        return tableLocation;
    }
}
