package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.utils.Units.formatDistanceToUnitSystem;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
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
        ImageTextButtonOptionPane button = new ImageTextButtonOptionPane(text, style);
        // Consistent layout across every menu button: content hugs the left edge, the icon sits in a
        // fixed square cell (scaled to fit so non-square icons are not stretched), and the label is
        // left-aligned in the remaining width — so icons line up in one column and text in another,
        // regardless of icon shape or label length.
        button.left();
        button.getImage().setScaling(Scaling.fit);
        button.getImageCell().size(widgetUnitStep);
        button.getLabelCell().padLeft(0.3f * widgetUnitStep).expandX().left();
        button.getLabel().setAlignment(Align.left);
        return button;
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
                    float eleMeters = (float) mapApp.mapViewerScreen.getCameraElevationMeters();

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

            // Leftmost, away from the X that closes the picture: the two must not be neighbours.
            Button buttonMatchPhoto = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_match_photo.png", null);
            buttonMatchPhoto.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    com.peaknav.viewer.PhotoSkylineAligner.matchNow();
                }
            });
            tableCameraControl.add(buttonMatchPhoto).width(widgetUnitStep).height(widgetUnitStep)
                    .padLeft(borderPad).padBottom(borderPad);

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

            if (com.peaknav.utils.PeakNavUtils.getLoadFactory() != null
                    && com.peaknav.utils.PeakNavUtils.getLoadFactory().isDebugBuild()) {
                // Debug builds: save this photo with the camera's pose as a dataset sample.
                Button buttonSaveSample = getC().widgetTextures.getButtonWithIcon(
                        "icons/icon_save_sample.png", null);
                buttonSaveSample.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        com.peaknav.viewer.PhotoSkylineAligner.saveSample();
                    }
                });
                tableCameraControl.add(buttonSaveSample).width(widgetUnitStep).height(widgetUnitStep)
                        .padLeft(borderPad).padBottom(borderPad);
            }

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
            com.peaknav.viewer.PhotoSkylineAligner.clear();
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
        public final Button helpButton;
        private final Button hereButton;
        private final TextureRegionDrawable icon_here;
        private final TextureRegionDrawable icon_here_gps;
        public final Button buttonGoToDest;
        public final Button buttonOrbitDest;
        public final Button buttonOpenCoordinate;
        private final Button buttonCancelGoToDest;
        public final Table tableCancelGoToDest;
        public final Button buttonGpxFly; // cinematic tour of the loaded GPX; shown only when one is loaded
        public final Button buttonGpxClear; // discards the loaded GPX; shown alongside buttonGpxFly
        public final Label copyrightLabel;
        /** Scrub bar for the GPX tour: shown only while one is running, drag to jump along it. */
        public final Table gpxSeekTable;
        public final Slider gpxSeekSlider;
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

            // GPX scrub bar, along the bottom of the screen and clear of the side button columns.
            gpxSeekTable = new Table();
            gpxSeekTable.bottom();
            gpxSeekTable.setFillParent(true);
            gpxSeekTable.setVisible(false);

            Slider.SliderStyle gpxSeekStyle = new Slider.SliderStyle();
            gpxSeekStyle.knob = getC().widgetTextures.getTextureRegionDrawable(
                    "icons/icon_slider_alpha.png");
            gpxSeekStyle.knob.setMinHeight(widgetUnitStep);
            gpxSeekStyle.knob.setMinWidth(widgetUnitStep);
            gpxSeekStyle.background = getC().widgetTextures.getNinePatchDrawable(
                    "icons/slider_nine_patch.png");
            gpxSeekSlider = new Slider(0f, 1f, 0.002f, false, gpxSeekStyle);
            gpxSeekTable.add(gpxSeekSlider)
                    .width(Gdx.graphics.getWidth() - 6f * widgetUnitStep)
                    .height(widgetUnitStep)
                    .padBottom(2.2f * widgetUnitStep);

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
                    // Clearing the selection also ends an orbit around it.
                    mapApp.mapViewerScreen.stopOrbit();
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
                    // One combined move that flies to the destination and turns to look at it.
                    //  - Interpolation.linear: progress is raw time; each phase eases itself in and
                    //    out (see easeWindow), so both the flight and the turn accelerate from rest
                    //    and glide to a stop rather than starting or stopping abruptly.
                    //  - directionStartFraction 0.25: the turn begins a quarter of the way in and
                    //    runs to the very end, so it is spread over most of the move (gentle).
                    //  - positionEndFraction 0.6: the camera lands at 60% of the move, so the turn
                    //    keeps going for a while after arrival.
                    //  - 2s duration (vs the 1s default): stretches that turn out further, so it
                    //    rotates slowly rather than whipping around.
                    mapApp.mapViewerScreen.moveCameraAction.setCameraVectors(
                            impactLifted,
                            newDir,
                            mapApp.mapViewerScreen.cam.up,
                            false,
                            Interpolation.linear,
                            true,
                            0.25f,
                            0.6f,
                            2.0f
                    );

                    mapApp.mapViewerScreen.removeImpact();
                }
            });
            tableCancelGoToDest.add(buttonGoToDest).width(widgetUnitStep)
                    .height(widgetUnitStep);

            // The other thing to do with a clicked point: circle it instead of flying to it.
            // Same strip as "go to", so it appears with the pin and goes away with it.
            buttonOrbitDest = getC().widgetTextures.getButtonWithIcon("icons/icon_orbit.png");
            buttonOrbitDest.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    // The gyroscope would fight the orbit for the camera, as it does a flight.
                    getC().getMapViewerScreen().tableTool.buttonOrientation.setChecked(false);
                    // Start from where the camera already is: the current distance to the
                    // point becomes the radius, so the view does not jump before it turns.
                    mapApp.mapViewerScreen.startOrbit(mapApp.mapViewerScreen.impact);
                    // The pin has served its purpose, exactly as after "go to". The orbit
                    // holds its own copy of the centre, so clearing the pin does not stop it.
                    mapApp.mapViewerScreen.removeImpact();
                }
            });
            tableCancelGoToDest.add(buttonOrbitDest).width(widgetUnitStep)
                    .height(widgetUnitStep).padLeft(0.35f * widgetUnitStep);

            // A second row, set apart from the three above it: this one does not move the
            // camera at all, it hands the point to something else entirely, so it should not
            // sit among the buttons that fly and orbit.
            tableCancelGoToDest.row();
            buttonOpenCoordinate = getC().widgetTextures
                    .getButtonWithIcon("icons/icon_open_coordinate.png");
            buttonOpenCoordinate.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    Vector3 impact = mapApp.mapViewerScreen.impact;
                    if (impact == null || getNativeScreenCaller() == null) {
                        return;
                    }
                    // World x is a longitude scaled at the frame's reference latitude - the
                    // target's - so that is what converts it back.
                    double latitude = impact.y;
                    double longitude = com.peaknav.utils.Units.convertLatitsToLonits(
                            impact.x, (float) getC().L.getTargetLatitude());
                    getNativeScreenCaller().openCoordinate(latitude, longitude);
                }
            });
            tableCancelGoToDest.add(buttonOpenCoordinate).width(widgetUnitStep)
                    .height(widgetUnitStep).colspan(3).right()
                    .padTop(0.55f * widgetUnitStep);
            table.add(tableCancelGoToDest).right().expandY()
                    .padRight(borderPad)
                    .row();

            // Cinematic GPX tour. Hidden until a track is loaded (MapViewerScreen toggles it).
            // A play triangle, not the camera icon: with the camera it was indistinguishable from
            // the photo button right above it, so nobody read it as "play the loaded route".
            // The same button pauses the tour once it is running — its icon is swapped by
            // MapViewerScreen.updateGpxButtons, so play and pause never occupy two slots.
            buttonGpxFly = getC().widgetTextures.getButtonWithIcon("icons/icon_gpx_play.png", null);
            buttonGpxFly.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    mapApp.mapViewerScreen.toggleGpxFlythrough();
                }
            });
            buttonGpxFly.setVisible(false);
            table.add(buttonGpxFly).width(widgetUnitStep).height(widgetUnitStep).expandY()
                    .right()
                    .padRight(borderPad)
                    .row();

            // Clear the loaded track(s). Also hidden until there is something to clear.
            buttonGpxClear = getC().widgetTextures.getButtonWithIcon(
                    "icons/icon_gpx_clear.png", null);
            buttonGpxClear.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    mapApp.mapViewerScreen.stopGpxFlythrough();
                    getC().gpxManager.clear();
                }
            });
            buttonGpxClear.setVisible(false);
            table.add(buttonGpxClear).width(widgetUnitStep).height(widgetUnitStep).expandY()
                    .right()
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
            helpButton = getC().widgetTextures.getButtonWithIcon("icons/icon_help.png");
            helpButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    // The "?" button is the tutorial only. Keyboard controls are a
                    // separate overlay, shown when an unbound key is pressed.
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
