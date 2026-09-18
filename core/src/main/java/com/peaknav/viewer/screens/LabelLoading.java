package com.peaknav.viewer.screens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
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
        slideShow.update(delta, downloadPercent >= 0 && state != State.LOADED);
    }

    public void dispose() {
        slideShow.dispose();
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
            labelNoDataInThisArea.setText(s("Loading"));
            tableCenterNoData.setVisible(true);
        } else {
            setState(state);
        }
    }
}
