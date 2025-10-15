package com.peaknav.elevation;

import static com.peaknav.elevation.blocks.CheckElevExistBlock.checkElevationExistence;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock.checkS3ElevationExistence;
import static com.peaknav.utils.PeakNavUtils.readImage;
import static com.peaknav.utils.PeakNavUtils.readImageToGreyscale;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.peaknav.utils.PathUtils;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Tile;

import java.util.Locale;

public class ElevationImageStorage {

    private final FileHandle imageJpg;
    private final FileHandle imagePng;
    private final Tile tile;
    private final int zoomElevLevel;
    private final boolean flagElevFound;

    public ElevationImageStorage(
            Tile tile,
            int zoomElevLevel) {
        this.tile = tile;
        this.zoomElevLevel = zoomElevLevel;

        this.imageJpg = getCroppedElevationImagePathJpg();
        this.imagePng = getCroppedElevationImagePathPng();

        /*
        BoundingBox bb = tile.getBoundingBox();
        int minLat = (int)bb.minLatitude;
        int minLon = (int)bb.minLongitude;
        int maxLat = (int)bb.maxLatitude;
        int maxLon = (int)bb.maxLongitude;
        boolean flagElevFound = false;
        for (int lat = minLat; lat <= maxLat; lat++) {
            for (int lon = minLon; lon <= maxLon; lon++) {
                if (checkElevationExistence(lat, lon)) {
                    flagElevFound = true;
                    break;
                }
            }
        }
         */
        Tile tile8 = tile;
        while (tile8.zoomLevel > 8) {
            tile8 = tile8.getParent();
        }
        boolean flagElevFound = checkS3ElevationExistence(tile8.tileX, tile8.tileY);
        this.flagElevFound = flagElevFound;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ElevationImageStorage) {
            ElevationImageStorage oe = (ElevationImageStorage) other;
            return tile.equals(oe.tile) && zoomElevLevel == oe.zoomElevLevel;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return tile.hashCode() + 84328*zoomElevLevel;
    }

    public BoundingBox getCroppedBoundingBox() {
        return tile.getBoundingBox();
    }

    private String getCroppedBaseImagePath(String extension) {
        return getCroppedPath(tile, zoomElevLevel, extension);
    }

    public static String getTileString(Tile tile) {
        return String.format(
                Locale.ENGLISH,
                "z%02d.x%05d.y%05d",
                tile.zoomLevel, tile.tileX, tile.tileY
        );
    }

    public static String getElevTileTarGzPath(Tile tile) {
        String subfolderX = String.format(Locale.ENGLISH,
                "x_%05d", tile.tileX);
        String fileNameY = String.format(Locale.ENGLISH,
                "y_%05d.tar.gz", tile.tileY);
        return PathUtils.joinPaths(
                "elev_tiles",
                "zoom_06",
                subfolderX,
                fileNameY);
    }

    public static String getCroppedPath(Tile tile, int zoomElevLevel, String extension) {
        assert zoomElevLevel >= 2;
        String cropStr = String.format(
                Locale.ENGLISH, "elev.%s.f%03d.%s",
                getTileString(tile), zoomElevLevel, extension);
        String subfolderX = String.format(Locale.ENGLISH,
                "x_%05d", tile.tileX);
        String subfolderY = String.format(Locale.ENGLISH,
                "y_%05d", tile.tileY);
        return PathUtils.joinPaths(
                "elev_tiles",
                "zoom_08",
                subfolderX,
                subfolderY,
                cropStr);
    }

    public static String getElevationCropsPathJpg(Tile tile, int zoomElevLevel) {
        return getCroppedPath(tile, zoomElevLevel, "jpg");
    }

    public static String getElevationCropsPathPng(Tile tile, int zoomElevLevel) {
        return getCroppedPath(tile, zoomElevLevel, "png");
    }

    private FileHandle getCroppedElevationImagePathJpg() {
        return Gdx.files.external(getCroppedBaseImagePath("jpg"));
    }

    private FileHandle getCroppedElevationImagePathPng() {
        return Gdx.files.external(getCroppedBaseImagePath("png"));
    }

    public boolean checkImageExistence() {
        return imageJpg.exists() && imagePng.exists();
    }

    public Tile getTile() {
        return tile;
    }

    public ElevationImageAbstract getElevationImage() {

        if (!flagElevFound) {
            return new ElevationImageFlat(getTile(), getCroppedBoundingBox());
        }

        FileHandle eleJpg = getCroppedElevationImagePathJpg();
        FileHandle elePng = getCroppedElevationImagePathPng();
        if (!eleJpg.exists() || !elePng.exists()) {
            // TODO: report missing (latitude, longitude);
            return null;
        }
        try {
            return new ElevationImage(
                    readImageToGreyscale(eleJpg),
                    readImage(elePng),
                    getTile(),
                    getCroppedBoundingBox()
            );
        } catch (GdxRuntimeException gdxRuntimeException) {
            gdxRuntimeException.printStackTrace();
            // TODO: report missing (latitude, longitude);
        }
        return null;
    }
}
