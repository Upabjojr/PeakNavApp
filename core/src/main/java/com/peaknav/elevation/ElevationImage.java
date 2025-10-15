package com.peaknav.elevation;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.utils.PeakNavUtils;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Tile;

import java.nio.ByteBuffer;

public class ElevationImage extends ElevationImageAbstract {
    public static final float MISSING_VERTEX_VALUE = Float.NaN;;
    private final Pixmap eleJpg;
    private final Pixmap elePng;

    // TODO: this should be private... there should be a loader checking if the tile has already been loaded...
    public ElevationImage(Pixmap eleJpg, Pixmap elePng, Tile tile, BoundingBox boundingBox) {
        super(eleJpg.getWidth(), tile, boundingBox);
        assert eleJpg != null;
        assert elePng != null;
        this.eleJpg = eleJpg;
        this.elePng = elePng;

        // Only square images of identical sizes:
        assert eleJpg.getWidth() == eleJpg.getHeight();
        assert elePng.getWidth() == elePng.getHeight();
        assert eleJpg.getWidth() == elePng.getWidth();

    }

    @Override
    protected short[] computeElevationsFromImages() {
        short[] elevationsShort = new short[edgeLength*edgeLength];

        ByteBuffer bbJpg = eleJpg.getPixels();
        ByteBuffer bbPng = elePng.getPixels();

        for (int y = 0; y < edgeLength; y++) {
            for (int x = 0; x < edgeLength; x++) {
                int pos = x + y*edgeLength;
                short val = PeakNavUtils.convertImageBytesToElevationMeters(bbJpg.get(pos), bbPng.get(pos));
                elevationsShort[pos] = val;
            }
        }
        // TODO: dispose of eleJpg and elePng, as they are no longer needed from here on?
        return elevationsShort;
    }

    @Override
    public ElevationImage rescale(int newScale) {
        if ((edgeLength - 1) % newScale != 0)
            throw new RuntimeException("not valid");
        int newEdgeLength = (edgeLength-1)/newScale + 1;

        Pixmap rescaledJpg = new Pixmap(newEdgeLength, newEdgeLength, Pixmap.Format.Alpha);
        Pixmap rescaledPng = new Pixmap(newEdgeLength, newEdgeLength, Pixmap.Format.Alpha);

        rescaledJpg.drawPixmap(eleJpg, 0, 0, edgeLength, edgeLength, 0, 0, newEdgeLength, newEdgeLength);
        rescaledPng.drawPixmap(elePng, 0, 0, edgeLength, edgeLength, 0, 0, newEdgeLength, newEdgeLength);

        return new ElevationImage(rescaledJpg, rescaledPng, tile, boundingBox);
    }

    @Override
    public ElevationImage crop(Tile newTile) {
        BoundingBox newBoundingBox = newTile.getBoundingBox();

        assert boundingBox.intersects(newBoundingBox);

        assert newBoundingBox.maxLatitude <= boundingBox.maxLatitude;
        assert newBoundingBox.maxLongitude <= boundingBox.maxLongitude;
        assert newBoundingBox.minLatitude >= boundingBox.minLatitude;
        assert newBoundingBox.minLongitude >= boundingBox.minLongitude;

        int factor = (1 << (newTile.zoomLevel - tile.zoomLevel));

        int width = edgeLength / factor;
        int height = edgeLength / factor;

        int startLon = width*(newTile.tileX - factor * tile.tileX);
        int startLat = height*(newTile.tileY - factor * tile.tileY);

        // width++;
        // height++;

        assert width == height;

        Pixmap croppedJpg = new Pixmap(width, height, Pixmap.Format.Alpha);
        Pixmap croppedPng = new Pixmap(width, height, Pixmap.Format.Alpha);

        // Y starts from top in pixmaps, unlike latitude that increase towards top:
        // TODO: check if this is off by one pixel

        croppedJpg.drawPixmap(eleJpg, startLon, startLat, width, height, 0, 0, width, height);
        croppedPng.drawPixmap(elePng, startLon, startLat, width, height, 0, 0, width, height);

        return new ElevationImage(croppedJpg, croppedPng, tile, newBoundingBox);
    }

    public Pixmap getEleJpg() {
        return eleJpg;
    }

    public Pixmap getElePng() {
        return elePng;
    }

    public void saveToExternal(FileHandle fileImageJpg, FileHandle fileImagePng) {
        // TODO: move to ElevationImageStore?
        // TODO: read only once from PeakNavUtils ?
        PeakNavUtils.getLoadFactory().getUtilsOSDep().savePixmapAsJpg(fileImageJpg.file(), eleJpg);
        PeakNavUtils.getLoadFactory().getUtilsOSDep().savePixmapAsPng(fileImagePng.file(), elePng);
        // saveNormalsToExternal(fileImageNormals);
    }

    @Override
    public void dispose() {
        if (!eleJpg.isDisposed()) {
            eleJpg.dispose();
        }
        if (!elePng.isDisposed()) {
            elePng.dispose();
        }
    }
}
