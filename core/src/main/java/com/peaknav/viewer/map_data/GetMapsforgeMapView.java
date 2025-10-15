package com.peaknav.viewer.map_data;

import com.badlogic.gdx.Gdx;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.model.Point;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.Layers;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.Model;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.view.MapView;

import java.io.File;

import com.peaknav.ui.ClickCallback;

public class GetMapsforgeMapView {

    private GraphicFactory graphicFactory;
    private MapView mapView;

    public GetMapsforgeMapView(GraphicFactory graphicFactory, MapView mapView) {
        this.graphicFactory = graphicFactory;
        this.mapView = mapView;
    }

    public void setPosition(double lat, double lon, byte zoom) {
        mapView.getModel()
                .mapViewPosition
                .setMapPosition(new MapPosition(new LatLong(lat, lon), zoom));
    };

    public MapView getMapView(ClickCallback clickCallback) {
        mapView.getMapScaleBar().setVisible(true);

        Layers layers = mapView.getLayerManager().getLayers();

        MultiMapDataStore mapDataStore = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);

        File mapFolder = Gdx.files.external("map_folder").file();
        File[] files = mapFolder.listFiles();
        for (File file : files) {
            if (!file.getName().endsWith(".map"))
                continue;
            mapDataStore.addMapDataStore(new MapFile(file), false, false);
        }

        TileCache tileCache = new InMemoryTileCache(100);
        final Model model = mapView.getModel();
        TileRendererLayer tileRendererLayer = new TileRendererLayer(
                tileCache, mapDataStore, model.mapViewPosition,
                false, true, false,
                graphicFactory,null) {
            @Override
            public boolean onTap(LatLong tapLatLong, Point layerXY, Point tapXY) {
                clickCallback.call(tapLatLong);
                return true;
            }
        };
        tileRendererLayer.setXmlRenderTheme(org.mapsforge.map.rendertheme.InternalRenderTheme.OSMARENDER);
        layers.add(tileRendererLayer);
        return mapView;
    }

}
