package com.peaknav.viewer.pois;

import static com.peaknav.utils.PeakNavUtils.getLogger;

import com.peaknav.utils.StoppableRunnable;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.labels.PoiObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class RunnableRetrievePOIs extends StoppableRunnable {

    private static final String TAG = "RunnableRetrievePOIs";
    private final MapController C;

    public RunnableRetrievePOIs(MapController C) {
        // setName("thread-ThreadRetrievePOIs");
        this.C = C;
    }

    @Override
    public void run() {
        getLogger().debug(TAG, "Entering RunnableRetrievePOIs.run()");

        getLogger().debug(TAG, "Calling redactNewPoiLists");
        // TODO: sometimes sth happens here and data aren't loaded:
        redactNewPoiListsLazy();

        getLogger().debug(TAG, "Wait released");

    }

    private void redactNewPoiListsLazy() {
        getLogger().debug(TAG, "Calling C.readMapDataManager");

        final List<PoiObject> newListOfPeaks = new LinkedList<>();
        final List<PoiObject> newListOfNonPeaks = new LinkedList<>();

        getLogger().debug(TAG, "Calling C.mapDataManager.getPeaks(...)");
        // what happens if "peaksNearby" is being drawn when this overwrite occurs?
        double currentLatitude = C.L.getCurrentLatitude();
        double currentLongitude =  C.L.getCurrentLongitude();
        C.mapDataManager.getPOIsByCoordLazy(currentLatitude, currentLongitude, foundPOIs -> {
            getLogger().debug(TAG, "Retrieved " + foundPOIs.size() + " peaks");
            for (PoiObject poiObject : foundPOIs) {
                poiObject.updatePositionToTargetLongitude();
                poiObject.fillDrawLabel(poiObject.drawLabelCategory);
                assert poiObject.drawLabel != null;
                if (poiObject.drawLabelCategory == DrawLabelCategory.PEAK) {
                    newListOfPeaks.add(poiObject);
                } else {
                    newListOfNonPeaks.add(poiObject);
                }
                checkStopThrow();
            }
            getLogger().debug(TAG, "Peak data added to peakDataNew");

            getLogger().debug(TAG, "Calling lock");
            C.dataRetrieveThreadManager.stopRunnableUpdateVisibility();
            C.O.applyToAllListsOfPOIs((listOfPeaks, listOfNonPeaks) -> {
                listOfPeaks.clear();
                listOfPeaks.addAll(newListOfPeaks);
                listOfNonPeaks.clear();
                listOfNonPeaks.addAll(newListOfNonPeaks);

                checkStopThrow();
            });

            C.dataRetrieveThreadManager.triggerUpdateVisibilityPositionChanged();

        });

    }
    private void redactNewPoiLists(List<PoiObject> newListOfPeaks, List<PoiObject> newListOfNonPeaks) {

        getLogger().debug(TAG, "Calling C.readMapDataManager");

        getLogger().debug(TAG, "Calling C.mapDataManager.getPeaks(...)");
        // what happens if "peaksNearby" is being drawn when this overwrite occurs?
        double currentLatitude = C.L.getCurrentLatitude();
        double currentLongitude =  C.L.getCurrentLongitude();
        ArrayList<PoiObject> foundPOIs = C.mapDataManager.getPOIsByCoord(currentLatitude, currentLongitude);
        getLogger().debug(TAG, "Retrieved " + foundPOIs.size() + " peaks");
        // TODO: "updatePeakData()" should be called before "peaksNearby" is overwritten:
        // updatePeakData(foundPeaks, true);
        for (PoiObject poiObject : foundPOIs) {
            poiObject.fillDrawLabel(poiObject.drawLabelCategory);
            assert poiObject.drawLabel != null;
            if (poiObject.drawLabelCategory == DrawLabelCategory.PEAK) {
                newListOfPeaks.add(poiObject);
            } else {
                newListOfNonPeaks.add(poiObject);
            }
            checkStopThrow();
        }
        getLogger().debug(TAG, "Peak data added to peakDataNew");
    }

}
