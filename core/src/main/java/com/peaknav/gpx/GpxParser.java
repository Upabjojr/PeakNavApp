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
            root = new XmlReader().parse(xml);
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

    private static void addPoints(GpxTrack track, Array<XmlReader.Element> pts) {
        for (XmlReader.Element pt : pts) {
            Float lat = attrFloat(pt, "lat");
            Float lon = attrFloat(pt, "lon");
            if (lat == null || lon == null) {
                continue;
            }
            float ele = 0f;
            boolean hasEle = false;
            String eleText = childText(pt, "ele");
            if (eleText != null) {
                try {
                    ele = Float.parseFloat(eleText.trim());
                    hasEle = true;
                } catch (NumberFormatException ignored) {
                    // leave without elevation
                }
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

    private static Array<XmlReader.Element> childrenNamed(XmlReader.Element parent, String name) {
        Array<XmlReader.Element> out = parent.getChildrenByName(name);
        return (out != null) ? out : new Array<XmlReader.Element>(0);
    }

    private static String childText(XmlReader.Element parent, String name) {
        XmlReader.Element child = parent.getChildByName(name);
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
