package com.peaknav.routing;

import java.util.List;
import java.util.Locale;

/**
 * A route written as a GPX track, the format the GPX viewer opens.
 *
 * <p>The ways it follows go in with it, as far as GPX has room for them. GPX has no notion of a
 * track's parts having names, but every track point may carry a name, a description and a type,
 * and extensions; so the first point of each stretch is named after its way ("12 Sentiero dei
 * Fiori"), described (the kind of way, its difficulty, how long it is), typed with the
 * OpenStreetMap highway value, and carries the plain fields in a {@code peaknav:way} extension -
 * other apps show the first three, and PeakNav reads the extension back ({@code GpxParser}). The
 * track's own description lists the stretches in order, for anyone reading the file.
 */
public final class RouteGpx {

    /** The namespace of PeakNav's GPX extension. */
    public static final String NAMESPACE = "https://peaknav.com/xmlschemas/gpx/1";
    /** The extension element, as it is written and read. */
    public static final String WAY_ELEMENT = "peaknav:way";

    /** The ground's height at a point in metres, or null where it is not known. */
    public interface Elevation {
        Float metres(double latitude, double longitude);
    }

    /** How a stretch is told to a reader: its label, and a line about it. Null for the plain defaults. */
    public interface Wording {
        /** What the way is called: its number and name, or what kind of way it is. */
        String label(WayInfo way);

        /** One line about the stretch: kind of way, difficulty, length and time. */
        String describe(WalkingRouter.Stretch stretch);

        /** The whole stretch on one line, its name first unless it has none. */
        String summary(WalkingRouter.Stretch stretch);
    }

    private RouteGpx() {
    }

    public static String toGpx(String name, WalkingRouter.Route route, Elevation elevation) {
        return toGpx(name, route, elevation, null);
    }

    public static String toGpx(String name, WalkingRouter.Route route, Elevation elevation, Wording wording) {
        return toGpx(name, route, route.stretches(), elevation, wording);
    }

    /** @param stretches the route's stretches, as {@link WalkingRouter.Route#stretches(double[])} times them */
    public static String toGpx(String name, WalkingRouter.Route route, List<WalkingRouter.Stretch> stretches,
                               Elevation elevation, Wording wording) {
        StringBuilder out = new StringBuilder(256 + route.size() * 72 + stretches.size() * 256);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"PeakNav\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
                .append(" xmlns:peaknav=\"").append(NAMESPACE).append("\">\n")
                .append("<trk><name>").append(escape(name)).append("</name>");
        StringBuilder summary = new StringBuilder();
        for (WalkingRouter.Stretch stretch : stretches) {
            if (summary.length() > 0) {
                summary.append('\n');
            }
            summary.append(wording != null ? wording.summary(stretch)
                    : label(stretch.way, null) + " - " + describe(stretch, null));
        }
        if (summary.length() > 0) {
            out.append("<desc>").append(escape(summary.toString())).append("</desc>");
        }
        out.append("<type>hiking</type><trkseg>\n");
        int next = 0; // the next stretch to start
        for (int i = 0; i < route.size(); i++) {
            out.append(String.format(Locale.ROOT, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", route.lat[i], route.lon[i]));
            Float ele = elevation == null ? null : elevation.metres(route.lat[i], route.lon[i]);
            if (ele != null && !ele.isNaN()) {
                out.append(String.format(Locale.ROOT, "<ele>%.1f</ele>", ele));
            }
            // A stretch starts at its first point; the last point of the route starts none.
            if (next < stretches.size() && stretches.get(next).from == i && i < route.size() - 1) {
                WalkingRouter.Stretch stretch = stretches.get(next++);
                appendWay(out, stretch, wording);
            }
            out.append("</trkpt>\n");
        }
        return out.append("</trkseg></trk>\n</gpx>\n").toString();
    }

    /** The stretch's point fields, in the order GPX 1.1 requires: name, desc, type, extensions. */
    private static void appendWay(StringBuilder out, WalkingRouter.Stretch stretch, Wording wording) {
        WayInfo way = stretch.way;
        out.append("<name>").append(escape(label(way, wording))).append("</name>")
                .append("<desc>").append(escape(describe(stretch, wording))).append("</desc>");
        if (way != null && way.highway != null) {
            out.append("<type>").append(escape(way.highway)).append("</type>");
        }
        // An empty extension marks a leg along no way - off the network, to or from an end - so a
        // reader knows the stretch before it has ended.
        out.append("<extensions><").append(WAY_ELEMENT);
        if (way != null) {
            attribute(out, "name", way.name);
            attribute(out, "ref", way.number);
            attribute(out, "highway", way.highway);
            attribute(out, "sac_scale", way.sacScale);
            attribute(out, "tracktype", way.trackType);
        }
        out.append("/></extensions>");
    }

    private static void attribute(StringBuilder out, String key, String value) {
        if (value != null) {
            out.append(' ').append(key).append("=\"").append(escape(value)).append('"');
        }
    }

    private static String label(WayInfo way, Wording wording) {
        if (wording != null) {
            return wording.label(way);
        }
        if (way == null) {
            return "-";
        }
        String text = join(way.number, way.name);
        return text.isEmpty() ? (way.highway != null ? way.highway : "-") : text;
    }

    private static String describe(WalkingRouter.Stretch stretch, Wording wording) {
        if (wording != null) {
            return wording.describe(stretch);
        }
        WayInfo way = stretch.way;
        String kind = way == null ? "" : join(way.highway, way.sacScale != null ? way.sacScale : way.trackType);
        String length = String.format(Locale.ROOT, "%.2f km, %d min", stretch.metres / 1000,
                Math.round(stretch.seconds / 60));
        return kind.isEmpty() ? length : kind + ", " + length;
    }

    /** The non-null parts, space-separated. */
    static String join(String a, String b) {
        if (a == null) {
            return b == null ? "" : b;
        }
        return b == null ? a : a + " " + b;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
