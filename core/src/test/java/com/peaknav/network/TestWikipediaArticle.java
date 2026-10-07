package com.peaknav.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.badlogic.gdx.utils.JsonReader;

import org.junit.jupiter.api.Test;

public class TestWikipediaArticle {

    /** Wikidata's answer for the Matterhorn, narrowed to the Italian and English Wikipedias. */
    private static final String MATTERHORN = "{\"entities\":{\"Q1374\":{\"type\":\"item\",\"id\":\"Q1374\","
            + "\"sitelinks\":{\"enwiki\":{\"site\":\"enwiki\",\"title\":\"Matterhorn\",\"badges\":[],"
            + "\"url\":\"https://en.wikipedia.org/wiki/Matterhorn\"},\"itwiki\":{\"site\":\"itwiki\","
            + "\"title\":\"Cervino\",\"badges\":[],\"url\":\"https://it.wikipedia.org/wiki/Cervino\"}}}},"
            + "\"success\":1}";
    /** An entry with an English article only, asked for in Norwegian. */
    private static final String ENGLISH_ONLY = "{\"entities\":{\"Q3886498\":{\"type\":\"item\","
            + "\"id\":\"Q3886498\",\"sitelinks\":{\"enwiki\":{\"site\":\"enwiki\",\"title\":\"Oscilla\","
            + "\"badges\":[],\"url\":\"https://en.wikipedia.org/wiki/Oscilla\"}}}},\"success\":1}";

    @Test
    void theReadersLanguageComesFirst() {
        WikipediaArticle.Article a = WikipediaArticle.pick(new JsonReader().parse(MATTERHORN), "Q1374", "it");
        assertEquals("Cervino", a.title);
        assertEquals("https://it.wikipedia.org/wiki/Cervino", a.url);
        assertEquals("it", a.language);
    }

    @Test
    void englishStandsInWhereTheReadersWikipediaHasNone() {
        WikipediaArticle.Article a = WikipediaArticle.pick(new JsonReader().parse(ENGLISH_ONLY), "Q3886498", "no");
        assertEquals("Oscilla", a.title);
        assertEquals("en", a.language);
    }

    @Test
    void noArticleNoLink() {
        assertNull(WikipediaArticle.pick(new JsonReader().parse(
                "{\"entities\":{\"Q5\":{\"type\":\"item\",\"id\":\"Q5\",\"sitelinks\":{}}},\"success\":1}"), "Q5", "de"));
        assertNull(WikipediaArticle.pick(new JsonReader().parse(
                "{\"entities\":{\"Q5\":{\"id\":\"Q5\",\"missing\":\"\"}},\"success\":1}"), "Q5", "de"));
        assertNull(WikipediaArticle.pick(new JsonReader().parse(MATTERHORN), "Q999", "it"));
    }
}
