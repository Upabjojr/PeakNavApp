package com.peaknav.routing;

import java.util.Locale;

/** A route written as a GPX track, the format the GPX viewer opens. */
public final class RouteGpx {

    /** The ground's height at a point in metres, or null where it is not known. */
    public interface Elevation {
        Float metres(double latitude, double longitude);
    }

    private RouteGpx() {
    }

    public static String toGpx(String name, WalkingRouter.Route route, Elevation elevation) {
        StringBuilder out = new StringBuilder(128 + route.size() * 72);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"PeakNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                .append("<trk><name>").append(escape(name)).append("</name><trkseg>\n");
        for (int i = 0; i < route.size(); i++) {
            out.append(String.format(Locale.ROOT, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", route.lat[i], route.lon[i]));
            Float ele = elevation == null ? null : elevation.metres(route.lat[i], route.lon[i]);
            if (ele != null && !ele.isNaN()) {
                out.append(String.format(Locale.ROOT, "<ele>%.1f</ele>", ele));
            }
            out.append("</trkpt>\n");
        }
        return out.append("</trkseg></trk>\n</gpx>\n").toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
