package com.peaknav.ui;

import com.peaknav.geo.LatLong;

import java.io.Serializable;

public interface ClickCallback extends Serializable {
    void call(LatLong latLong);
}
