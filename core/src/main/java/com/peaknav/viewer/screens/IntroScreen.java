package com.peaknav.viewer.screens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.Constants.peakNavGrey;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.utils.Units.deg2rad;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.widgets.WidgetGetter;

public class IntroScreen implements Screen {
    SpriteBatch spriteBatch;
    Stage stage;
    private MapApp mapApp;
    private LabelLoading labelLoading;
    private InputMultiplexer multiplexer;
    private Table tableDownloadMap;
    private Texture ic_launcher_texture;
    private Table tableCentral;
    private Label labelDownloadState;
    private ShapeRenderer shapeRenderer;
    private float radius = 30f;
    private boolean downloadStarted = false;
    private float timeElapsed = 0.0f;
    private final Color colorOuterRot = new Color(1f, 0f, 0f, 0.4f);
    private final Color colorProgress = new Color(0f, 0f, 1.0f, 0.4f);
    private float widgetUnitStep;
    private float radius1, radius2;
    // private Timer.Task timer;
    private Drawable[] downloadButtonIcons;
    private Button buttonDM;

    public IntroScreen(MapApp mapApp) {

        this.mapApp = mapApp;
    }

    public void triggerMapDataDownloaded() {
        if (tableDownloadMap != null) {
            tableDownloadMap.setVisible(false);
        }
        mapApp.setScreen(mapApp.mapViewerScreen);
        if (labelDownloadState != null) {
            labelDownloadState.setText("Download complete!");
        }
    }

    public void triggerMapDataDownloadStarted() {
        if (labelDownloadState != null) {
            labelDownloadState.setText(s("Download_in_progress"));
        }
        if (tableDownloadMap != null) {
            // The terms and the button have done their work; the screen is the slideshow's now.
            tableDownloadMap.setVisible(false);
        }
        downloadStarted = true;
    }

    /** Pictures of the app while the first download runs; see SlideShow. */
    private com.peaknav.viewer.widgets.SlideShow slideShow;

    /**
     * True when this screen was opened to be looked at rather than waited on: the debug build's
     * button (see WidgetGetter). The screen normally hands over to the map as soon as the data
     * is loaded, which with data already downloaded means at once; in preview it stays until it
     * is tapped. What it shows is a first run's own screen - the terms, the button and the
     * pointers at it; the slideshow belongs to a download actually running.
     */
    private boolean preview = false;

    /** Opens the welcome screen as it looks on a first run, to be closed by a tap. Debug builds. */
    public void showAsPreview() {
        preview = true;
        mapApp.setScreen(this);
    }

