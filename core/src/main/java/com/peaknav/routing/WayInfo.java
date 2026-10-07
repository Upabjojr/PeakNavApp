package com.peaknav.routing;

import com.peaknav.pbf.Tag;
import com.peaknav.roads.RoadClassifier;

import java.util.List;

/**
 * What a walker is told about the way underfoot: its name and number as the signposts give them,
 * what kind of way it is, and how hard. A route is a string of these (see
 * {@link WalkingRouter.Route#stretches()}); the GPX written for it carries them, and the GPX pane
 * shows them.
 *
 * <p>Two ways that say the same are the same stretch: a path split in two by a junction with
 * nothing new to tell is not two stretches.
 */
public final class WayInfo {

    /** Its name, or the name of the hiking route it carries; null if none. */
    public final String name;
    /** Its path numbers, "12/E5", or a road's reference; null if none. */
    public final String number;
    /** The OpenStreetMap highway value: path, track, residential...; null if not known. */
    public final String highway;
    /** The SAC hiking scale as tagged (mountain_hiking, T3...); null if not graded. */
    public final String sacScale;
    /** A track's surface grade, grade1 to grade5; null if not graded. */
    public final String trackType;

    public WayInfo(String name, String number, String highway, String sacScale, String trackType) {
        this.name = blankToNull(name);
        this.number = blankToNull(number);
        this.highway = blankToNull(highway);
        this.sacScale = blankToNull(sacScale);
        this.trackType = blankToNull(trackType);
    }

    /** The way's own tags, and those of the routes it belongs to, as the map data gives them. */
    public static WayInfo of(List<Tag> tags) {
        String highway = RoadClassifier.ownValue(tags, "highway");
        boolean trail = highway != null && isTrail(highway);
        return new WayInfo(
                trail ? RoadClassifier.trailNameOf(tags) : RoadClassifier.ownValue(tags, "name"),
                trail ? RoadClassifier.trailNumberOf(tags) : RoadClassifier.ownValue(tags, "ref"),
                highway,
                RoadClassifier.ownValue(tags, "sac_scale"),
                RoadClassifier.ownValue(tags, "tracktype"));
    }

    /** Ways walked as trails, whose names and numbers are the hiking routes' too. */
    private static boolean isTrail(String highway) {
        switch (highway) {
            case "path": case "footway": case "track": case "steps": case "bridleway":
            case "cycleway": case "via_ferrata":
                return true;
            default:
                return false;
        }
    }

    /** The SAC grade 1 to 6, from either spelling of the scale; 0 if not graded or not understood. */
    public int sacGrade() {
        if (sacScale == null) {
            return 0;
        }
        String s = sacScale.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.length() > 1 && s.charAt(0) == 't' && Character.isDigit(s.charAt(1))) {
            int grade = s.charAt(1) - '0';
            return grade >= 1 && grade <= 6 ? grade : 0;
        }
        switch (s) {
            case "hiking": return 1;
            case "mountain_hiking": return 2;
            case "demanding_mountain_hiking": return 3;
            case "alpine_hiking": return 4;
            case "demanding_alpine_hiking": return 5;
            case "difficult_alpine_hiking": return 6;
            default: return 0;
        }
    }

    /** A track's grade 1 to 5; 0 if not graded. */
    public int trackGrade() {
        if (trackType == null) {
            return 0;
        }
        String t = trackType.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.length() == 6 && t.startsWith("grade") && t.charAt(5) >= '1' && t.charAt(5) <= '5') {
            return t.charAt(5) - '0';
        }
        return 0;
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WayInfo)) {
            return false;
        }
        WayInfo w = (WayInfo) o;
        return same(name, w.name) && same(number, w.number) && same(highway, w.highway)
                && same(sacScale, w.sacScale) && same(trackType, w.trackType);
    }

    @Override
    public int hashCode() {
        int h = 17;
        for (String s : new String[]{name, number, highway, sacScale, trackType}) {
            h = 31 * h + (s == null ? 0 : s.hashCode());
        }
        return h;
    }

    @Override
    public String toString() {
        return "WayInfo{" + number + " " + name + " " + highway + " " + sacScale + " " + trackType + "}";
    }
}
