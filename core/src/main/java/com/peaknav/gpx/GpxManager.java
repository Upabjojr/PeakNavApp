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
        List<GpxTrack> parsed = GpxParser.parse(xml);
        if (parsed.isEmpty()) {
            toast(s("Gpx_no_path_found"));
            return;
        }
        int points = 0;
        for (GpxTrack track : parsed) {
            points += track.size();
        }
        add(parsed);
        toast(s("Gpx_loaded")
                .replace("{tracks}", Integer.toString(parsed.size()))
                .replace("{points}", Integer.toString(points)));
        goToTracks(parsed);
    }

    /**
     * Move the map to the just-loaded path so the user can actually see it. We aim at the centre of
     * its bounding box (the whole path sits around that point) but snap to the nearest actual track
     * point, so on a C-shaped or looping track the camera still lands on the path rather than in a
     * gap. Navigation is the same call the search uses, so running it off this thread is fine.
     */
    private void goToTracks(List<GpxTrack> tracks) {
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        for (GpxTrack track : tracks) {
            for (GpxTrack.Point p : track.getPoints()) {
                minLat = Math.min(minLat, p.lat);
                maxLat = Math.max(maxLat, p.lat);
                minLon = Math.min(minLon, p.lon);
                maxLon = Math.max(maxLon, p.lon);
            }
        }
        if (minLat > maxLat) {
            return; // no points
        }
        double centerLat = (minLat + maxLat) / 2.0;
        double centerLon = (minLon + maxLon) / 2.0;

        GpxTrack.Point nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (GpxTrack track : tracks) {
            for (GpxTrack.Point p : track.getPoints()) {
                double dLat = p.lat - centerLat;
                double dLon = p.lon - centerLon;
                double d = dLat * dLat + dLon * dLon;
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
        }
        if (nearest != null) {
            // false: don't nag about missing downloads just because we're jumping to a track.
            getC().L.setCurrentTargetCoords(nearest.lat, nearest.lon, false);
        }
    }

    /** Download a GPX file over HTTP(S) and load it. Runs on libGDX's HTTP callback thread. */
    public void loadFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
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
