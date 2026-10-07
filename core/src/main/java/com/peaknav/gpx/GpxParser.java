package com.peaknav.gpx;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.XmlReader;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal GPX reader built on libGDX's {@link XmlReader} (no extra dependency, works on every
 * platform). It pulls the paths out of a GPX document: each track segment ({@code trk/trkseg}) and
 * each route ({@code rte}) becomes a {@link GpxTrack}. Standalone waypoints ({@code wpt}) are not
 * paths, so they are ignored here.
 */
public final class GpxParser {

    private GpxParser() {
    }

    public static List<GpxTrack> parse(String xml) {
        List<GpxTrack> tracks = new ArrayList<>();
        if (xml == null || xml.isEmpty()) {
            return tracks;
        }
        XmlReader.Element root;
        try {
            root = new XmlReader().parse(readable(xml));
        } catch (Exception e) {
            // Not valid XML / GPX.
            return tracks;
        }
        if (root == null) {
            return tracks;
        }

        // Tracks: <trk><name>?<trkseg><trkpt lat lon><ele>?...
        for (XmlReader.Element trk : childrenNamed(root, "trk")) {
            String trackName = childText(trk, "name");
            for (XmlReader.Element seg : childrenNamed(trk, "trkseg")) {
                GpxTrack track = new GpxTrack(trackName);
                addPoints(track, childrenNamed(seg, "trkpt"));
                if (track.size() >= 2) {
                    tracks.add(track);
                }
            }
        }

        // Routes: <rte><name>?<rtept lat lon><ele>?...
        for (XmlReader.Element rte : childrenNamed(root, "rte")) {
            GpxTrack track = new GpxTrack(childText(rte, "name"));
            addPoints(track, childrenNamed(rte, "rtept"));
            if (track.size() >= 2) {
                tracks.add(track);
            }
        }

        return tracks;
    }

    /**
     * The document as the reader can take it. A byte-order mark in front, which Windows tools
     * write, is not XML to it: the whole file was refused, and the track "not found". And it
     * decodes character references in hexadecimal only, so the decimal ones are rewritten:
     * "Caf&#233;" came out as "Caf#233".
     */
    static String readable(String xml) {
        int start = 0;
        while (start < xml.length() && (xml.charAt(start) == '\uFEFF' || xml.charAt(start) <= ' ')) {
            start++;
        }
        String text = start == 0 ? xml : xml.substring(start);
        if (text.indexOf("&#") < 0) {
            return text;
        }
        Matcher decimal = DECIMAL_REFERENCE.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (decimal.find()) {
            String replacement;
            try {
                replacement = "&#x" + Integer.toHexString(Integer.parseInt(decimal.group(1))) + ";";
            } catch (NumberFormatException tooLong) {
                replacement = decimal.group();
            }
            decimal.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        decimal.appendTail(out);
        return out.toString();
    }

    private static final Pattern DECIMAL_REFERENCE = Pattern.compile("&#(\\d+);");

    /** A number that is one: parseFloat reads "NaN" and "Infinity" too, which some exporters write. */
    private static boolean isNumber(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static void addPoints(GpxTrack track, Array<XmlReader.Element> pts) {
        for (XmlReader.Element pt : pts) {
            Float lat = attrFloat(pt, "lat");
            Float lon = attrFloat(pt, "lon");
            // A point that is nowhere is left out: one NaN made the track's box NaN, so that
            // no track was drawn, and its statistics index an array at -1.
            if (lat == null || lon == null || !isNumber(lat) || !isNumber(lon)
                    || lat < -90f || lat > 90f || lon < -180f || lon > 180f) {
                continue;
            }
            float ele = 0f;
            boolean hasEle = false;
            String eleText = childText(pt, "ele");
            if (eleText != null) {
                try {
                    float read = Float.parseFloat(eleText.trim());
                    if (isNumber(read)) {
                        ele = read;
                        hasEle = true;
                    }
                } catch (NumberFormatException ignored) {
                    // leave without elevation
                }
            }
            XmlReader.Element extensions = child(pt, "extensions");
            XmlReader.Element way = extensions == null ? null
                    : extensions.getChildByName(com.peaknav.routing.RouteGpx.WAY_ELEMENT);
            if (way != null) {
                // An empty element is a stretch along no way; see RouteGpx.
                boolean none = way.getAttributes() == null || way.getAttributes().size == 0;
                track.startStretch(none ? null : new com.peaknav.routing.WayInfo(
                        way.getAttribute("name", null), way.getAttribute("ref", null),
                        way.getAttribute("highway", null), way.getAttribute("sac_scale", null),
                        way.getAttribute("tracktype", null)));
            }
            Long millis = parseTime(childText(pt, "time"));
            track.add(lat, lon, ele, hasEle,
                    millis != null ? millis : 0L, millis != null);
        }
    }

    // ISO-8601 timestamps as GPX writes them, e.g. 2023-05-01T08:30:00Z or ...+02:00. We only ever
    // use differences within a track, so the zone offset can be ignored: parsing the calendar
    // fields as UTC gives consistent, monotonic millis.
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})");

    private static Long parseTime(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TIME_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            return c.getTimeInMillis();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether an element is GPX's {@code name}, written with a namespace prefix or without:
     * {@code <gpx:trk>} is the same element as {@code <trk>}, and the reader gives the name as
     * it is written.
     */
    private static boolean isNamed(XmlReader.Element element, String name) {
        String written = element.getName();
        if (written == null) {
            return false;
        }
        int colon = written.lastIndexOf(':');
        return (colon < 0 ? written : written.substring(colon + 1)).equals(name);
    }

    private static Array<XmlReader.Element> childrenNamed(XmlReader.Element parent, String name) {
        Array<XmlReader.Element> out = new Array<XmlReader.Element>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            XmlReader.Element child = parent.getChild(i);
            if (isNamed(child, name)) {
                out.add(child);
            }
        }
        return out;
    }

    private static XmlReader.Element child(XmlReader.Element parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            XmlReader.Element child = parent.getChild(i);
            if (isNamed(child, name)) {
                return child;
            }
        }
        return null;
    }

    private static String childText(XmlReader.Element parent, String name) {
        XmlReader.Element child = child(parent, name);
        if (child == null) {
            return null;
        }
        String text = child.getText();
        return (text == null) ? null : text.trim();
    }

    private static Float attrFloat(XmlReader.Element element, String name) {
        String value = element.getAttribute(name, null);
        if (value == null) {
            return null;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
