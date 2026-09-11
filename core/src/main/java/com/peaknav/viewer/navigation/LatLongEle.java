package com.peaknav.viewer.navigation;

import com.peaknav.geo.LatLong;

import com.peaknav.utils.Units;

public class LatLongEle extends LatLong {
    private final double elevation;

    public LatLongEle(double latitude, double longitude, double elevation) throws IllegalArgumentException {
        super(latitude, longitude);
        this.elevation = elevation;
    }

    public double getElevationInLatits() {
        return Units.convertMetersToLatits(elevation);
    }

    public double getElevationInMeters() {
        return elevation;
    }

}
