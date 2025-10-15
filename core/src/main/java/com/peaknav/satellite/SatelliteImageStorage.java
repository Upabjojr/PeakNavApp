package com.peaknav.satellite;

import static com.peaknav.elevation.ElevationImageStorage.getTileString;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.peaknav.utils.TileAndZoomElevFactor;
import com.peaknav.utils.PathUtils;
import com.peaknav.utils.PeakNavUtils;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SatelliteImageStorage {

    private static Map<TileAndZoomElevFactor, Texture> textureMap = new ConcurrentHashMap<>();

    private SatelliteImageStorage() {

    }

    public static Texture getSatelliteTextureBlock(TileAndZoomElevFactor cib) {
        if (textureMap.containsKey(cib)) {
            return textureMap.get(cib);
        }
        String path = getSatelliteBlockFilePath(cib);
        FileHandle fileHandle = Gdx.files.external(path);
        if (!fileHandle.exists()) {
            return null;
        }
        Pixmap pixmap = PeakNavUtils.readImage(fileHandle);
        Texture texture = new Texture(pixmap);
        pixmap.dispose();
        textureMap.put(cib, texture);
        return texture;
    }

    public static String getSatelliteBlockFilePath(TileAndZoomElevFactor cib) {
        byte z = (byte) cib.zoomElevFactor;
        String tileString = getTileString(cib.tile);
        String name = String.format(Locale.ENGLISH, "sat.%s.%02d.jpg", tileString, z);
        return PathUtils.joinPaths(
                "satellite",
                name
        );
    }

    public static void dispose() {
        // TODO
    }
}