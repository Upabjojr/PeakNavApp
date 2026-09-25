package com.peaknav.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import org.junit.jupiter.api.Test;

/** The answers of the Wikidata and Commons APIs, trimmed from real ones (Hörnlihütte, Q12681203). */
public class TestWikidataPicture {

    private static JsonValue json(String text) {
        return new JsonReader().parse(text);
    }

    @Test
    public void theImageClaimGivesTheFileName() {
        JsonValue claims = json("{\"claims\":{\"P18\":[{\"mainsnak\":{\"snaktype\":\"value\",\"property\":\"P18\","
                + "\"datavalue\":{\"value\":\"Hörnlihütte 2021.jpg\",\"type\":\"string\"}}}]}}");
        assertEquals("Hörnlihütte 2021.jpg", WikidataPicture.imageFile(claims, "Q12681203"));
    }

    @Test
    public void anEntryWithoutAnImageHasNone() {
        assertNull(WikidataPicture.imageFile(json("{\"claims\":{}}"), "Q1"));
        assertNull(WikidataPicture.imageFile(json("{\"error\":{\"code\":\"no-such-entity\"}}"), "Q1"));
    }

    @Test
    public void theCreditIsTheAuthorsNameAndTheLicence() {
        JsonValue query = json("{\"query\":{\"pages\":{\"-1\":{\"imageinfo\":[{"
                + "\"thumburl\":\"https://upload.wikimedia.org/x/480px-a.jpg\","
                + "\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:a.jpg\","
                + "\"extmetadata\":{\"Artist\":{\"value\":\"<a href=\\\"//commons.wikimedia.org/wiki/User:Whgler\\\">Whgler</a>\"},"
                + "\"LicenseShortName\":{\"value\":\"CC BY-SA 4.0\"}}}]}}}}");
        JsonValue info = WikidataPicture.firstImageInfo(query);
        assertEquals("https://upload.wikimedia.org/x/480px-a.jpg", info.getString("thumburl"));
        assertEquals("Whgler, CC BY-SA 4.0", WikidataPicture.credit(info.get("extmetadata")));
        assertEquals("", WikidataPicture.credit(null));
    }
}
