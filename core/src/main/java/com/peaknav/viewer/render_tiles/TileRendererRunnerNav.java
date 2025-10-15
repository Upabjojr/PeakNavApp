package com.peaknav.viewer.render_tiles;

import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.Color;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.model.DisplayModel;

import java.util.List;

public class TileRendererRunnerNav extends TileRendererRunnerMapsforge {
    public TileRendererRunnerNav(TileRenderer tileRenderer, TileRenderer.RenderThemes renderThemes, MapTile mapTile, PixmapLayerName layer) {
        super(tileRenderer, renderThemes, mapTile, layer);
    }


    Point getTopLeftPoint(BoundingBox boundingBox, byte zoomLevel, int tileSize) {
        long mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize);
        return new Point(MercatorProjection.longitudeToPixelX(boundingBox.minLongitude, mapSize), MercatorProjection.latitudeToPixelY(boundingBox.maxLatitude, mapSize));
    }

    public TileBitmap renderPolyline(Tile tile) {
        BoundingBox boundingBox = tile.getBoundingBox();
        Paint paint = this.graphicFactory.createPaint();
        paint.setColor(Color.BLUE);
        paint.setStrokeWidth(10.0F*getInverseScaleFactor(tile.zoomLevel));
        paint.setStyle(Style.STROKE);
        Polyline polyline = new Polyline(paint, this.graphicFactory);
        polyline.addPoint(boundingBox.getCenterPoint());
        polyline.addPoint(new LatLong(boundingBox.maxLatitude, boundingBox.maxLongitude));
        polyline.addPoint(new LatLong(boundingBox.minLatitude, boundingBox.maxLongitude));
        polyline.addPoint(new LatLong(boundingBox.minLatitude, boundingBox.minLongitude));
        Point topLeftPoint = this.getTopLeftPoint(boundingBox, tile.zoomLevel, tile.tileSize);
        Canvas canvas = this.graphicFactory.createCanvas();
        TileBitmap bitmap = this.graphicFactory.createTileBitmap(tile.tileSize, true);
        canvas.setBitmap(bitmap);
        polyline.setDisplayModel(tileRenderer.displayModel);
        polyline.draw(boundingBox, tile.zoomLevel, canvas, topLeftPoint);
        return bitmap;
    }

}
