package com.peaknav.network;

import static com.peaknav.utils.PeakNavUtils.containsUnrenderableCharacters;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.peaknav.utils.CoordinateSearch;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;

public class OnlineSearch {
    public interface NominatimResponseListener {
        void applySearchResults(ArrayList<NominatimResponse> retVal);
    }

    public void parseDestinationText(String text, final NominatimResponseListener callback) {

        // Coordinates in any of the common printed forms go straight there; anything else is a
        // name, searched for without the formatting it was pasted with.
        double[] coordinates = CoordinateSearch.parseCoordinates(text);
        if (coordinates != null) {
            getC().L.setCurrentTargetCoords(coordinates[0], coordinates[1]);
        } else {

            try {
                findWithNominatim(CoordinateSearch.cleanQuery(text), callback);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * The languages to ask Nominatim for, best first.
     *
     * <p>Without this the service answers in whatever the place's own country writes: searching
     * for Mount Everest returned it as 珠穆朗玛峰 ཇོ་མོ་གླང་མ། सगरमाथा, which no reader of this app
     * asked for and which the map font cannot even draw. English is always appended as the
     * fallback, because a place with no name in the reader's language nearly always has one in
     * English - and an English name is at least in the alphabet the reader's own language uses.
     */
    static String acceptLanguages(String interfaceLanguage) {
        if (interfaceLanguage == null || interfaceLanguage.trim().isEmpty()
                || "en".equals(interfaceLanguage.trim())) {
            return "en";
        }
        return interfaceLanguage.trim() + ",en";
    }

    /** The interface language, or null before the app has built its translations. */
    private static String interfaceLanguage() {
        if (getC() == null || getC().i18n == null) {
            return null;
        }
        return getC().i18n.getLanguage();
    }

    /**
     * Orders what came back: names the reader can actually read first, and within those the
     * places nearest to where the map is looking.
     *
     * <p>Asking for a language settles nearly every result, but not the place that has no name
     * in any Latin-alphabet language at all. Rather than drop it - it may be exactly what was
     * searched for - it goes last, under the ones that can be read.
     */
    static void sortByReadabilityThenDistance(ArrayList<NominatimResponse> results,
                                              final float lat, final float lon) {
        Collections.sort(results, (r1, r2) -> {
            boolean readable1 = !containsUnrenderableCharacters(r1.displayName);
            boolean readable2 = !containsUnrenderableCharacters(r2.displayName);
            if (readable1 != readable2) {
                return readable1 ? -1 : 1;
            }
            return Double.compare(
                    Math.hypot(r1.lat - lat, r1.lon - lon),
                    Math.hypot(r2.lat - lat, r2.lon - lon));
        });
    }

    private void findWithNominatim(String text, final NominatimResponseListener callback) throws UnsupportedEncodingException {
        String encodedText = URLEncoder.encode(text, "UTF-8");

        Net.HttpRequest request = new Net.HttpRequest();
        request.setUrl(String.format("https://nominatim.openstreetmap.org/search?osmtype=N&q=%s&limit=15&format=json&accept-language=%s",
                encodedText, URLEncoder.encode(acceptLanguages(interfaceLanguage()), "UTF-8")));
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        request.setContent("");
        request.setMethod("GET");

        Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {
            @Override
            public void handleHttpResponse(Net.HttpResponse httpResponse) {
                ArrayList<NominatimResponse> retval = new ArrayList<>();
                try {
                    String responseJson = httpResponse.getResultAsString();
                    JsonValue list = new JsonReader().parse(responseJson);

                    // Nominatim can return a non-array error/rate-limit body, or
                    // an empty/unparseable one; only iterate a real result array.
                    if (list != null && list.isArray()) {
                        for (JsonValue jso = list.child; jso != null; jso = jso.next) {
                            try {
                                retval.add(new NominatimResponse(jso));
                            } catch (Exception ignored) {
                                // Skip malformed entries.
                            }
                        }
                    }

                    sortByReadabilityThenDistance(retval,
                            getC().L.getTargetLatitude(), getC().L.getTargetLongitude());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                callback.applySearchResults(retval);
            }

            @Override
            public void failed(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void cancelled() {

            }
        });

    }
}