    @Override
    public void show() {
        int minSize = Math.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        widgetUnitStep = Units.getWidgetUnitStep();
        stage = new Stage(new ExtendViewport(minSize, minSize));
        spriteBatch = new SpriteBatch();

        labelLoading = mapApp.mapViewerScreen.labelLoading;

        Label.LabelStyle labelStyleSmall = new Label.LabelStyle();
        labelStyleSmall.font = getC().styleSingleton.getBitmapFontSmallWhite();

        tableCentral = new Table();
        tableCentral.setFillParent(true);
        tableCentral.center().top();
        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = getC().styleSingleton.getBitmapFont();
        Label label = new Label(s("Welcome"), labelStyle);
        tableCentral.add(label).padTop(2*widgetUnitStep).height(0.5f*widgetUnitStep).row();
        if (ic_launcher_texture == null) {
            ic_launcher_texture = new Texture(Gdx.files.internal("icons/ic_launcher.png"));
            // Drawn into a fixed cell well below the texture's own size, so without filtering
            // the medallion's ring and tick marks alias into a jagged mess.
            ic_launcher_texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        Image image = new Image(ic_launcher_texture);
        tableCentral.add(image).width(3*widgetUnitStep).height(3*widgetUnitStep).padTop(0.5f*widgetUnitStep).row();

        computeRadii();

        labelDownloadState = new Label("", labelStyleSmall);
        labelDownloadState.setAlignment(com.badlogic.gdx.utils.Align.center);
        tableCentral.add(labelDownloadState).row();

        // The slideshow, under the download's state. Running from the moment the screen opens,
        // not only once a download does: the pictures are what says what the app is for, to
        // someone deciding whether to press the button.
        slideShow = new com.peaknav.viewer.widgets.SlideShow(widgetUnitStep, labelStyleSmall);
        tableCentral.add(slideShow.getTable()).row();

        stage.addActor(tableCentral);

        tableDownloadMap = new Table();
        buttonDM = getC().widgetTextures.getButtonWithIcon("icons/icon_checkbox_download_data.png");
        tableDownloadMap.setFillParent(true);
        tableDownloadMap.center().bottom();
        buttonDM.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                getC().submitExecutorGeneric(
                        () -> {
                            P.setCollectDownloadInfo(true);
                            mapApp.nativeScreenCaller.openMapDataDownloadChooserWizard();
                        });
            }
        });

        downloadButtonIcons = new Drawable[2];
        downloadButtonIcons[0] = buttonDM.getStyle().up;
        downloadButtonIcons[1] = getC().widgetTextures.getTextureRegionDrawable("icons/icon_checkbox_download_data2.png");

        tableDownloadMap
                .add(getLicensePrivacy(labelStyleSmall))
                .width(licenseTextWidth()).row();
        tableDownloadMap.add(getLicensePrivacyLinks()).row();

        // tableDownloadMap.add(labelDM).row();
        tableDownloadMap.add(buttonDM).width(2*widgetUnitStep).height(2*widgetUnitStep).padBottom(0.5f*widgetUnitStep).row();

        boolean firstTimeAppRun = P.isFirstTimeAppRun();
        tableDownloadMap.setVisible(firstTimeAppRun);

        stage.addActor(tableDownloadMap);

        shapeRenderer = new ShapeRenderer();
        shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());

        if (preview) {
            // Nothing here is worth a button of its own in a build nobody ships: a tap anywhere
            // outside the download button gives the map back.
            stage.addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
                @Override
                public boolean touchDown(com.badlogic.gdx.scenes.scene2d.InputEvent event,
                                         float x, float y, int pointer, int button) {
                    if (event.getTarget() == stage.getRoot() || event.getTarget() == tableCentral) {
                        preview = false;
                        mapApp.setScreen(mapApp.mapViewerScreen);
                        return true;
                    }
                    return false;
                }
            });
        }

        multiplexer = new InputMultiplexer();
        multiplexer.addProcessor(stage);

        Gdx.input.setInputProcessor(multiplexer);
    }

    private Table getLicensePrivacyLinks() {
        Table links = new Table();
        WidgetGetter.HyperlinkLabel termsLicense = new WidgetGetter.HyperlinkLabel(
                s("License_link"), null, "https://peaknav.com/license_agreement.html");
        WidgetGetter.HyperlinkLabel termsPrivacy = new WidgetGetter.HyperlinkLabel(
                s("Privacy_link"), null, "https://peaknav.com/privacy_statement.html");
        links.add(termsLicense).left().padRight(widgetUnitStep);
        links.add(termsPrivacy).right();
        return links;
    }

    private Label getLicensePrivacy(Label.LabelStyle labelStyleSmall) {
        Label licensePrivacy = new Label(s("Accept_license_and_privacy"), labelStyleSmall);
        licensePrivacy.setWrap(true);
        // Centred like everything else on this screen: wrapped text is left-aligned by default,
        // which put the sentence off to one side of the links and the button below it.
        licensePrivacy.setAlignment(com.badlogic.gdx.utils.Align.center);
        licensePrivacy.setWidth(licenseTextWidth());
        return licensePrivacy;
    }

    /** Most of the screen's width, but not a line across a whole tablet: ten button widths at most. */
    private float licenseTextWidth() {
        return Math.min(Gdx.graphics.getWidth() * 0.8f, 10 * widgetUnitStep);
    }

    private void computeRadii() {
        radius = 1.5f*widgetUnitStep;
        radius1 = radius - 0.2f*widgetUnitStep;
        radius2 = radius + 0.3f*widgetUnitStep;
    }

    public void drawCompletionArc(float centerX, float centerY, float radius, float radius2, float degrees) {
        int numSegments = Math.round(degrees/6+1);

        float stepDegrees = degrees / numSegments;
        float angleIter = 90f;

        ImmediateModeRenderer renderer = shapeRenderer.getRenderer();

        float prevX = centerX;
        float prevX2 = centerX;
        float prevY = centerY + radius;
        float prevY2 = centerY + radius2;

        for (int i = 0; i <= numSegments; i++) {
            float cos = (float) Math.cos(angleIter*deg2rad);
            float sin = (float) Math.sin(angleIter*deg2rad);
            float x = centerX + radius * cos;
            float y = centerY + radius * sin;
            float x2 = centerX + radius2 * cos;
            float y2 = centerY + radius2 * sin;

            renderer.color(colorProgress);
            renderer.vertex(prevX, prevY, 0);
            renderer.color(colorProgress);
            renderer.vertex(x, y, 0);
            renderer.color(colorProgress);
            renderer.vertex(x2, y2, 0);

            renderer.color(colorProgress);
            renderer.vertex(prevX, prevY, 0);
            renderer.color(colorProgress);
            renderer.vertex(x2, y2, 0);
            renderer.color(colorProgress);
            renderer.vertex(prevX2, prevY2, 0);

            angleIter -= stepDegrees;
            prevX = x;
            prevY = y;
            prevX2 = x2;
            prevY2 = y2;
        }

    }

    private void drawCompletionDisk(float delta) {
        float width = Gdx.graphics.getWidth();
        float height = Gdx.graphics.getHeight();

        float x = width/2;
        float y = height - 4.5f*widgetUnitStep;

        float smallRadius = 0.15f*radius;
        float bigRadius = radius;

        // int segments = 50; // Adjust the number of segments for smoother rotation
        float completionAngle = 360f*getAppState().getMapDataDownloadProgressRatio();
        timeElapsed += delta;
        timeElapsed %= 2.f;
        float outerRotAngle = -90f* Interpolation.circle.apply(
                (timeElapsed > 1.f)? (2.f - timeElapsed) : timeElapsed);
        outerRotAngle %= 360f;

        shapeRenderer.setColor(colorOuterRot);
        final int N = 8;
        for (int i = 0; i < N; i++) {
            float angle = deg2rad * (outerRotAngle + 360f*i/N);
            shapeRenderer.circle(
                    x+(smallRadius + bigRadius) * (float) Math.cos(angle),
                    y+(smallRadius + bigRadius) * (float) Math.sin(angle),
                    smallRadius);
        }
        shapeRenderer.setColor(colorProgress);
        drawCompletionArc(x, y, radius1, radius2, completionAngle);
    }

    @Override
    public void render(float delta) {

        setDownloadButtonIcon(delta);
        if (slideShow != null) {
            slideShow.update(delta, true);
        }
        updateDownloadPercent();

        Gdx.gl.glClearColor(peakNavGrey, peakNavGrey, peakNavGrey, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        if (!preview && labelLoading.getState() == LabelLoading.State.LOADED) {
            // TODO: labelLoading.getState() may never be LOADED if no location permission was granted to the app
            mapApp.setScreen(mapApp.mapViewerScreen);
        }

        if (downloadStarted) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            drawCompletionDisk(delta);
            shapeRenderer.end();
        }

        spriteBatch.begin();
        spriteBatch.end();

        stage.act(delta);
        boolean pointAtButton = !downloadStarted && tableDownloadMap.isVisible();
        pointerTime += delta;
        if (pointAtButton) {
            drawDownloadButtonPointers(false);
        }
        stage.draw();
        if (pointAtButton) {
            drawDownloadButtonPointers(true);
        }
    }

    /** Seconds the pointers have been animating; only the fraction of each period matters. */
    private float pointerTime = 0f;
    private static final float POINTER_PERIOD = 1.2f;
    private static final float HALO_PERIOD = 1.6f;
    private static final int POINTER_CHEVRONS = 3;
    private final Color colorPointer = new Color(1f, 0.70f, 0.05f, 1f);
    private final Color colorHalo = new Color(1f, 0.85f, 0.30f, 1f);
    private final com.badlogic.gdx.math.Vector2 buttonCentre = new com.badlogic.gdx.math.Vector2();

    /**
     * Makes the download button hard to miss: many people did not see that the welcome screen
     * waits for it. Behind the button ({@code front} false) a halo swells and fades; beside it
     * ({@code front} true) chevrons slide in from both sides towards it, fading in and out as
     * they go. Drawn in the stage's own coordinates, so they follow the button wherever the
     * layout puts it.
     */
    private void drawDownloadButtonPointers(boolean front) {
        float w = buttonDM.getWidth();
        float h = buttonDM.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        buttonDM.localToStageCoordinates(buttonCentre.set(w / 2f, h / 2f));
        float cx = buttonCentre.x, cy = buttonCentre.y, r = Math.min(w, h) / 2f;
        float u = widgetUnitStep;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        shapeRenderer.setProjectionMatrix(stage.getCamera().combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        if (!front) {
            float t = (pointerTime % HALO_PERIOD) / HALO_PERIOD;
            float haloRadius = r * (1.05f + 0.3f * Interpolation.pow2Out.apply(t));
            colorHalo.a = 0.55f * (1f - t);
            shapeRenderer.setColor(colorHalo);
            shapeRenderer.circle(cx, cy, haloRadius, 48);
        } else {
            float size = 0.32f * u;          // half the chevron's height
            float stroke = 0.13f * u;
            for (int i = 0; i < POINTER_CHEVRONS; i++) {
                float t = ((pointerTime / POINTER_PERIOD) + (float) i / POINTER_CHEVRONS) % 1f;
                // From 3.4 units out to just beside the button, fading in and back out.
                float distance = r + (2.4f - 1.9f * t) * u;
                colorPointer.a = (float) Math.sin(Math.PI * t);
                shapeRenderer.setColor(colorPointer);
                drawChevron(cx - distance, cy, size, stroke, 1f);    // left, pointing right
                drawChevron(cx + distance, cy, size, stroke, -1f);   // right, pointing left
            }
        }
        shapeRenderer.end();
        shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** A ">" with its tip at (tipX, y), or a "<" when {@code direction} is -1. */
    private void drawChevron(float tipX, float y, float size, float stroke, float direction) {
        float backX = tipX - direction * size;
        shapeRenderer.rectLine(backX, y + size, tipX, y, stroke);
        shapeRenderer.rectLine(backX, y - size, tipX, y, stroke);
        shapeRenderer.circle(tipX, y, stroke / 2f, 12);
    }

    /** The percentage last written into the state label, so its text is only rebuilt on a change. */
    private int downloadPercentShown = -1;

    /** "Download in progress..." with how far it has got underneath, as the map screen shows it. */
    private void updateDownloadPercent() {
        if (!downloadStarted || labelDownloadState == null) {
            return;
        }
        int percent = Math.max(0, Math.min(100,
                (int) Math.floor(getAppState().getMapDataDownloadProgressRatio() * 100f)));
        if (percent != downloadPercentShown) {
            downloadPercentShown = percent;
            labelDownloadState.setText(s("Download_in_progress") + "\n" + percent + "%");
        }
    }

    private float cumDelta = 0f;
    private int downloadButtonIconIndex = 0;
    private void setDownloadButtonIcon(float delta) {
        cumDelta += delta;
        int index = ((int) (cumDelta / 0.750f)) % downloadButtonIcons.length;
        if (downloadButtonIconIndex != index) {
            downloadButtonIconIndex = index;
            buttonDM.getStyle().up = downloadButtonIcons[downloadButtonIconIndex];
        }

    }

    @Override
    public void resize(int width, int height) {
        if (!Units.isProportionalInterface() && stage.getViewport() instanceof ExtendViewport) {
            // One stage unit per pixel, as on the map screen: no growing with the window.
            ((ExtendViewport) stage.getViewport()).setMinWorldWidth(width);
            ((ExtendViewport) stage.getViewport()).setMinWorldHeight(height);
        }
        stage.getViewport().update(width, height, true);
        spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());

        widgetUnitStep = Units.getWidgetUnitStep();
        computeRadii();

        Gdx.gl.glViewport(0, 0, width, height);
    }

    @Override
    public void pause() {

    }

    @Override
    public void resume() {

    }

    @Override
    public void hide() {
    }

    /** See {@link MapViewerScreen#recoverFromRenderError()} — same blank-screen safeguard. */
    public void recoverFromRenderError() {
        MapViewerScreen.endBatchQuietly(spriteBatch);
        try {
            if (shapeRenderer != null && shapeRenderer.isDrawing()) {
                shapeRenderer.end();
            }
        } catch (Throwable ignored) {
        }
        if (stage != null) {
            MapViewerScreen.endBatchQuietly(stage.getBatch());
        }
    }

    @Override
    public void dispose() {
        // Null guards: these are created in show(), which may never have run.
        if (ic_launcher_texture != null) {
            ic_launcher_texture.dispose();
            ic_launcher_texture = null;
        }
        if (slideShow != null) {
            slideShow.dispose();
        }
        if (shapeRenderer != null)
            shapeRenderer.dispose();
        if (spriteBatch != null)
            spriteBatch.dispose();
        if (stage != null)
            stage.dispose();
    }

}
