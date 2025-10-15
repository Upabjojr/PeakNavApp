package com.peaknav.viewer.map_data;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.GraphicFactory;

public interface MapsforgeConnector {
    Bitmap getBitmap();
    GraphicFactory getGraphicFactory();
}
