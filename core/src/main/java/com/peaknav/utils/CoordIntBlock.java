package com.peaknav.utils;

public class CoordIntBlock {
    public final int latitude;
    public final int longitude;

    public CoordIntBlock(int latitude, int longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof CoordIntBlock) {
            CoordIntBlock ob = (CoordIntBlock) other;
            return latitude == ob.latitude && longitude == ob.longitude;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return 1000*latitude + longitude;
    }
}
