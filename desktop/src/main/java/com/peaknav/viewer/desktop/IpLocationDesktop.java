package com.peaknav.viewer.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * Where the desktop's "go to my position" gets a position from.
 *
 * <p>A computer has no GPS, so the only thing left is the internet connection: an online
 * service is asked where the address the request came from is, and answers with the city
 * it believes that address sits in. That is what this does, and its limits are worth being
 * honest about - the answer is the city, not the street, it is wrong outright behind a VPN
 * or a mobile hotspot, and the two services below can disagree by a couple of hundred
 * kilometres. The caller says so in a toast, naming the place it landed on, so a wrong
 * answer is recognisable as one rather than looking like a broken map.
 *
 * <p>Two services, tried in order, because either one alone is a single point of failure
 * and neither needs an account or a key. Nothing is sent but the request itself; the
 * address it comes from is what the service reads. The user is asked first, once - see
 * {@code PreferencesManager.isIpLocationConsent()}.
 */
public final class IpLocationDesktop {

    /** What a lookup produced: degrees, plus the place name to show, or a failure. */
    public interface Listener {
        void located(double latitude, double longitude, String placeName);

        void failed();
    }

    private static final String[] SERVICES = {
            "https://ipwho.is/?fields=success,latitude,longitude,city,country",
            "https://get.geojs.io/v1/ip/geo.json",
    };

    /** Beyond this the user has been staring at an unmoved map for long enough. */
    private static final int TIMEOUT_MILLIS = 8000;

    private IpLocationDesktop() {
    }

    /** Asks the first service, falling back to the next one; the listener is called once. */
    public static void locate(Listener listener) {
        request(0, listener);
    }

    private static void request(int service, Listener listener) {
        if (service >= SERVICES.length) {
            listener.failed();
            return;
        }
        Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
        request.setUrl(SERVICES[service]);
        request.setTimeOut(TIMEOUT_MILLIS);
        // Some of these services answer a request without a user agent with a redirect to
        // their own home page, which parses as valid JSON-less HTML and fails silently.
        request.setHeader("User-Agent", "PeakNav");
        request.setHeader("Accept", "application/json");

        Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {
            @Override
            public void handleHttpResponse(Net.HttpResponse httpResponse) {
                if (httpResponse.getStatus().getStatusCode() != 200) {
                    request(service + 1, listener);
                    return;
                }
                if (!parse(httpResponse.getResultAsString(), listener)) {
                    request(service + 1, listener);
                }
            }

            @Override
            public void failed(Throwable t) {
                request(service + 1, listener);
            }

            @Override
            public void cancelled() {
                request(service + 1, listener);
            }
        });
    }

    /**
     * Reads a service's answer; true when it produced a position.
     *
     * <p>Both services name the fields the same way, and both are read as text: one sends
     * the degrees as numbers and the other as strings, and asking for a double would throw
     * on the second one.
     */
    private static boolean parse(String body, Listener listener) {
        try {
            JsonValue json = new JsonReader().parse(body);
            if (json == null || (json.has("success") && !json.getBoolean("success"))) {
                return false;
            }
            String lat = json.getString("latitude", null);
            String lon = json.getString("longitude", null);
            if (lat == null || lon == null) {
                return false;
            }
            double latitude = Double.parseDouble(lat.trim());
            double longitude = Double.parseDouble(lon.trim());
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                return false;
            }
            listener.located(latitude, longitude, placeName(json));
            return true;
        } catch (Exception unusableAnswer) {
            return false;
        }
    }

    /** "City, Country" from whichever of the two the service gave, or an empty string. */
    private static String placeName(JsonValue json) {
        String city = json.getString("city", "");
        String country = json.getString("country", "");
        if (city.isEmpty()) {
            return country;
        }
        if (country.isEmpty()) {
            return city;
        }
        return city + ", " + country;
    }
}
