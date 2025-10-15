package com.peaknav.network;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnlineSearch {
    private final Pattern textPattern = Pattern.compile("\\s*(-?\\d+\\.?\\d*)\\s*,\\s*(-?\\d+\\.?\\d*)\\s*");

    public interface NominatimResponseListener {
        void applySearchResults(ArrayList<NominatimResponse> retVal);
    }

    public void parseDestinationText(String text, final NominatimResponseListener callback) {

        Matcher matcher = textPattern.matcher(text);
        if (matcher.matches()) {
            // matcher.group(1);
            String[] data = text.split(",");
            float longitude = Float.parseFloat(data[1].trim());
            float latitude = Float.parseFloat(data[0].trim());

            getC().L.setCurrentTargetCoords(latitude, longitude);
        } else {

            try {
                findWithNominatim(text, callback);
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
                String responseJson = httpResponse.getResultAsString();
                JsonReader jsonReader = new JsonReader();
                JsonValue list = jsonReader.parse(responseJson);

                ArrayList<NominatimResponse> retval = new ArrayList<>();

                for (int i = 0; i < list.size; i++) {
                    JsonValue jso = list.get(i);
                    // String osmType = jso.getString("osm_type");
                    // if (!osmType.equals("node")) {
                        // continue;
                    // }
                    retval.add(new NominatimResponse(jso));
                }
                float lat = getC().L.getTargetLatitude();
                float lon = getC().L.getTargetLongitude();
                Collections.sort(retval, (r1, r2) -> Double.compare(
                        Math.hypot(r1.lat - lat, r1.lon - lon),
                        Math.hypot(r2.lat - lat, r2.lon - lon)
                ));
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
