package com.peaknav.network;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Wikipedia article of a Wikidata entry, for the info panel: in the reader's language where
 * that Wikipedia has one, else in English. One request, Wikidata's list of the entry's articles
 * ("sitelinks") narrowed to those two; anything failing ends quietly with no link.
 */
public final class WikipediaArticle {

    /** Wikimedia asks for an agent that says what is calling and where to find it. */
    private static final String USER_AGENT = "PeakNav (https://peaknav.com)";
    private static final int CACHE_SIZE = 48;

    public static final class Article {
        public final String title;
        public final String url;
        /** The Wikipedia it is from: the language asked for, or "en" when that had none. */
        public final String language;

        Article(String title, String url, String language) {
            this.title = title;
            this.url = url;
            this.language = language;
        }
    }

    public interface Callback {
        /** On the render thread, only when there is an article. */
        void found(String wikidataId, Article article);
    }

    /** By entry and language; a null article remembers that there is none. */
    private static final Map<String, Article[]> CACHE = new LinkedHashMap<String, Article[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Article[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private WikipediaArticle() {
    }

    /** Looks up the entry's article in {@code language}, or English, and hands it to {@code callback}. */
    public static void fetch(final String wikidataId, String language, final Callback callback) {
        if (wikidataId == null || !wikidataId.matches("Q[0-9]+")) {
            return;
        }
        final String lang = language == null || !language.matches("[a-z]{2,3}") ? "en" : language;
        final String key = wikidataId + "/" + lang;
        Article[] cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            deliver(wikidataId, cached[0], callback);
            return;
        }
        Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
        request.setUrl("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json"
                + "&props=sitelinks/urls&ids=" + wikidataId + "&sitefilter=" + lang + "wiki"
                + (lang.equals("en") ? "" : "%7Cenwiki"));
        request.setHeader("User-Agent", USER_AGENT);
        request.setTimeOut(10000);
        Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {
            @Override
            public void handleHttpResponse(Net.HttpResponse response) {
                if (response.getStatus().getStatusCode() != 200) {
                    return;
                }
                Article article;
                try {
                    article = pick(new JsonReader().parse(response.getResultAsString()), wikidataId, lang);
                } catch (RuntimeException malformed) {
                    return;   // no link, rather than a crash on an answer of another shape
                }
                synchronized (CACHE) {
                    CACHE.put(key, new Article[]{article});
                }
                deliver(wikidataId, article, callback);
            }

            @Override
            public void failed(Throwable t) {
            }

            @Override
            public void cancelled() {
            }
        });
    }

    private static void deliver(final String wikidataId, final Article article, final Callback callback) {
        if (article != null) {
            Gdx.app.postRunnable(() -> callback.found(wikidataId, article));
        }
    }

    /** The article in {@code language} from a wbgetentities answer, else the English one, else null. */
    static Article pick(JsonValue root, String wikidataId, String language) {
        JsonValue entities = root == null ? null : root.get("entities");
        JsonValue entity = entities == null ? null : entities.get(wikidataId);
        JsonValue sitelinks = entity == null ? null : entity.get("sitelinks");
        if (sitelinks == null) {
            return null;
        }
        for (String lang : new String[]{language, "en"}) {
            JsonValue link = sitelinks.get(lang + "wiki");
            if (link != null && link.has("title") && link.has("url")) {
                return new Article(link.getString("title"), link.getString("url"), lang);
            }
        }
        return null;
    }
}
