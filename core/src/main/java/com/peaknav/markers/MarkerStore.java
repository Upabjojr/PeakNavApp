package com.peaknav.markers;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.XmlReader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The user's markers, kept as the waypoints of a GPX file in the app's data folder - the format
 * the app already reads and writes, which any other map app can open, and which a backup copies
 * like any file. Read once, written whole on every change (a handful of points), and written
 * through a temporary file so a crash mid-write cannot lose the ones already there.
 *
 * <p>Thread safe: the render thread draws the markers while a button adds one.
 */
public final class MarkerStore {

    /** The file's name in the app's data folder. */
    public static final String FILE_NAME = "markers.gpx";
    /** PeakNav's own waypoint extension, in its GPX namespace (RouteGpx.NAMESPACE): the flag's colour. */
    static final String MARKER_ELEMENT = "peaknav:marker";

    /** Null until known: the app's own store finds its file on first use (see {@link #MarkerStore()}). */
    private FileHandle file;
    private final boolean inAppData;
    private final List<Marker> markers = new ArrayList<>();
    private volatile int version;
    private boolean loaded;

    public MarkerStore(FileHandle file) {
        this.file = file;
        this.inAppData = false;
    }

    /**
     * The app's markers, in {@link #FILE_NAME} in its data folder. The file is looked up when first
     * needed, not here: the store is built with the app's controller, before libGDX has its files
     * set up - taken then, it was null, and markers were never written.
     */
    public MarkerStore() {
        this.file = null;
        this.inAppData = true;
    }

    private FileHandle file() {
        if (file == null && inAppData && com.badlogic.gdx.Gdx.files != null) {
            file = com.badlogic.gdx.Gdx.files.external(FILE_NAME);
        }
        return file;
    }

    /** Bumped on every change, so a drawing can tell cheaply that it is out of date. */
    public int getVersion() {
        return version;
    }

    /** The markers, in the order they were saved. A copy. */
    public synchronized List<Marker> getMarkers() {
        loadIfNeeded();
        return new ArrayList<>(markers);
    }

    /** Saves a marker; one already on that spot is replaced by it. */
    public synchronized void add(Marker marker) {
        loadIfNeeded();
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).samePlace(marker.latitude, marker.longitude)) {
                markers.remove(i);
                break;
            }
        }
        markers.add(marker);
        changed();
    }

    /** Removes the marker on that spot; false if there was none. */
    public synchronized boolean remove(double latitude, double longitude) {
        loadIfNeeded();
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).samePlace(latitude, longitude)) {
                markers.remove(i);
                changed();
                return true;
            }
        }
        return false;
    }

    /**
     * Puts {@code changed} in place of the marker on its spot, keeping its place in the list: a
     * renamed or recoloured marker. False if there is none there.
     */
    public synchronized boolean update(Marker changed) {
        loadIfNeeded();
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).samePlace(changed.latitude, changed.longitude)) {
                markers.set(i, changed);
                changed();
                return true;
            }
        }
        return false;
    }

    /** Removes every marker. */
    public synchronized void clear() {
        loadIfNeeded();
        markers.clear();
        changed();
    }

    /** All the markers as a GPX file's text, to share. */
    public synchronized String toGpxText() {
        loadIfNeeded();
        return toGpx(markers);
    }

    /** "Marker 3": the first number no saved marker is already called by. */
    public synchronized String nextDefaultName(String word) {
        loadIfNeeded();
        for (int n = 1; ; n++) {
            String name = word + " " + n;
            boolean taken = false;
            for (Marker m : markers) {
                if (m.name.equals(name)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return name;
            }
        }
    }

    private void changed() {
        version++;
        save();
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        FileHandle file = file();
        if (file == null) {
            // No files yet; try again next time rather than settle for an empty list.
            return;
        }
        loaded = true;
        if (!file.exists()) {
            return;
        }
        try {
            markers.addAll(parse(file.readString("UTF-8")));
        } catch (RuntimeException e) {
            // An unreadable file must not take the app down; it is left as it is, not
            // overwritten, until a marker is saved.
            System.err.println("[Markers] could not read " + file.path() + ": " + e.getMessage());
        }
        version++;
    }

    private void save() {
        FileHandle file = file();
        if (file == null) {
            System.err.println("[Markers] no data folder yet; not written");
            return;
        }
        FileHandle temporary = file.sibling(file.name() + ".tmp");
        temporary.writeString(toGpx(markers), false, "UTF-8");
        temporary.moveTo(file);
    }

    /** The markers of a GPX file: its waypoints. */
    static List<Marker> parse(String xml) {
        List<Marker> out = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return out;
        }
        XmlReader.Element root = new XmlReader().parse(xml);
        if (root == null) {
            return out;
        }
        for (XmlReader.Element wpt : root.getChildrenByName("wpt")) {
            try {
                double lat = Double.parseDouble(wpt.getAttribute("lat"));
                double lon = Double.parseDouble(wpt.getAttribute("lon"));
                XmlReader.Element ele = wpt.getChildByName("ele");
                XmlReader.Element name = wpt.getChildByName("name");
                XmlReader.Element time = wpt.getChildByName("time");
                XmlReader.Element sym = wpt.getChildByName("sym");
                XmlReader.Element extensions = wpt.getChildByName("extensions");
                XmlReader.Element own = extensions == null ? null : extensions.getChildByName(MARKER_ELEMENT);
                out.add(new Marker(
                        name != null && name.getText() != null ? name.getText() : "",
                        lat, lon,
                        ele != null && ele.getText() != null ? Double.parseDouble(ele.getText().trim()) : Double.NaN,
                        time != null && time.getText() != null ? parseTime(time.getText().trim()) : 0L,
                        MarkerColor.parse(own == null ? null : own.getAttribute("color", null),
                                sym == null ? null : sym.getText())));
            } catch (RuntimeException skipped) {
                // One malformed waypoint costs only itself.
            }
        }
        return out;
    }

    static String toGpx(List<Marker> markers) {
        StringBuilder out = new StringBuilder(256 + markers.size() * 160);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"PeakNav\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
                .append(" xmlns:peaknav=\"").append(com.peaknav.routing.RouteGpx.NAMESPACE).append("\">\n");
        for (Marker m : markers) {
            out.append(String.format(Locale.ROOT, "<wpt lat=\"%.7f\" lon=\"%.7f\">", m.latitude, m.longitude));
            if (!Double.isNaN(m.elevation)) {
                out.append(String.format(Locale.ROOT, "<ele>%.1f</ele>", m.elevation));
            }
            if (m.created > 0) {
                out.append("<time>").append(formatTime(m.created)).append("</time>");
            }
            // GPX 1.1 order: ele, time, name, sym, extensions.
            out.append("<name>").append(escape(m.name)).append("</name>");
            if (m.color.gpxSymbol != null) {
                out.append("<sym>").append(m.color.gpxSymbol).append("</sym>");
            }
            out.append("<extensions><").append(MARKER_ELEMENT).append(" color=\"").append(m.color.key())
                    .append("\"/></extensions>")
                    .append("</wpt>\n");
        }
        return out.append("</gpx>\n").toString();
    }

    private static SimpleDateFormat iso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    private static String formatTime(long millis) {
        return iso().format(new Date(millis));
    }

    private static long parseTime(String text) {
        try {
            Date date = iso().parse(text);
            return date == null ? 0L : date.getTime();
        } catch (java.text.ParseException e) {
            return 0L;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
