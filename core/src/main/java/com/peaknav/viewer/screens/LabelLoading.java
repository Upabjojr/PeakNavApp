package com.peaknav.viewer.screens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.peaknav.viewer.widgets.SlideShow;
import com.peaknav.viewer.widgets.TextLines;

public class LabelLoading {

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
        switch (state) {
            case LOADED:
                tableCenterNoData.setVisible(false);
                getAppState().setLoadingMapData(false);
                break;
            case LOADING_UPDATING:
            case LOADING:
                setMessage(downloadingOr(s("Loading")));
                tableCenterNoData.setVisible(true);
                getAppState().setLoadingMapData(true);
                break;
            case NO_DATA:
                setMessage(downloadingOr(s("No_downloaded_data_for_this_area")));
                tableCenterNoData.setVisible(true);
                getAppState().setLoadingMapData(false);
                break;
        }
    }

    private State state;
    /** How far a running map data download has got, 0-100, or -1 when none is running. */
    private int downloadPercent = -1;
    private final Table tableCenterNoData;
    private final Label labelNoDataInThisArea;

    public enum State {
        LOADING,
        LOADING_UPDATING,
        NO_DATA,
        LOADED;
    }

    /** Pictures of the app, shown under the message while a first download runs. */
    private final SlideShow slideShow;

    /**
     * Advances the pictures and shows them while a download is running with nothing on the map
     * yet - the same wait the welcome screen fills, met again by anyone who starts a download
     * for an area before any of its data has arrived. Render thread, every frame.
     */
    public void update(float delta) {
        renderThread = Thread.currentThread();
        boolean show = downloadPercent >= 0 && state != State.LOADED;
        if (show && !slideShowRunning) {
            slideShow.restart();   // each wait gets its own run of pictures
        }
        slideShowRunning = show;
        slideShow.update(delta, show);
    }

    /** Whether the pictures were showing on the last frame; see {@link #update}. */
    private boolean slideShowRunning = false;

    public void dispose() {
        slideShow.dispose();
    }

    /** The screen has turned: the picture's size comes from the stage, so it is laid out again. */
    public void resize() {
        slideShow.invalidate();
        // A turn from landscape to portrait takes away most of the width the lines were broken for.
        setMessage(message);
    }

    /** The message as written, before it was broken into lines; see {@link #setMessage}. */
    private String message = "";

    /** The most lines the message is broken into before its font is made smaller. */
    private static final int MAX_MESSAGE_LINES = 3;

    /** How much of the screen's width a line of the message may take. */
    private static final float MESSAGE_WIDTH_FRACTION = 0.9f;

    /**
     * Shows a message in the centre of the screen, in as many lines as it needs to fit across it -
     * up to three, and only then smaller. The large font is 8% of the screen's short side high,
     * and "No downloaded data for this area" is wider than a phone held upright in every one of
     * the app's languages - the German, "Keine heruntergeladenen Daten für diesen Bereich", nearly
     * twice as wide: on one line it ran off both edges. See {@link TextLines}.
     */
    private void setMessage(String text) {
        message = text;
        // Measured on the render thread only. The state is also set from the tile threads, and
        // measuring there reads the font's scale, which a label laying itself out on the render
        // thread changes and puts back - the lines would now and then be broken for the wrong size.
        if (Thread.currentThread() == renderThread) {
            fitMessage();
        } else {
            Gdx.app.postRunnable(this::fitMessage);
        }
    }

    private void fitMessage() {
        Stage stage = tableCenterNoData.getStage();
        float screenWidth = stage != null ? stage.getWidth() : Gdx.graphics.getWidth();
        TextLines.fit(labelNoDataInThisArea, message, MESSAGE_WIDTH_FRACTION * screenWidth, MAX_MESSAGE_LINES);
    }

    /** The thread that draws, noted by the constructor and every frame's {@link #update}. */
    private volatile Thread renderThread;

    public LabelLoading(float height) {
        state = State.LOADING;
        renderThread = Thread.currentThread();   // built by MapViewerScreen, on the render thread

        tableCenterNoData = new Table();
        tableCenterNoData.setFillParent(true);
        tableCenterNoData.setVisible(true);
        tableCenterNoData.center();
        labelNoDataInThisArea = new Label(s("Loading"), getC().styleSingleton.getLabelStyle());
        // labelNoDataInThisArea.setFontScale(3f);
        // Centred line by line: the download's percentage goes on a line of its own below.
        labelNoDataInThisArea.setAlignment(com.badlogic.gdx.utils.Align.center);
        setMessage(s("Loading"));
        tableCenterNoData.add(labelNoDataInThisArea).minHeight(height).row();

        Label.LabelStyle captionStyle = new Label.LabelStyle();
        captionStyle.font = getC().styleSingleton.getBitmapFontSmallWhite();
        slideShow = new SlideShow(height, captionStyle);
        tableCenterNoData.add(slideShow.getTable()).row();
    }

    /**
     * While map data downloads, the centre of the screen says so and how far it has got - "Download
     * in progress..." with "42%" on the line below - in place of "Loading..." or "No data for this area", which is what the
     * download is about to change. -1 when the download has finished: the state's own text again.
     * Render thread only.
     */
    public void setDownloadPercent(int percent) {
        if (percent == downloadPercent) {
            return;
        }
        downloadPercent = percent;
        if (state != State.LOADED) {
            setState(state);
        }
    }

    private String downloadingOr(String text) {
        return downloadPercent < 0 ? text : s("Download_in_progress") + "\n" + downloadPercent + "%";
    }

    public Table getTableCenterNoData() {
        return tableCenterNoData;
    }

    /**
     * The same "Loading..." screen while a picked photo is decoded; off, the map-data
     * state shows again as it stands.
     */
    public void setPhotoLoading(boolean loading) {
        if (loading) {
            setMessage(s("Loading"));
            tableCenterNoData.setVisible(true);
        } else {
            setState(state);
        }
    }
}
