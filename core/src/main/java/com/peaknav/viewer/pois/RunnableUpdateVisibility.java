package com.peaknav.viewer.pois;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.viewer.labels.PoiObject.getComparatorPeaks;
import static com.peaknav.viewer.labels.PoiObject.getComparatorPois;

import com.badlogic.gdx.math.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.peaknav.utils.StoppableRunnable;
import com.peaknav.viewer.DataRetrieveThreadManager;
import com.peaknav.viewer.labels.DrawLabel;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.labels.PoiObject;
import com.peaknav.viewer.screens.MapViewerScreen;

public class RunnableUpdateVisibility extends StoppableRunnable {

    private static final String TAG = "RunnableUpdateVisibility";
    private static long lastUpdate = 0L;
    private final MapController C;
    private final Set<DataRetrieveThreadManager.MapDataUpdateRequest> updateRequests;


    private final static List<Polygon> tempPolygonsFront = new ArrayList<>(2048);
    private final static List<Polygon> tempPolygonsBack = new ArrayList<>(2048);
    private final static List<PoiObject> newDisplayablePois = new ArrayList<>(2048);

    private final static List<PoiObject> newVisiblePeaks = new ArrayList<>(8*2048);
    private final static List<PoiObject> newVisiblePois = new ArrayList<>(8*2048);

    @Override
    protected boolean checkStop() {
        return super.checkStop();
    }

    public RunnableUpdateVisibility(
            MapController mapController,
            Set<DataRetrieveThreadManager.MapDataUpdateRequest> updateRequests
    ) {
        C = mapController;
        this.updateRequests = updateRequests;
    }

    @Override
    public void run() {
        clearEverything();

        try {
            mainRun();
        } finally {
            clearEverything();
        }
    }

    private void clearEverything() {
        tempPolygonsFront.clear();
        tempPolygonsBack.clear();
        newDisplayablePois.clear();
        newVisiblePois.clear();
        newVisiblePeaks.clear();
    }

    private void mainRun() {
        if (C.L.isCurrentLocationNotSet()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdate < 250) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        lastUpdate = currentTime;

        getC().visibility.updateCameraPosLatits();

        Iterator<DataRetrieveThreadManager.MapDataUpdateRequest> iterator = updateRequests.iterator();

        while (iterator.hasNext()) {
            DataRetrieveThreadManager.MapDataUpdateRequest updateRequest;
            try {
                updateRequest = iterator.next();
            } catch (ConcurrentModificationException concurrentModificationException) {
                return;
            }
            iterator.remove();

            switch (updateRequest) {
                case DATA_SORT_POI_LIST_BY_RELEVANCE:
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    // Sorting by desired display order:
                    sortListsOfPOIs();
                case DATA_VISIBILITY_RECOMPUTE_HIDDEN_BY_MOUNTAINS:
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    // now filter POIs to get the list of newVisiblePeaks and newVisiblePois
                    updateVisiblePOIs();
                case DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP:
                    updateDisplayablePOIs();
                case DATA_VISIBILITY_RECOMPUTE_FRONT_TO_CAMERA:
                    updateFrontOfCameraPOIs();
            }
        }
    }

    private void sortListsOfPOIs() {
        double lat = C.L.getCurrentLatitude();
        double lon = C.L.getCurrentLongitude();
        double ele = C.L.getCurrentTerrainEle();

        C.O.applyToAllListsOfPOIs((listOfPeaks, listOfNonPeaks) -> {

            Collections.sort(listOfPeaks,
                    getComparatorPeaks(lat, lon, ele));
            Collections.sort(listOfNonPeaks,
                    getComparatorPois(lat, lon, ele));

        });
    }

    private void updateDisplayablePOIs() {

        tempPolygonsFront.clear();
        tempPolygonsBack.clear();
        newDisplayablePois.clear();

        C.O.iterateOverVisiblePoisUnstoppable(poiObject -> {
            poiObject.drawLabel.updatePosition();
            poiObject.drawLabel.updateGlyphData(tempPolygonsFront, tempPolygonsBack);
            if (poiObject.drawLabel.isVisibleIgnoreCameraOrientation())
                newDisplayablePois.add(poiObject);
        });

        C.O.setDisplayablePoiList(newDisplayablePois);
    }

    private void updateFrontOfCameraPOIs() {
        newDisplayablePois.clear();
        C.O.iterateOverVisiblePoisUnstoppable(poiObject -> {
            if (!poiObject.drawLabel.isVisibleIgnoreCameraOrientation()) {
                return;
            }
            poiObject.drawLabel.updateHiddenBehindCamera();
            if (poiObject.drawLabel.isVisible())
                newDisplayablePois.add(poiObject);
        });

        C.O.setDisplayablePoiList(newDisplayablePois);
    }

    private void updateVisiblePOIs() {

        MapViewerScreen mapViewerScreen = getC().getMapViewerScreen();
        mapViewerScreen.impactPixmap.requestUpdatedImpactPixmap();

        newVisiblePeaks.clear();
        newVisiblePois.clear();

        C.O.iterateOverAllLists(poiObject -> {
            DrawLabel drawLabel = poiObject.drawLabel;

            drawLabel.lock.lock();
            try {
                drawLabel.updatePosition();
                drawLabel.resetVisibility();
                drawLabel.updateHiddenByMountains();
            } finally {

                drawLabel.lock.unlock();
            }

            if (drawLabel.isVisibleByMountains()) {
                if (drawLabel.drawLabelCategory == DrawLabelCategory.PEAK)
                    newVisiblePeaks.add(poiObject);
                else
                    newVisiblePois.add(poiObject);
            }
        });

        // Collections.sort(newVisiblePeaks, getComparatorPeaks(getC().L.getTargetLatitude(), getC().L.getTargetLongitude(), getC().L.getCurrentTerrainEle()));
        // Collections.sort(newVisiblePois, getComparatorPois(getC().L.getTargetLatitude(), getC().L.getTargetLongitude(), getC().L.getCurrentTerrainEle()));

        newVisiblePeaks.addAll(newVisiblePois);
        C.O.setVisiblePoiList(newVisiblePeaks);
    }

}
