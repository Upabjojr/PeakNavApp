package com.peaknav.ui;

import org.mapsforge.core.model.LatLong;

import java.io.Serializable;

public interface ClickCallback extends Serializable {
    void call(LatLong latLong);
}
