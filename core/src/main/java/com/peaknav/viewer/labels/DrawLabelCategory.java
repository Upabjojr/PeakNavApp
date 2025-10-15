package com.peaknav.viewer.labels;

import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.graphics.Color;
import com.peaknav.utils.PreferencesManager;

import java.util.LinkedList;
import java.util.List;

public enum DrawLabelCategory {
    // Order corresponds to priority in visualizing labels!
    ALPINE_HUT(0, 25, new Color(204/255f, 255/255f, 204/255f, 0.6f)),
    PEAK(45, 100, new Color(222/255f, 184/255f, 135/255f, 0.6f)),
    PISTE(0, 0, Color.WHITE),
    PLACE(30, 25, new Color(173/255f, 216/255f, 230/255f, 0.6f)),
    ;

    private static volatile List<Integer> listOfRotationAngles = null;
    public final int rotationAngle;
    public final float shiftLabelY;
    private final Color backgroundColor;
    public final float rotationAngleCos;
    public final float rotationAngleSin;

    DrawLabelCategory(int rotationAngle, float shiftLabelY, Color backgroundColor) {
        this.rotationAngle = rotationAngle;
        this.shiftLabelY = shiftLabelY;
        this.backgroundColor = backgroundColor;

        rotationAngleCos = (float) Math.cos(Math.toRadians(rotationAngle));
        rotationAngleSin = (float) Math.sin(Math.toRadians(rotationAngle));
    }

    public static List<Integer> getAngles() {
        if (listOfRotationAngles != null)
            return listOfRotationAngles;
        listOfRotationAngles = new LinkedList<>();
        for (DrawLabelCategory cat : DrawLabelCategory.values()) {
            if (listOfRotationAngles.contains(cat.rotationAngle))
                continue;
            listOfRotationAngles.add(cat.rotationAngle);
        }
        return listOfRotationAngles;
    }

    public String getTextFromDrawLabel(PoiObject poiObject) {
        if (poiObject.drawLabelCategory == PEAK) {
            String elev;
            if (P.getUnitSystem() == PreferencesManager.UnitSystem.METRIC) {
                elev = ((int) poiObject.elevation) + " m";
            } else {
                elev = ((int) Math.round(3.280839895f * poiObject.elevation)) + " ft";
            }
            return poiObject.name + " - " + elev;
        }
        return poiObject.name;
    }

    public Color getTextColor() {
        return Color.WHITE;
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }
}
