package com.peaknav.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * What language the place search asks for, and what it does with an answer that arrives in an
 * alphabet the reader does not use. Searching for Mount Everest used to offer it as
 * "珠穆朗玛峰 ཇོ་མོ་གླང་མ། सगरमाथा" - the name its own country writes, unreadable here and
 * undrawable by the map font.
 */
class TestSearchLanguage {

    @Test
    @DisplayName("the reader's language is asked for first, with English behind it")
    void theInterfaceLanguageLeads() {
        assertEquals("it,en", OnlineSearch.acceptLanguages("it"));
        assertEquals("de,en", OnlineSearch.acceptLanguages("de"));
        // Norwegian reaches the app as nb or nn but reads its strings as "no"; whichever of the
        // three arrives is a language Nominatim knows.
        assertEquals("no,en", OnlineSearch.acceptLanguages("no"));
    }

    @Test
    @DisplayName("an English reader asks for English once, not twice")
    void englishIsNotRepeated() {
        assertEquals("en", OnlineSearch.acceptLanguages("en"));
    }

    @Test
    @DisplayName("before the translations exist, English is asked for")
    void withoutALanguageEnglishIsAsked() {
        assertEquals("en", OnlineSearch.acceptLanguages(null));
        assertEquals("en", OnlineSearch.acceptLanguages("   "));
    }

    @Test
    @DisplayName("a name in an alphabet the app cannot draw goes under the ones it can")
    void readableResultsComeFirst() {
        ArrayList<NominatimResponse> results = new ArrayList<>();
        results.add(response("सगरमाथा, सोलुखुम्बु, नेपाल", 27.9881f, 86.9250f));   // nearest
        results.add(response("Mount Everest, Solukhumbu, Nepal", 27.9881f, 86.9250f));
        results.add(response("Mount Everest, Orange County, Florida", 28.38f, -81.56f));

        OnlineSearch.sortByReadabilityThenDistance(results, 27.9f, 86.9f);

        assertEquals("Mount Everest, Solukhumbu, Nepal", results.get(0).displayName);
        assertEquals("Mount Everest, Orange County, Florida", results.get(1).displayName);
        // Kept, not dropped: it may be the very place that was searched for.
        assertEquals("सगरमाथा, सोलुखुम्बु, नेपाल", results.get(2).displayName);
    }

    @Test
    @DisplayName("among names that read alike, the nearest to the map still wins")
    void distanceStillDecidesBetweenReadableNames() {
        ArrayList<NominatimResponse> results = new ArrayList<>();
        results.add(response("Zermatt, Wallis, Switzerland", 46.02f, 7.75f));
        results.add(response("Zermatt, Adelaide, Australia", -34.9f, 138.6f));

        OnlineSearch.sortByReadabilityThenDistance(results, 45.9f, 7.6f);

        assertEquals("Zermatt, Wallis, Switzerland", results.get(0).displayName);
    }

    private static NominatimResponse response(String displayName, float lat, float lon) {
        JsonValue json = new JsonReader().parse(String.format(
                "{\"osm_type\":\"node\",\"lat\":\"%f\",\"lon\":\"%f\",\"display_name\":\"%s\"}",
                lat, lon, displayName));
        return new NominatimResponse(json);
    }
}
