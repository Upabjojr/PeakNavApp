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
        downloadStarted = true;
    }

    @Override
    public void show() {
        int minSize = Integer.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
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
        }
        Image image = new Image(ic_launcher_texture);
        tableCentral.add(image).width(3*widgetUnitStep).height(3*widgetUnitStep).padTop(0.5f*widgetUnitStep).row();

        computeRadii();

        labelDownloadState = new Label("", labelStyleSmall);
        tableCentral.add(labelDownloadState).row();
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
                .width(Gdx.graphics.getWidth()*0.8f).row();
        tableDownloadMap.add(getLicensePrivacyLinks()).row();

        // tableDownloadMap.add(labelDM).row();
        tableDownloadMap.add(buttonDM).width(2*widgetUnitStep).height(2*widgetUnitStep).padBottom(0.5f*widgetUnitStep).row();

        boolean firstTimeAppRun = P.isFirstTimeAppRun();
        tableDownloadMap.setVisible(firstTimeAppRun);

        stage.addActor(tableDownloadMap);

        shapeRenderer = new ShapeRenderer();
        shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());

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
        licensePrivacy.setWidth(Gdx.graphics.getWidth()*0.8f);
        return licensePrivacy;
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

        Gdx.gl.glClearColor(peakNavGrey, peakNavGrey, peakNavGrey, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        if (labelLoading.getState() == LabelLoading.State.LOADED) {
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
        stage.draw();
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

    @Override
    public void dispose() {
        ic_launcher_texture.dispose();
        ic_launcher_texture = null;
        shapeRenderer.dispose();
        stage.dispose();
    }

}
