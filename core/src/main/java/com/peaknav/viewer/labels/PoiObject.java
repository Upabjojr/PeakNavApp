package com.peaknav.viewer.labels;

import static com.peaknav.elevation.ElevationUtils.getElevationCorrectionForRoundEarth;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.Units.convertLatitsToMeters;
import static com.peaknav.utils.Units.convertLonitsToLatits;
import static com.peaknav.utils.Units.convertMetersToLatits;
import static com.peaknav.viewer.labels.DrawLabelCategory.PLACE;

import com.badlogic.gdx.math.Vector3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class PoiObject {

    public final String name;
    public final float lat, lon, elevation;
    public transient DrawLabel drawLabel;
    public final int isolationParent;
    public final DrawLabelCategory drawLabelCategory;
    private final Map<String, String> tags;
    private final Vector3 pos;
    private int population = -1;

    private static int getDistanceIntegerRange(PoiObject o1, double curLat, double curLon) {
        double distance1 = Math.sqrt(Math.pow(o1.lat - curLat, 2) + Math.pow(o1.lon - curLon, 2));
        int d1 = (int) Math.floor(2*distance1);
        return d1;
    }

    private static final double isolationFactor = 10.0;

    public static Comparator<PoiObject> getComparatorPeaks(double curLat, double curLon, double curEle) {
        // double ele = convertLatitsToMeters((float)curEle);
        return (o1, o2) -> {
            double ele1 = Double.max(o1.elevation, o1.isolationParent/isolationFactor);
            double ele2 = Double.max(o2.elevation, o2.isolationParent/isolationFactor);

            return Double.compare(ele2, ele1);
        };
    }

    public static Comparator<PoiObject> getComparatorPois(double curLat, double curLon, double curEle) {
        return (o1, o2) -> {
            int pop1 = o1.getPopulation();
            int pop2 = o2.getPopulation();
            if (pop1 != pop2)
                return Integer.compare(pop2, pop1);

            double distance1 = Math.pow(o1.lat - curLat, 2) + Math.pow(o1.lon - curLon, 2);
            double distance2 = Math.pow(o2.lat - curLat, 2) + Math.pow(o2.lon - curLon, 2);

            if (o1.drawLabelCategory == PLACE)
                distance1 *= getDistanceModifier(getPriorityOsmPlace(o1), 2);
            if (o2.drawLabelCategory == PLACE)
                distance2 *= getDistanceModifier(getPriorityOsmPlace(o2), 2);
            return Double.compare(distance1, distance2);
        };
    }

    private int getPopulation() {
        if (population >= 0)
            return population;

        String popS = tags.get("population");
        if (popS == null)
            population = 0;
        else {
            try {
                popS = popS.replaceAll("[., ]", "");
                population = Integer.parseInt(popS);
            } catch (NumberFormatException ignored) {
                population = 0;
            }
        }
        return population;
    }

    private static double getDistanceModifier(double val, double rescaler) {
        return 1.0 + rescaler*val;
    }

    private static double getPriorityOsmTags(PoiObject poiObject) {
        return ((double) poiObject.drawLabelCategory.ordinal())/DrawLabelCategory.values().length;
    }

    public Vector3 getPosition3D(Vector3 targetVector) {
        return targetVector.set(pos);
    }

    public void updatePositionToTargetLongitude() {
        pos.x = (float) convertLonitsToLatits(lon, getC().L.getTargetLatitude());
    }

    private static final String[] PlacePriority = {
        "municipality",
        "city",
        "borough",
        "suburb",
        "quarter",
        "neighbourhood",
        "city_block",
        "plot",
        "town",
        "village",
        "hamlet",
        "isolated_dwelling",
        "farm",
        "allotments",
        "island",
        "islet",
        "square",
        "locality",
    };

    private static final Map<String, Double> placePriorityMap = refreshPriorityMap();

    private static Map<String, Double> refreshPriorityMap() {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i < PlacePriority.length; i++) {
            map.put(PlacePriority[i], ((double)i)/PlacePriority.length);
        }
        return map;
    }

    private static double getPriorityOsmPlace(PoiObject poiObject) {
        String placeValue = poiObject.tags.get("place");
        try {
            return placePriorityMap.get(placeValue);
        } catch (Exception e) {
            return 1.0;
        }
    }

    public void fillDrawLabel(DrawLabelCategory drawLabelCategory) {
        drawLabel = new DrawLabel(drawLabelCategory, this);
        // drawLabel.updatePosition(true);
    }

    public PoiObject(
            String name, float lon, float lat,
            float elevation, Map<String, String> tags,
            int isolationParent, DrawLabelCategory drawLabelCategory) {
        this.name = getC().transliterator.transliterate(name);
        this.lon = lon;
        this.lat = lat;
        float dz = convertLatitsToMeters(getElevationCorrectionForRoundEarth(lat, lon));
        this.elevation = elevation;
        float elevationAfterRoundEarthCorrection = elevation - dz;
        this.isolationParent = isolationParent;
        this.drawLabelCategory = drawLabelCategory;
        this.tags = tags;
        this.pos = new Vector3();
        this.pos.set(
                (float)convertLonitsToLatits(lon, lat),
                lat,
                convertMetersToLatits(elevationAfterRoundEarthCorrection));
    }

    public int hashCode() {
        return name.hashCode() + drawLabel.hashCode() + ((int)lat*1000000) + ((int)lon*1000000)
                + ((int)elevation*1000000);
    }

    public boolean equals(Object o) {
        if (o instanceof PoiObject) {
            PoiObject o1 = (PoiObject) o;
            return o1.hashCode() == hashCode();
        }
        return false;
    }
}
