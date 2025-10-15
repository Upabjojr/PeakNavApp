package com.peaknav.viewer.screens;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;

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
                labelNoDataInThisArea.setText(s("Loading"));
                tableCenterNoData.setVisible(true);
                getAppState().setLoadingMapData(true);
                break;
            case NO_DATA:
                labelNoDataInThisArea.setText(s("No_downloaded_data_for_this_area"));
                tableCenterNoData.setVisible(true);
                getAppState().setLoadingMapData(false);
                break;
        }
    }

    private State state;
    private final Table tableCenterNoData;
    private final Label labelNoDataInThisArea;

    public enum State {
        LOADING,
        LOADING_UPDATING,
        NO_DATA,
        LOADED;
    }

    public LabelLoading(float height) {
        state = State.LOADING;

        tableCenterNoData = new Table();
        tableCenterNoData.setFillParent(true);
        tableCenterNoData.setVisible(true);
        tableCenterNoData.center();
        labelNoDataInThisArea = new Label(s("Loading"), getC().styleSingleton.getLabelStyle());
        // labelNoDataInThisArea.setFontScale(3f);
        tableCenterNoData.add(labelNoDataInThisArea).height(height).row();

    }

    public Table getTableCenterNoData() {
        return tableCenterNoData;
    }
}
