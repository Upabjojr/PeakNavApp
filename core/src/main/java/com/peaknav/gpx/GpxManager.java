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
        List<GpxTrack> parsed = GpxParser.parse(xml);
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
     * Move the map so the user can survey the just-loaded track. MapViewerScreen (once the location
     * settles) flies the camera to frame the track vertically: the low point at the bottom of the
     * screen, the high point at the top. "Low" and "high" are the lowest- and highest-elevation
     * points of the track when it has elevation, otherwise its start and end. We target the low
     * point so the terrain around the camera loads. Navigation is the same call the search uses, so
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
            getC().getMapViewerScreen().requestGpxFraming(
                    low.lat, low.lon, low.hasElevation ? low.eleMeters : Float.NaN,
                    high.lat, high.lon, high.hasElevation ? high.eleMeters : Float.NaN);
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
                    loadFromXml(body);
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
        if (getNativeScreenCaller() != null) {
            getNativeScreenCaller().makeToast(message);
        }
        System.out.println("[GPX] " + message);
    }
}
