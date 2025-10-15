package com.peaknav.elevation;


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;

import com.peaknav.utils.PeakNavUtils;

public class ElevationTile {

    private final int latitude;
    private final int longitude;

    public ElevationTile(int latitude, int longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /*
    public FileHandle getElevationFileImgJpg() {
        return Gdx.files.external(getElevationFilepathImgJpg(latitude, longitude));
    }

    public FileHandle getElevationFileImgPng() {
        return Gdx.files.external(getElevationFilepathImgPng(latitude, longitude));
    }
     */

    /*
    public short getPixelElevationInMeters(int y, int x) {
        Pixmap pixmapJpg = PeakNavUtils.readImageCached(getElevationFileImgJpg());
        Pixmap pixmapPng = PeakNavUtils.readImageCached(getElevationFileImgPng());
        int index = x + y*pixmapPng.getWidth();
        byte byteJpg = pixmapJpg.getPixels().get(index);
        byte bytePng = pixmapPng.getPixels().get(index);
        return PeakNavUtils.convertImageBytesToElevationMeters(byteJpg, bytePng);
    }
     */

    private final int SIZE = 3600;

    /*
    public short getCoordElevationInMeters(double lat, double lon) {
        Pixmap pixmapJpg = PeakNavUtils.readImage(getElevationFileImgJpg());
        Pixmap pixmapPng = PeakNavUtils.readImage(getElevationFileImgPng());
        int x = (int) Math.round(SIZE*(lon - longitude));
        int y = (int) Math.round(SIZE * (1 - lat + latitude));
        int index = x + y*pixmapPng.getWidth();
        byte byteJpg = pixmapJpg.getPixels().get(index);
        byte bytePng = pixmapPng.getPixels().get(index);
        return PeakNavUtils.convertImageBytesToElevationMeters(byteJpg, bytePng);
    }
     */
}
