package com.peaknav.viewer.render_tiles;

import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.queue.Job;
import org.mapsforge.map.model.common.Observer;

import java.util.Set;

public class DummyTileCache implements TileCache {
    @Override
    public boolean containsKey(Job key) {
        return false;
    }

    @Override
    public void destroy() {

    }

    @Override
    public TileBitmap get(Job key) {
        return null;
    }

    @Override
    public int getCapacity() {
        return 0;
    }

    @Override
    public int getCapacityFirstLevel() {
        return 0;
    }

    @Override
    public TileBitmap getImmediately(Job key) {
        return null;
    }

    @Override
    public void purge() {

    }

    @Override
    public void put(Job key, TileBitmap bitmap) {

    }

    @Override
    public void setWorkingSet(Set<Job> workingSet) {

    }

    @Override
    public void addObserver(Observer observer) {

    }

    @Override
    public void removeObserver(Observer observer) {

    }
}
