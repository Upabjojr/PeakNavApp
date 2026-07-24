package com.peaknav.gpx;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.XmlReader;

import java.util.ArrayList;
import java.util.List;

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
            String eleText = childText(pt, "ele");
            if (eleText != null) {
                try {
                    track.add(lat, lon, Float.parseFloat(eleText.trim()), true);
                    continue;
                } catch (NumberFormatException ignored) {
                    // fall through to no-elevation
                }
            }
            track.add(lat, lon, 0f, false);
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
