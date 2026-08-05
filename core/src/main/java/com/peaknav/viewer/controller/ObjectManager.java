package com.peaknav.viewer.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import com.peaknav.viewer.labels.PoiObject;

public class ObjectManager {

    private final List<PoiObject> listOfPeaks = new ArrayList<>(131072);
    private final List<PoiObject> listOfNonPeaks = new ArrayList<>(131072);

    public final List<PoiObject> listOfVisiblePOIs = new ArrayList<>(65536);
    private final Set<PoiObject> setOfVisiblePOIs = new HashSet<>(65536);
    private final List<PoiObject> listOfDisplayablePOIs = new ArrayList<>(2048);
    // private final Set<PoiObject> setOfDisplayablePOIs = new HashSet<>();

    // The label renderer draws displayable POIs one label-rotation-angle at a time (each angle
    // needs its own sprite-batch transform). Rather than rescanning the whole displayable list
    // once per angle per pass — 3 passes x ~4 angles = up to 12 full scans per frame — the list
    // is bucketed by rotation angle whenever it is (re)built, so each pass touches only the POIs
    // it will actually draw. Guarded by lockDisplayable, exactly like listOfDisplayablePOIs.
    private final Map<Integer, List<PoiObject>> displayableByAngle = new HashMap<>();

    // TODO: can we get rid of locks?
    private final ReentrantLock lockVisible = new ReentrantLock();
    private final ReentrantLock lockDisplayable = new ReentrantLock();
    private final ReentrantLock lockAll = new ReentrantLock();

    public interface RunOnPoiObject {
        void run(PoiObject poiObject);
    }

    public interface RunOnAllListsOfPOIs {
        void run(final List<PoiObject> listOfPeaks, final List<PoiObject> listOfNonPeaks);
    }

    public void applyToAllListsOfPOIs(RunOnAllListsOfPOIs runnable) {
        lockAll.lock();
        try {
            runnable.run(listOfPeaks, listOfNonPeaks);
        } finally {
            lockAll.unlock();
        }
    }

    public boolean iterateOverAllLists(RunOnPoiObject runnable) {
        lockAll.lock();
        try {
            int minLength = Math.min(listOfPeaks.size(), listOfNonPeaks.size());
            for (int i = 0; i < minLength; i++) {
                runnable.run(listOfPeaks.get(i));
                runnable.run(listOfNonPeaks.get(i));
            }
            List<PoiObject> missing;
            if (listOfPeaks.size() > listOfNonPeaks.size())
                missing = listOfPeaks;
            else
                missing = listOfNonPeaks;
            for (int i = minLength; i < missing.size(); i++) {
                runnable.run(missing.get(i));
            }
            return false;
        } finally {
            lockAll.unlock();
        }
    }

    public void iterateOverDisplayablePois(RunOnPoiObject runnable) {
        lockDisplayable.lock();
        try {
            for (int i = 0; i < listOfDisplayablePOIs.size(); i++) {
                PoiObject poiObject = listOfDisplayablePOIs.get(i);
                runnable.run(poiObject);
            }
        } finally {
            lockDisplayable.unlock();
        }
    }

    // Iterate only the displayable POIs whose label rotation angle equals {@code angle}. See
    // displayableByAngle: this replaces a full-list scan + per-POI angle test with a walk over
    // just the matching bucket.
    public void iterateOverDisplayablePoisForAngle(int angle, RunOnPoiObject runnable) {
        lockDisplayable.lock();
        try {
            List<PoiObject> bucket = displayableByAngle.get(angle);
            if (bucket != null) {
                for (int i = 0; i < bucket.size(); i++) {
                    runnable.run(bucket.get(i));
                }
            }
        } finally {
            lockDisplayable.unlock();
        }
    }

    public void iterateOverVisiblePoisUnstoppable(RunOnPoiObject runnable) {
        lockVisible.lock();
        for (PoiObject poiObject : listOfVisiblePOIs) {
            runnable.run(poiObject);
        }
        lockVisible.unlock();
    }

    public void setVisiblePoiList(List<PoiObject> newVisiblePoiObjects) {
        lockVisible.lock();
        listOfVisiblePOIs.clear();
        setOfVisiblePOIs.clear();
        if (newVisiblePoiObjects != null) {
            listOfVisiblePOIs.addAll(newVisiblePoiObjects);
            setOfVisiblePOIs.addAll(newVisiblePoiObjects);
        }
        lockVisible.unlock();
    }

    public void setDisplayablePoiList(List<PoiObject> displayablePois) {
        lockDisplayable.lock();
        listOfDisplayablePOIs.clear();
        // setOfDisplayablePOIs.clear();
        // Empty the angle buckets but keep the list objects so we don't reallocate every rebuild.
        for (List<PoiObject> bucket : displayableByAngle.values()) {
            bucket.clear();
        }
        if (displayablePois != null) {
            listOfDisplayablePOIs.addAll(displayablePois);
            // setOfDisplayablePOIs.addAll(displayablePois);
            for (int i = 0; i < displayablePois.size(); i++) {
                PoiObject poiObject = displayablePois.get(i);
                int angle = poiObject.drawLabelCategory.rotationAngle;
                List<PoiObject> bucket = displayableByAngle.get(angle);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    displayableByAngle.put(angle, bucket);
                }
                bucket.add(poiObject);
            }
        }
        lockDisplayable.unlock();
    }

    public boolean isPoiInVisibleList(PoiObject poiObject) {
        return setOfVisiblePOIs.contains(poiObject);
    }

    /*
    public boolean isPoiInDisplayableList(PoiObject poiObject) {
        return setOfDisplayablePOIs.contains(poiObject);
    }
     */
}
