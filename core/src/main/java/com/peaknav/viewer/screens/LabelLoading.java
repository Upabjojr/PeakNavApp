package com.peaknav.viewer.screens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.peaknav.viewer.widgets.SlideShow;

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
                labelNoDataInThisArea.setText(downloadingOr(s("Loading")));
                tableCenterNoData.setVisible(true);
                getAppState().setLoadingMapData(true);
                break;
            case NO_DATA:
                labelNoDataInThisArea.setText(downloadingOr(s("No_downloaded_data_for_this_area")));
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
    }

    public LabelLoading(float height) {
        state = State.LOADING;

        tableCenterNoData = new Table();
        tableCenterNoData.setFillParent(true);
        tableCenterNoData.setVisible(true);
        tableCenterNoData.center();
        labelNoDataInThisArea = new Label(s("Loading"), getC().styleSingleton.getLabelStyle());
        // labelNoDataInThisArea.setFontScale(3f);
        // Centred line by line: the download's percentage goes on a line of its own below.
        labelNoDataInThisArea.setAlignment(com.badlogic.gdx.utils.Align.center);
        // A sentence, not a word: "No downloaded data for this area" - and the longer German
        // and French translations of it - ran off both edges of a phone, because a Label draws
        // one unbroken line unless told otherwise. Given a width, it breaks the message into as
        // many lines as it needs, centred one above the other.
        labelNoDataInThisArea.setWrap(true);
        tableCenterNoData.add(labelNoDataInThisArea).minHeight(height).width(messageWidth(height)).row();

        Label.LabelStyle captionStyle = new Label.LabelStyle();
        captionStyle.font = getC().styleSingleton.getBitmapFontSmallWhite();
        slideShow = new SlideShow(height, captionStyle);
        tableCenterNoData.add(slideShow.getTable()).row();
    }

    /**
     * How much width the message may use: nearly all of the screen, with a margin so the text
     * never touches the edges. Taken from the stage rather than fixed, so the same message is
     * one line on a tablet and three on a phone, and is re-read when the screen turns.
     */
    private static Value messageWidth(final float widgetUnitStep) {
        return new Value() {
            @Override
            public float get(Actor context) {
                float screenWidth = context != null && context.getStage() != null
                        ? context.getStage().getWidth() : 10f * widgetUnitStep;
                return 0.86f * screenWidth;
            }
        };
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
            labelNoDataInThisArea.setText(s("Loading"));
            tableCenterNoData.setVisible(true);
        } else {
            setState(state);
        }
    }
}
