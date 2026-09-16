package com.peaknav.network;

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

    private void findWithNominatim(String text, final NominatimResponseListener callback) throws UnsupportedEncodingException {
        String encodedText = URLEncoder.encode(text, "UTF-8");

        Net.HttpRequest request = new Net.HttpRequest();
        request.setUrl(String.format("https://nominatim.openstreetmap.org/search?osmtype=N&q=%s&limit=15&format=json", encodedText));
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

                    float lat = getC().L.getTargetLatitude();
                    float lon = getC().L.getTargetLongitude();
                    Collections.sort(retval, (r1, r2) -> Double.compare(
                            Math.hypot(r1.lat - lat, r1.lon - lon),
                            Math.hypot(r2.lat - lat, r2.lon - lon)
                    ));
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
