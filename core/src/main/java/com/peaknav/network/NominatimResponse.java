package com.peaknav.network;

import com.badlogic.gdx.utils.JsonValue;

import java.io.Serializable;

public class NominatimResponse implements Serializable {
    public float lat, lon, importance;
    public String displayName, osmType;

    public NominatimResponse(JsonValue jsonValue) {
        osmType = jsonValue.getString("osm_type", "");
        lon = jsonValue.getFloat("lon", 0f);
        lat = jsonValue.getFloat("lat", 0f);
        displayName = jsonValue.getString("display_name", "");
        importance = jsonValue.getFloat("importance", 0f);
    }

    public String toString() {
        return displayName;
    }

}
