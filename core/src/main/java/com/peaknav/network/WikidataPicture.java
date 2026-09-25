package com.peaknav.network;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The picture a Wikidata entry names (its "image", P18), fetched for the info panel: a
 * thumbnail from Wikimedia Commons, with who made it and under what licence, which Commons'
 * terms ask to be shown with it.
 *
 * <p>Three requests, all through {@code Gdx.net}: the entry's P18 claim, the file's thumbnail
 * address and credit from the Commons API, then the thumbnail itself. Anything failing - no
 * connection, no picture, a format the decoder cannot read - ends quietly with no picture.
 */
public final class WikidataPicture {

    /** Wikimedia asks for an agent that says what is calling and where to find it. */
    private static final String USER_AGENT = "PeakNav (https://peaknav.com)";
    private static final int THUMB_WIDTH = 480;
    private static final int CACHE_SIZE = 24;

    public static final class Picture {
        /** Decoded, not yet a texture: the caller makes one on the render thread. */
        public final Pixmap pixmap;
        /** "Author, licence", as short as Commons gives it; may be empty. */
        public final String credit;
        /** The file's page on Commons, where the full credit and licence are. */
        public final String pageUrl;

        Picture(Pixmap pixmap, String credit, String pageUrl) {
            this.pixmap = pixmap;
            this.credit = credit;
            this.pageUrl = pageUrl;
        }
    }

    public interface Callback {
        /** On the render thread, only when there is a picture. The pixmap is the callee's to dispose. */
        void loaded(String wikidataId, Picture picture);
    }

    /** Thumbnail bytes and credit by entry, and the entries known to have none (null bytes). */
    private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private static final class Cached {
        final byte[] bytes;
        final String credit, pageUrl;

        Cached(byte[] bytes, String credit, String pageUrl) {
            this.bytes = bytes;
            this.credit = credit;
            this.pageUrl = pageUrl;
        }
    }

    private WikidataPicture() {
    }

    /** Fetches the entry's picture and hands it to {@code callback}; nothing happens if it has none. */
    public static void fetch(final String wikidataId, final Callback callback) {
        if (wikidataId == null || !wikidataId.matches("Q[0-9]+")) {
            return;
        }
        Cached cached;
        synchronized (CACHE) {
            cached = CACHE.get(wikidataId);
        }
        if (cached != null) {
            deliver(wikidataId, cached, callback);
            return;
        }
        get("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P18&entity=" + wikidataId,
                body -> {
                    String file = imageFile(new JsonReader().parse(body), wikidataId);
                    if (file == null) {
                        remember(wikidataId, new Cached(null, null, null));
                        return;
                    }
                    fetchFromCommons(wikidataId, file, callback);
                });
    }

    private static void fetchFromCommons(final String wikidataId, String file, final Callback callback) {
        String url;
        try {
            url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo"
                    + "&iiprop=url%7Cextmetadata&iiextmetadatafilter=Artist%7CLicenseShortName"
                    + "&iiurlwidth=" + THUMB_WIDTH + "&titles=" + URLEncoder.encode("File:" + file, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return;
        }
        get(url, body -> {
            JsonValue info = firstImageInfo(new JsonReader().parse(body));
            if (info == null || !info.has("thumburl")) {
                return;
            }
            final String pageUrl = info.getString("descriptionurl", null);
            final String credit = credit(info.get("extmetadata"));
            Net.HttpRequest request = request(info.getString("thumburl"));
            Gdx.net.sendHttpRequest(request, new Listener() {
                @Override
                public void handleHttpResponse(Net.HttpResponse response) {
                    if (response.getStatus().getStatusCode() != 200) {
                        return;
                    }
                    Cached fetched = new Cached(response.getResult(), credit, pageUrl);
                    remember(wikidataId, fetched);
                    deliver(wikidataId, fetched, callback);
                }
            });
        });
    }

    /** Decodes off the render thread, then hands over on it. */
    private static void deliver(final String wikidataId, Cached cached, final Callback callback) {
        if (cached.bytes == null) {
            return;
        }
        final Pixmap pixmap;
        try {
            pixmap = new Pixmap(cached.bytes, 0, cached.bytes.length);
        } catch (RuntimeException unreadable) {
            // A thumbnail in a format the decoder does not know (WebP, say).
            return;
        }
        final Picture picture = new Picture(pixmap, cached.credit == null ? "" : cached.credit, cached.pageUrl);
        Gdx.app.postRunnable(() -> callback.loaded(wikidataId, picture));
    }

    private static void remember(String wikidataId, Cached cached) {
        synchronized (CACHE) {
            CACHE.put(wikidataId, cached);
        }
    }

    /** The file name of the entry's first P18 claim, or null. */
    static String imageFile(JsonValue root, String wikidataId) {
        if (root == null) {
            return null;
        }
        JsonValue claims = root.get("claims");
        JsonValue p18 = claims == null ? null : claims.get("P18");
        if (p18 == null || p18.child == null) {
            return null;
        }
        JsonValue snak = p18.child.get("mainsnak");
        JsonValue value = snak == null ? null : snak.get("datavalue");
        return value == null ? null : value.getString("value", null);
    }

    static JsonValue firstImageInfo(JsonValue root) {
        JsonValue pages = root == null || root.get("query") == null ? null : root.get("query").get("pages");
        if (pages == null || pages.child == null) {
            return null;
        }
        JsonValue imageinfo = pages.child.get("imageinfo");
        return imageinfo == null ? null : imageinfo.child;
    }

    /** "Author, licence" from Commons' metadata, the author's HTML reduced to its text. */
    static String credit(JsonValue extmetadata) {
        if (extmetadata == null) {
            return "";
        }
        String artist = metadata(extmetadata, "Artist");
        String licence = metadata(extmetadata, "LicenseShortName");
        StringBuilder out = new StringBuilder();
        if (artist != null) {
            out.append(artist);
        }
        if (licence != null) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(licence);
        }
        return out.toString();
    }

    private static String metadata(JsonValue extmetadata, String key) {
        JsonValue entry = extmetadata.get(key);
        String value = entry == null ? null : entry.getString("value", null);
        if (value == null) {
            return null;
        }
        String text = value.replaceAll("<[^>]*>", " ").replaceAll("&amp;", "&").replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'").replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    private interface Body {
        void handle(String body) throws Exception;
    }

    private static void get(String url, final Body body) {
        Gdx.net.sendHttpRequest(request(url), new Listener() {
            @Override
            public void handleHttpResponse(Net.HttpResponse response) {
                if (response.getStatus().getStatusCode() != 200) {
                    return;
                }
                try {
                    body.handle(response.getResultAsString());
                } catch (Exception malformed) {
                    // No picture, rather than a crash on an answer of another shape.
                }
            }
        });
    }

    private static Net.HttpRequest request(String url) {
        Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
        request.setUrl(url);
        request.setHeader("User-Agent", USER_AGENT);
        request.setTimeOut(10000);
        return request;
    }

    /** Failures and cancellations are simply no picture. */
    private abstract static class Listener implements Net.HttpResponseListener {
        @Override
        public void failed(Throwable t) {
        }

        @Override
        public void cancelled() {
        }
    }
}
