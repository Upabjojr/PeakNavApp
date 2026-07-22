package com.peaknav.viewer.labels;

import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.graphics.Color;
import com.peaknav.utils.PreferencesManager;

import java.util.LinkedList;
import java.util.List;

public enum DrawLabelCategory {
    // Order corresponds to priority in visualizing labels!
    //
    // Label colours are picked for readability against the map rather than for decoration:
    //  - the text stays black, which gives 10:1 or better against every fill below;
    //  - the fills are light (so the black text stays legible) and are kept well away in hue
    //    from sky blue, the background peak and place labels most often sit on. The old light
    //    blue fill was almost the same hue as the sky, and the old pure white one disappeared
    //    against snow;
    //  - each category has a clearly distinct hue, so they stay tellable apart at a glance.
    //
    // Fill alone cannot separate a label from the map: any fill light enough for black text is
    // within about 1.2:1 of sky or snow in luminance. That job is done by OUTLINE_COLOR, which
    // is drawn as a border around every label box (see DrawLabel.drawRectangle).
    ALPINE_HUT(0, 25, new Color(126/255f, 214/255f, 140/255f, 1f)),
    PEAK(45, 100, new Color(240/255f, 176/255f, 74/255f, 1f)),
    PISTE(0, 0, new Color(214/255f, 214/255f, 222/255f, 1f)),
    PLACE(30, 25, new Color(198/255f, 170/255f, 240/255f, 1f)),
    ;

    /**
     * Border drawn around every label. Near black, so it reads against the light backgrounds the
     * fills cannot cope with: about 10:1 against sky and 15:1 against snow. Against dark
     * satellite imagery it is the light fill that provides the separation instead, so between
     * them the label has an edge on any background.
     */
    private static final Color OUTLINE_COLOR = new Color(20/255f, 22/255f, 26/255f, 1f);

    public static Color getOutlineColor() {
        return OUTLINE_COLOR;
    }

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
