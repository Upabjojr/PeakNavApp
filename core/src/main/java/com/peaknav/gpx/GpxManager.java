package com.peaknav.gpx;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the GPX paths currently loaded, and knows how to load more from a file's text or from a
 * URL. This is pure data + networking (no OpenGL), so it lives in the controller and can be driven
 * from any thread; {@link GpxPathRenderer} watches {@link #getVersion()} and rebuilds its mesh on
 * the render thread whenever the set of paths changes.
 */
public class GpxManager {

    private final List<GpxTrack> tracks = new ArrayList<>();
    private volatile int version = 0;
    /**
     * The text and a file name of the last GPX that exists nowhere on the device - downloaded from
     * the web, or made on the map by "route to here" - so it can be saved or shared. A GPX opened
     * from a file is already a file, and is not offered again. Null when there is none.
     */
    private String shareableXml;
    private String shareableName;

    /** Bumped every time the set of paths changes, so the renderer knows to rebuild. */
    public int getVersion() {
        return version;
    }

    /** A snapshot copy, safe to iterate off the manager's lock. */
    public synchronized List<GpxTrack> getTracks() {
        return new ArrayList<>(tracks);
    }

    public synchronized boolean isEmpty() {
        return tracks.isEmpty();
    }

    public synchronized void clear() {
        shareableXml = null;
        shareableName = null;
        if (tracks.isEmpty()) {
            return;
        }
        tracks.clear();
        version++;
        toast(s("Gpx_cleared"));
    }

    private synchronized void add(List<GpxTrack> newTracks) {
        tracks.addAll(newTracks);
        version++;
    }

    /** Parse GPX text and add whatever paths it contains, with user feedback either way. */
    public void loadFromXml(String xml) {
        loadFromXml(xml, true);
    }

    /**
     * Parse GPX text and add whatever paths it contains. With {@code navigate} the map flies to
     * frame the track and says so, as a user loading a file expects; without it the paths are
     * simply added and drawn where they are - for a script that has its own camera plan, such as
     * the headless renderer's, a framing fly would fight it. Returns how many paths were added.
     */
    public int loadFromXml(String xml, boolean navigate) {
        return loadParsed(GpxParser.parse(xml), navigate);
    }

    private int loadParsed(List<GpxTrack> parsed, boolean navigate) {
        if (parsed.isEmpty()) {
            if (navigate) {
                toast(s("Gpx_no_path_found"));
            }
            return 0;
        }
        int points = 0;
        for (GpxTrack track : parsed) {
            points += track.size();
        }
        add(parsed);
        if (navigate) {
            toast(s("Gpx_loaded")
                    .replace("{tracks}", Integer.toString(parsed.size()))
                    .replace("{points}", Integer.toString(points)));
            goToTracks(parsed);
        }
        return parsed.size();
    }

    /**
     * Loads GPX that exists nowhere else on the device - a download, or a route made on the map -
     * and keeps its text so it can be saved or shared (see {@link #getShareableXml()}).
     *
     * @param fileName what to call the file, without a folder; ".gpx" is added when missing
     */
    public int loadShareableXml(String xml, String fileName, boolean navigate) {
        int added = loadFromXml(xml, navigate);
        keepShareable(added, xml, fileName);
        return added;
    }

    /**
     * Loads GPX the app made itself, whose heights it took from the terrain - a route to a tapped
     * point - as {@link #loadShareableXml} does, marking its tracks so no one compares those
     * heights with the terrain they came from.
     */
    public int loadComputedXml(String xml, String fileName, boolean navigate) {
        List<GpxTrack> parsed = GpxParser.parse(xml);
        for (GpxTrack track : parsed) {
            track.markHeightsComputed();
        }
        int added = loadParsed(parsed, navigate);
        keepShareable(added, xml, fileName);
        return added;
    }

    private void keepShareable(int added, String xml, String fileName) {
        if (added > 0) {
            synchronized (this) {
                shareableXml = xml;
                shareableName = safeFileName(fileName);
                version++;
            }
        }
    }

    public synchronized boolean hasShareable() {
        return shareableXml != null;
    }

    public synchronized String getShareableXml() {
        return shareableXml;
    }

    public synchronized String getShareableName() {
        return shareableName;
    }

    /** A plain file name ending in .gpx: no folders, nothing a file system could refuse. */
    static String safeFileName(String name) {
        String base = name == null ? "" : name.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int query = base.indexOf('?');
        if (query >= 0) {
            base = base.substring(0, query);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")) {
            base = base.substring(0, base.length() - 4);
        }
        if (base.isEmpty()) {
            base = "PeakNav_track";
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + ".gpx";
    }

    /** Points of the loaded tracks the framing looks at, at most: a sample is plenty. */
    private static final int FRAMING_POINTS = 400;

    /**
     * Move the map so the user can survey the just-loaded track. MapViewerScreen (once the location
     * settles) flies the camera to a view of the whole track (see GpxFraming), looking up it from
     * its low end: "low" and "high" are the lowest- and highest-elevation points of the track when
     * it has elevation, otherwise its start and end. We target the low point so the terrain around
     * the camera loads. Navigation is the same call the search uses, so
     * running it off this thread is fine.
     */
    private void goToTracks(List<GpxTrack> tracks) {
        GpxTrack.Point first = null;
        GpxTrack.Point last = null;
        GpxTrack.Point lowEle = null;
        GpxTrack.Point highEle = null;
        for (GpxTrack track : tracks) {
            List<GpxTrack.Point> pts = track.getPoints();
            if (pts.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = pts.get(0);
            }
            last = pts.get(pts.size() - 1);
            for (GpxTrack.Point p : pts) {
                if (!p.hasElevation) {
                    continue;
                }
                if (lowEle == null || p.eleMeters < lowEle.eleMeters) {
                    lowEle = p;
                }
                if (highEle == null || p.eleMeters > highEle.eleMeters) {
                    highEle = p;
                }
            }
        }
        if (first == null) {
            return;
        }
        GpxTrack.Point low;
        GpxTrack.Point high;
        if (lowEle != null && highEle != null && lowEle != highEle) {
            low = lowEle;
            high = highEle;
        } else {
            low = first;
            high = last;
        }
        if (getC().getMapViewerScreen() != null) {
            // Every track, sampled: the framing fits all of it on screen, not just its ends.
            int total = 0;
            for (GpxTrack track : tracks) {
                total += track.getPoints().size();
            }
            int step = Math.max(1, total / FRAMING_POINTS);
            int count = 0;
            float[] lats = new float[total / step + tracks.size()];
            float[] lons = new float[lats.length];
            float[] eles = new float[lats.length];
            for (GpxTrack track : tracks) {
                List<GpxTrack.Point> pts = track.getPoints();
                for (int i = 0; i < pts.size(); i += step) {
                    GpxTrack.Point p = pts.get(i);
                    if (count == lats.length) {
                        break;
                    }
                    lats[count] = p.lat;
                    lons[count] = p.lon;
                    eles[count] = p.hasElevation ? p.eleMeters : Float.NaN;
                    count++;
                }
            }
            getC().getMapViewerScreen().requestGpxFraming(
                    low.lat, low.lon, low.hasElevation ? low.eleMeters : Float.NaN,
                    high.lat, high.lon, high.hasElevation ? high.eleMeters : Float.NaN,
                    java.util.Arrays.copyOf(lats, count), java.util.Arrays.copyOf(lons, count),
                    java.util.Arrays.copyOf(eles, count));
        }
        // false: don't nag about missing downloads just because we're jumping to a track.
        getC().L.setCurrentTargetCoords(low.lat, low.lon, false);
    }

    /** Download a GPX file over HTTP(S) and load it. Runs on libGDX's HTTP callback thread. */
    public void loadFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        if (com.peaknav.network.HttpsPolicy.isBlockedHttp(url)) {
            toast(com.peaknav.network.HttpsPolicy.HTTP_BLOCKED_MESSAGE);
            return;
        }
        toast(s("Gpx_downloading"));
        Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
        request.setUrl(url.trim());
        Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {
            @Override
            public void handleHttpResponse(Net.HttpResponse httpResponse) {
                int status = httpResponse.getStatus().getStatusCode();
                String body = httpResponse.getResultAsString();
                if (status >= 200 && status < 400 && body != null && !body.isEmpty()) {
                    // Downloaded, so not on the device yet: offered for saving or sharing.
                    loadShareableXml(body, url.trim(), true);
                } else {
                    toast(s("Gpx_download_failed"));
                }
            }

            @Override
            public void failed(Throwable t) {
                toast(s("Gpx_download_failed"));
            }

            @Override
            public void cancelled() {
                toast(s("Gpx_download_failed"));
            }
        });
    }

    private static void toast(String message) {
        // Only to an app that is running: asking for the screen caller would otherwise create one.
        if (com.peaknav.viewer.MapViewerSingleton.hasAppInstance() && getNativeScreenCaller() != null) {
            getNativeScreenCaller().makeToast(message);
        }
        System.out.println("[GPX] " + message);
    }
}
