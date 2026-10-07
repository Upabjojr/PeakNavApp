package com.peaknav.routing;

import static com.peaknav.utils.PeakNavUtils.s;

import com.peaknav.gpx.GpxTrackStats;
import com.peaknav.utils.PreferencesManager;

/**
 * Ways told in the reader's language: what a stretch is called, what kind of way it is and how
 * hard. One wording for the GPX file written for a route and for the pane that shows it, so the
 * two say the same.
 */
public final class WayWording implements RouteGpx.Wording {

    private final PreferencesManager.UnitSystem units;

    public WayWording(PreferencesManager.UnitSystem units) {
        this.units = units;
    }

    /** Its number and name as the signposts give them; else what kind of way it is. */
    @Override
    public String label(WayInfo way) {
        if (way == null) {
            return s("Way_off_path");
        }
        String text = RouteGpx.join(way.number, way.name);
        return text.isEmpty() ? typeName(way.highway) : text;
    }

    /** "Path, T3 demanding mountain hiking - 1.2 km, 25 min". */
    @Override
    public String describe(WalkingRouter.Stretch stretch) {
        return kind(stretch.way) + " - " + GpxTrackStats.formatDistance(stretch.metres, units)
                + ", " + GpxTrackStats.formatDuration(stretch.seconds / 60);
    }

    @Override
    public String summary(WalkingRouter.Stretch stretch) {
        return line(stretch.way, GpxTrackStats.formatDistance(stretch.metres, units) + ", "
                + GpxTrackStats.formatDuration(stretch.seconds / 60));
    }

    /**
     * "12 Sentiero dei Fiori - Path, T2 mountain hiking - " and {@code amount}; a way with no name
     * or number is not named after its kind twice ("Path, T2 mountain hiking - 1.2 km").
     */
    public String line(WayInfo way, String amount) {
        String kind = kind(way);
        String label = label(way);
        boolean unnamed = way == null || label.equals(typeName(way.highway));
        return (unnamed ? kind : label + " - " + kind) + " - " + amount;
    }

    /** "Path, T3 demanding mountain hiking", "Track, grade 2", "Road"; off the paths, that. */
    public static String kind(WayInfo way) {
        if (way == null) {
            return s("Way_off_path");
        }
        String difficulty = difficulty(way);
        String type = typeName(way.highway);
        return difficulty == null ? type : type + ", " + difficulty;
    }

    /** The SAC grade for a trail, the surface grade for a track; null if the way has neither. */
    public static String difficulty(WayInfo way) {
        int sac = way.sacGrade();
        if (sac > 0) {
            return s("Way_sac_" + sac);
        }
        int track = way.trackGrade();
        if (track > 0) {
            return s("Way_track_grade").replace("{n}", String.valueOf(track));
        }
        return null;
    }

    /** The kind of way an OpenStreetMap highway value names, in the reader's words. */
    public static String typeName(String highway) {
        if (highway == null) {
            return s("Way_type_other");
        }
        switch (highway) {
            case "path":
                return s("Way_type_path");
            case "footway":
                return s("Way_type_footway");
            case "track":
                return s("Way_type_track");
            case "steps":
                return s("Way_type_steps");
            case "bridleway":
                return s("Way_type_bridleway");
            case "cycleway":
                return s("Way_type_cycleway");
            case "via_ferrata":
                return s("Way_type_via_ferrata");
            case "pedestrian":
                return s("Way_type_pedestrian");
            case "primary": case "primary_link": case "secondary": case "secondary_link":
            case "tertiary": case "tertiary_link": case "unclassified": case "residential":
            case "living_street": case "service": case "road":
                return s("Way_type_road");
            default:
                return s("Way_type_other");
        }
    }
}
