import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.database.GeonameSuggester;
import com.peaknav.tools.GeonamesIndexBuilder;
import com.peaknav.tools.PeakIndexAppender;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the search box offers while a name is being typed.
 *
 * <p>The box asks again at every keystroke, so it is asked about half-written words far more
 * often than about whole ones. It used to answer nothing at all to those: "Matterh" returned an
 * empty list, and only the last letter of "Matterhorn" brought the mountain back.
 */
class TestGeonameSuggestions {

    private static IndexReader reader;
    private static IndexSearcher searcher;

    /** A row of a GeoNames cities dump, carrying the columns the builder reads. */
    private static String cityRow(String id, String name, String ascii, String lat, String lon,
                                  String country, String population) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            fields.add("");
        }
        fields.set(0, id);
        fields.set(1, name);
        fields.set(2, ascii);
        fields.set(4, lat);
        fields.set(5, lon);
        fields.set(8, country);
        fields.set(14, population);
        return String.join("\t", fields);
    }

    /** A row of the alternate-names dump: id, place, language, name, preferred, ..., historic. */
    private static String altRow(String id, String place, String language, String name) {
        return String.join("\t", id, place, language, name, "", "", "", "");
    }

    /**
     * A handful of places chosen for the ways they can be confused with one another: a mountain
     * and a village named after it, a capital known by another name in its own country, a town
     * whose name begins like a famous summit's, and two summits sharing the same first word.
     */
    @BeforeAll
    static void buildIndex(@TempDir Path dir) throws IOException {
        Path cities = dir.resolve("cities.txt");
        Files.write(cities, String.join("\n",
                cityRow("3169070", "Rome", "Rome", "41.89193", "12.51133", "IT", "2318895"),
                cityRow("2172517", "Roma", "Roma", "-26.57", "148.79", "AU", "6848"),
                cityRow("2661604", "Zürich", "Zurich", "47.36667", "8.55", "CH", "415367"),
                cityRow("2660718", "Zermatt", "Zermatt", "46.01936", "7.74861", "CH", "5771"),
                cityRow("3181190", "Cervinara", "Cervinara", "41.02", "14.61", "IT", "10150"),
                cityRow("3025468", "Chamonix-Mont-Blanc", "Chamonix-Mont-Blanc",
                        "45.92375", "6.86933", "FR", "10614")
        ).getBytes(StandardCharsets.UTF_8));

        Path alternates = dir.resolve("alternates.txt");
        Files.write(alternates, String.join("\n",
                altRow("1", "3169070", "it", "Roma"),
                altRow("2", "2661604", "en", "Zurich")
        ).getBytes(StandardCharsets.UTF_8));

        Path index = dir.resolve("index");
        GeonamesIndexBuilder.runner(cities.toString(), alternates.toString(), index.toString());

        // lat, lon, metres, has a Wikipedia article, name, alternate names
        Path peaks = dir.resolve("peaks.tsv");
        Files.write(peaks, String.join("\n",
                "45.97640\t7.65860\t4478\t1\tMatterhorn\tCervino|Mont Cervin",
                "45.93750\t7.86690\t3883\t1\tKlein Matterhorn\t",
                "41.51000\t-115.38000\t3749\t0\tMatterhorn Peak\t",
                "45.83240\t7.86750\t4634\t1\tMonte Rosa Massif\tDufourspitze",
                "45.51750\t7.26770\t4061\t1\tGran Paradiso\t",
                "46.01000\t7.78000\t3135\t0\tGornergrat\tZermatt"
        ).getBytes(StandardCharsets.UTF_8));
        PeakIndexAppender.run(peaks.toString(), index.toString(), 1500);

        reader = IndexReader.open(FSDirectory.open(new File(index.toString())));
        searcher = new IndexSearcher(reader);
    }

    @AfterAll
    static void closeIndex() throws IOException {
        reader.close();
    }

    /** The names offered for what has been typed, in the order the box would list them. */
    private static List<String> offered(String typed) {
        List<String> names = new ArrayList<>();
        for (Document document : GeonameSuggester.suggest(searcher, typed, 5)) {
            names.add(document.get("name"));
        }
        return names;
    }

    private static String best(String typed) {
        List<String> names = offered(typed);
        assertFalse(names.isEmpty(), "nothing was offered for \"" + typed + "\"");
        return names.get(0);
    }

    @Test
    @DisplayName("a half-typed name offers the place it is the beginning of")
    void aPrefixIsEnough() {
        // The bug this was written for: a fuzzy query measures whole words, so three missing
        // letters put "Matterh" further from "Matterhorn" than any usable similarity allows,
        // and the list was empty until the very last keystroke.
        assertEquals("Matterhorn", best("Matterh"));
        for (String typed : new String[]{"Matte", "Matter", "Matterh", "Matterho", "Matterhor"}) {
            assertEquals("Matterhorn", best(typed),
                    "the mountain must be offered from \"" + typed + "\" onwards");
        }
    }

    @Test
    @DisplayName("the place the reader is most likely to mean comes first")
    void theLikeliestPlaceLeads() {
        // Namesakes, ordered by how big they are: two million Romans outweigh a town in
        // Queensland that carries the name the query was actually spelled with.
        assertEquals("Rome", best("Roma"));
        // A village named after the mountain above it does not outrank the mountain.
        assertEquals("Matterhorn", best("Cervin"));
        // ... but a town does outrank a summit that merely lists the town among its names.
        assertEquals("Zermatt", best("Zermat"));
        // The one actually called Monte Rosa, not the summit that answers to it as well.
        assertEquals("Monte Rosa Massif", best("Monte Rosa"));
    }

    @Test
    @DisplayName("a place matching every typed word comes before one matching a single word")
    void allWordsCount() {
        assertEquals("Gran Paradiso", best("Gran Par"));
        // The Matterhorn is the more important mountain by far, and matches one of the two
        // words; the smaller summit that matches both is nevertheless what was asked for.
        List<String> offered = offered("Klein Matterhorn");
        assertEquals("Klein Matterhorn", offered.get(0));
        assertTrue(offered.indexOf("Matterhorn") > 0,
                "a place matching one word of two is offered, but not ahead of the full match");
    }

    @Test
    @DisplayName("when no place matches every word, the ones matching some are still offered")
    void aPartialMatchBeatsAnEmptyList() {
        // Nothing here is both Monte and Klein. Rather than answer nothing, the box falls
        // back on whatever matched a word of it - the reader can see it is not what they
        // asked for, where an empty list tells them nothing at all.
        assertFalse(offered("Monte Klein").isEmpty());
    }

    @Test
    @DisplayName("a misspelling still finds the place, behind anything spelled right")
    void aTypoIsForgiven() {
        assertEquals("Matterhorn", best("Matterhorm"));
        assertEquals("Zermatt", best("Zermattt"));
    }

    @Test
    @DisplayName("a name is found without the accents its own language writes it with")
    void accentsAreOptional() {
        assertEquals("Zürich", best("Zurich"));
        assertEquals("Zürich", best("Zürich"));
        assertEquals("Zürich", best("zuri"));
    }

    @Test
    @DisplayName("a search box can be typed anything at all, and answers without throwing")
    void nothingTypedCanBreakIt() {
        assertTrue(offered("").isEmpty());
        assertTrue(offered("   ").isEmpty());
        assertTrue(offered("(*^[]~ \"").isEmpty(), "Lucene's own syntax is text like any other");
        assertTrue(GeonameSuggester.suggest(searcher, null, 5).isEmpty());
        assertTrue(GeonameSuggester.suggest(null, "Matterhorn", 5).isEmpty(),
                "typing while the index is still being unpacked must answer nothing");
        assertTrue(offered("Xyzzyx").isEmpty(), "a name nowhere in the index has no suggestions");
    }

    @Test
    @DisplayName("no more places are offered than the list can show")
    void theListIsShort() {
        assertTrue(offered("Ma").size() <= 5);
        assertEquals(2, GeonameSuggester.suggest(searcher, "Matterhorn", 2).size());
    }
}
